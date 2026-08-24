package com.daytrace.server

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.Manifest
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 相机拍照/录像管理器
 *
 * 使用 Camera2 API 实现远程拍照和录像，由 MCP Server 工具调用。
 * 所有方法均为同步阻塞调用（在后台线程中执行），通过 CountDownLatch 等待异步回调。
 *
 * 拍照流程：
 * 1. openCamera → CameraDevice 回调
 * 2. 创建 ImageReader（JPEG）
 * 3. 创建 CaptureSession（preview surface + ImageReader surface）
 * 4. 发送单次拍照请求（TEMPLATE_STILL_CAPTURE）
 * 5. ImageReader 回调 → 读取 JPEG 数据 → 写入文件
 * 6. 从 CaptureResult 提取拍摄参数
 *
 * 录像流程：
 * 1. openCamera → CameraDevice 回调
 * 2. 配置 MediaRecorder（Surface + audio source）
 * 3. 创建 CaptureSession（MediaRecorder surface）
 * 4. 切换为录像模板，启动 MediaRecorder
 * 5. 等待指定时长
 * 6. 停止 MediaRecorder，关闭相机
 */
@SuppressLint("MissingPermission")
class CameraCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "DayTrace/Camera"
        private const val CAMERA_OPEN_TIMEOUT_MS = 5000L
        private const val CAPTURE_TIMEOUT_MS = 10000L
    }

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Background thread join interrupted")
        }
        backgroundThread = null
        backgroundHandler = null
    }

    /** 检查权限 */
    private fun checkPermissions(): Boolean {
        val cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            Log.e(TAG, "Camera permission not granted")
        }
        return cameraGranted
    }

    /** 拍照结果 */
    data class PhotoResult(
        val file: File,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val fileSize: Long,
        val cameraFacing: String,
        val focalLength: Float? = null,
        val iso: Int? = null,
        val exposureTimeNs: Long? = null,
        val aperture: Float? = null,
        val whiteBalanceMode: String? = null,
        val flashMode: String? = null,
        val timestamp: Long
    )

    /** 录像结果 */
    data class VideoResult(
        val file: File,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val durationSec: Int,
        val fileSize: Long,
        val cameraFacing: String,
        val timestamp: Long
    )

    /**
     * 拍照
     *
     * @param facing "back" 或 "front"
     * @param resolution "720p", "1080p", "4k", "max", 或 "WxH"（如 "1920x1080"）
     * @param flash "auto", "on", "off"
     * @return PhotoResult 或 null（失败时）
     */
    fun takePhoto(
        facing: String = "back",
        resolution: String = "1080p",
        flash: String = "off"
    ): PhotoResult? {
        if (!checkPermissions()) return null

        val cameraFacing = if (facing.lowercase() == "front")
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        startBackgroundThread()

        val cameraId = findCameraId(cameraFacing) ?: run {
            Log.e(TAG, "No camera found for facing=$facing")
            stopBackgroundThread()
            return null
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val (targetW, targetH) = parseResolution(resolution, characteristics)

        Log.i(TAG, "takePhoto: camera=$cameraId facing=$facing res=${targetW}x${targetH} flash=$flash")

        // 创建 ImageReader
        val imageReader = ImageReader.newInstance(
            targetW, targetH, ImageFormat.JPEG, 2
        )

        val captureLatch = CountDownLatch(1)
        val captureResultRef = AtomicReference<TotalCaptureResult?>()
        val imageRef = AtomicReference<Image?>()
        val errorRef = AtomicReference<String?>()

        // ImageReader 回调
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                imageRef.set(image)
                Log.d(TAG, "Image available")
            }
        }, backgroundHandler)

        // 打开相机
        val cameraDeviceRef = AtomicReference<CameraDevice?>()
        val openLatch = CountDownLatch(1)

        val cameraStateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "Camera opened")
                cameraDeviceRef.set(camera)
                openLatch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                openLatch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera open error: $error")
                camera.close()
                errorRef.set("Camera open error: $error")
                openLatch.countDown()
            }
        }

        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed: ${e.message}", e)
            stopBackgroundThread()
            return null
        }

        if (!openLatch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Camera open timeout")
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        val camera = cameraDeviceRef.get()
        if (camera == null) {
            Log.e(TAG, "Camera device null: ${errorRef.get()}")
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        // 创建拍照 session
        val sessionLatch = CountDownLatch(1)
        val sessionRef = AtomicReference<CameraCaptureSession?>()

        val surfaces = listOf(imageReader.surface)

        val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Capture session configured")
                sessionRef.set(session)
                sessionLatch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Capture session configure failed")
                errorRef.set("Session configure failed")
                sessionLatch.countDown()
            }
        }

        try {
            camera.createCaptureSession(surfaces, sessionStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed: ${e.message}", e)
            camera.close()
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        if (!sessionLatch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Session config timeout")
            camera.close()
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        val session = sessionRef.get()
        if (session == null) {
            Log.e(TAG, "Session null: ${errorRef.get()}")
            camera.close()
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        // 构建拍照请求
        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(imageReader.surface)

        // 闪光灯
        when (flash.lowercase()) {
            "on" -> {
                captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
            }
            "off" -> {
                captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            "auto" -> {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
        }

        // 对焦
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        // 方向（JPEG 旋转）
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val jpegRotation = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            // 前置摄像头需要镜像
            (360 - sensorOrientation) % 360
        } else {
            sensorOrientation
        }
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation)

        // 拍照回调
        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                Log.d(TAG, "Capture completed")
                captureResultRef.set(result)
                captureLatch.countDown()
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                Log.e(TAG, "Capture failed: ${failure.reason}")
                errorRef.set("Capture failed: ${failure.reason}")
                captureLatch.countDown()
            }
        }

        try {
            session.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "session.capture failed: ${e.message}", e)
            camera.close()
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        // 等待拍照完成
        if (!captureLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Capture timeout")
            camera.close()
            imageReader.close()
            stopBackgroundThread()
            return null
        }

        val image = imageRef.get()
        val captureResult = captureResultRef.get()

        // 清理相机资源
        try { session.stopRepeating() } catch (_: Exception) {}
        camera.close()
        imageReader.close()

        if (image == null) {
            Log.e(TAG, "No image captured: ${errorRef.get()}")
            stopBackgroundThread()
            return null
        }

        // 读取 JPEG 数据
        val jpegData = imageToJpegBytes(image)
        image.close()

        if (jpegData == null) {
            Log.e(TAG, "Failed to read JPEG data")
            stopBackgroundThread()
            return null
        }

        // 写入文件
        val timestamp = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.CHINA).format(Date(timestamp))
        val fileName = "photo_${dateStr}.jpg"
        val capturesDir = File(context.getExternalFilesDir("captures"), "photos")
        capturesDir.mkdirs()
        val photoFile = File(capturesDir, fileName)
        FileOutputStream(photoFile).use { it.write(jpegData) }

        Log.i(TAG, "Photo saved: ${photoFile.absolutePath} (${jpegData.size} bytes)")

        // 提取拍摄参数
        val photoResult = PhotoResult(
            file = photoFile,
            mimeType = "image/jpeg",
            width = image.width,
            height = image.height,
            fileSize = jpegData.size.toLong(),
            cameraFacing = facing,
            focalLength = extractFocalLength(captureResult, characteristics),
            iso = extractIso(captureResult),
            exposureTimeNs = extractExposureTime(captureResult),
            aperture = extractAperture(captureResult, characteristics),
            whiteBalanceMode = extractWhiteBalance(captureResult),
            flashMode = flash,
            timestamp = timestamp
        )

        stopBackgroundThread()
        return photoResult
    }

    /**
     * 录像
     *
     * @param facing "back" 或 "front"
     * @param durationSec 录制时长（秒），默认 6
     * @param resolution "720p" (默认), "1080p", "480p"
     * @return VideoResult 或 null（失败时）
     */
    @SuppressLint("MissingPermission")
    fun recordVideo(
        facing: String = "back",
        durationSec: Int = 6,
        resolution: String = "720p"
    ): VideoResult? {
        if (!checkPermissions()) return null

        // 录像还需要录音权限
        val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Log.e(TAG, "RECORD_AUDIO permission not granted (needed for video)")
            return null
        }

        val cameraFacing = if (facing.lowercase() == "front")
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        startBackgroundThread()

        val cameraId = findCameraId(cameraFacing) ?: run {
            Log.e(TAG, "No camera found for facing=$facing")
            stopBackgroundThread()
            return null
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val (targetW, targetH) = parseVideoResolution(resolution, characteristics)

        // 限制最大时长
        val duration = durationSec.coerceIn(1, 60)

        Log.i(TAG, "recordVideo: camera=$cameraId facing=$facing res=${targetW}x${targetH} duration=${duration}s")

        val timestamp = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.CHINA).format(Date(timestamp))
        val fileName = "video_${dateStr}.mp4"
        val capturesDir = File(context.getExternalFilesDir("captures"), "videos")
        capturesDir.mkdirs()
        val videoFile = File(capturesDir, fileName)

        // 配置 MediaRecorder
        val mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile.absolutePath)
            setVideoEncodingBitRate(calculateBitrate(targetW, targetH))
            setVideoFrameRate(30)
            setVideoSize(targetW, targetH)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setMaxDuration(duration * 1000)
            // 旋转
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                setOrientationHint((360 - sensorOrientation) % 360)
            } else {
                setOrientationHint(sensorOrientation)
            }
            prepare()
        }

        // 打开相机
        val cameraDeviceRef = AtomicReference<CameraDevice?>()
        val openLatch = CountDownLatch(1)
        val errorRef = AtomicReference<String?>()

        val cameraStateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "Camera opened for video")
                cameraDeviceRef.set(camera)
                openLatch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                openLatch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera open error for video: $error")
                camera.close()
                errorRef.set("Camera open error: $error")
                openLatch.countDown()
            }
        }

        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera for video failed: ${e.message}", e)
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        if (!openLatch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Camera open timeout for video")
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        val camera = cameraDeviceRef.get()
        if (camera == null) {
            Log.e(TAG, "Camera device null for video: ${errorRef.get()}")
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        // 创建录像 session
        val sessionLatch = CountDownLatch(1)
        val sessionRef = AtomicReference<CameraCaptureSession?>()

        val recorderSurface = mediaRecorder.surface

        val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Video session configured")
                sessionRef.set(session)
                sessionLatch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Video session configure failed")
                errorRef.set("Video session configure failed")
                sessionLatch.countDown()
            }
        }

        try {
            camera.createCaptureSession(listOf(recorderSurface), sessionStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession for video failed: ${e.message}", e)
            camera.close()
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        if (!sessionLatch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Video session config timeout")
            camera.close()
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        val session = sessionRef.get()
        if (session == null) {
            Log.e(TAG, "Video session null: ${errorRef.get()}")
            camera.close()
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        // 构建录像请求
        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        captureBuilder.addTarget(recorderSurface)
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

        // 开始录像
        try {
            session.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler)
            mediaRecorder.start()
            Log.i(TAG, "Video recording started, duration=${duration}s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            camera.close()
            mediaRecorder.release()
            stopBackgroundThread()
            return null
        }

        // 等待录制完成
        try {
            Thread.sleep(duration * 1000L)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Recording sleep interrupted")
        }

        // 停止录像
        try {
            session.stopRepeating()
            mediaRecorder.stop()
            Log.i(TAG, "Video recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording: ${e.message}", e)
        }

        // 清理
        camera.close()
        mediaRecorder.reset()
        mediaRecorder.release()
        stopBackgroundThread()

        if (!videoFile.exists() || videoFile.length() == 0L) {
            Log.e(TAG, "Video file is empty or missing")
            return null
        }

        Log.i(TAG, "Video saved: ${videoFile.absolutePath} (${videoFile.length()} bytes)")

        return VideoResult(
            file = videoFile,
            mimeType = "video/mp4",
            width = targetW,
            height = targetH,
            durationSec = duration,
            fileSize = videoFile.length(),
            cameraFacing = facing,
            timestamp = timestamp
        )
    }

    // === 辅助方法 ===

    private fun findCameraId(facing: Int): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == facing
            }
        } catch (e: Exception) {
            Log.e(TAG, "findCameraId failed: ${e.message}")
            null
        }
    }

    /**
     * 解析分辨率字符串
     * "720p" → 1280x720
     * "1080p" → 1920x1080
     * "4k" → 3840x2160
     * "max" → 传感器最大输出
     * "WxH" → 自定义
     */
    private fun parseResolution(resolution: String, characteristics: CameraCharacteristics): Pair<Int, Int> {
        // 尝试 WxH 格式
        val customMatch = Regex("(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).matchEntire(resolution)
        if (customMatch != null) {
            return customMatch.groupValues[1].toInt() to customMatch.groupValues[2].toInt()
        }

        // 查找 JPEG 流的最大分辨率
        val jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.sortedByDescending { it.width * it.height }

        when (resolution.lowercase()) {
            "max" -> {
                val maxSize = jpegSizes?.firstOrNull()
                if (maxSize != null) return maxSize.width to maxSize.height
            }
            "4k", "2160p" -> {
                // 查找最接近 3840x2160 的
                val target = 3840 * 2160
                val closest = jpegSizes?.minByOrNull { Math.abs(it.width * it.height - target) }
                if (closest != null) return closest.width to closest.height
            }
            "1080p", "fhd" -> {
                val target = 1920 * 1080
                val closest = jpegSizes?.minByOrNull { Math.abs(it.width * it.height - target) }
                if (closest != null) return closest.width to closest.height
            }
            "720p", "hd" -> {
                val target = 1280 * 720
                val closest = jpegSizes?.minByOrNull { Math.abs(it.width * it.height - target) }
                if (closest != null) return closest.width to closest.height
            }
            "480p" -> {
                val target = 854 * 480
                val closest = jpegSizes?.minByOrNull { Math.abs(it.width * it.height - target) }
                if (closest != null) return closest.width to closest.height
            }
        }

        // 默认返回 1080p 或最大
        val default = jpegSizes?.find { it.width >= 1920 }
            ?: jpegSizes?.firstOrNull()
        return (default?.width ?: 1920) to (default?.height ?: 1080)
    }

    /**
     * 解析视频分辨率
     */
    private fun parseVideoResolution(resolution: String, characteristics: CameraCharacteristics): Pair<Int, Int> {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)
            ?.sortedByDescending { it.width * it.height }

        when (resolution.lowercase()) {
            "1080p", "fhd" -> {
                val target = 1920 * 1080
                val closest = videoSizes?.filter { it.width * it.height <= target }
                    ?.maxByOrNull { it.width * it.height }
                if (closest != null) return closest.width to closest.height
            }
            "720p", "hd" -> {
                val target = 1280 * 720
                val closest = videoSizes?.filter { it.width * it.height <= target }
                    ?.maxByOrNull { it.width * it.height }
                if (closest != null) return closest.width to closest.height
            }
            "480p" -> {
                val target = 854 * 480
                val closest = videoSizes?.filter { it.width * it.height <= target }
                    ?.maxByOrNull { it.width * it.height }
                if (closest != null) return closest.width to closest.height
            }
            "max" -> {
                val max = videoSizes?.firstOrNull()
                if (max != null) return max.width to max.height
            }
        }

        // 尝试 WxH 格式
        val customMatch = Regex("(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).matchEntire(resolution)
        if (customMatch != null) {
            val w = customMatch.groupValues[1].toInt()
            val h = customMatch.groupValues[2].toInt()
            // 检查是否被支持
            val supported = videoSizes?.any { it.width == w && it.height == h }
            if (supported == true) return w to h
        }

        // 默认 720p
        val default = videoSizes?.find { it.width >= 1280 }
            ?: videoSizes?.firstOrNull()
        return (default?.width ?: 1280) to (default?.height ?: 720)
    }

    /** 计算视频比特率 */
    private fun calculateBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        return when {
            pixels >= 3840 * 2160 -> 25_000_000  // 4K
            pixels >= 1920 * 1080 -> 8_000_000   // 1080p
            pixels >= 1280 * 720 -> 4_000_000     // 720p
            else -> 2_000_000
        }
    }

    /** 从 Image 读取 JPEG 字节 */
    private fun imageToJpegBytes(image: Image): ByteArray? {
        return try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "imageToJpegBytes failed: ${e.message}")
            null
        }
    }

    // === 拍摄参数提取 ===

    private fun extractFocalLength(result: TotalCaptureResult?, chars: CameraCharacteristics): Float? {
        return try {
            result?.get(CaptureResult.LENS_FOCAL_LENGTH)
                ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private fun extractIso(result: TotalCaptureResult?): Int? {
        return try {
            result?.get(CaptureResult.SENSOR_SENSITIVITY)
        } catch (e: Exception) { null }
    }

    private fun extractExposureTime(result: TotalCaptureResult?): Long? {
        return try {
            result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        } catch (e: Exception) { null }
    }

    private fun extractAperture(result: TotalCaptureResult?, chars: CameraCharacteristics): Float? {
        return try {
            result?.get(CaptureResult.LENS_APERTURE)
                ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private fun extractWhiteBalance(result: TotalCaptureResult?): String? {
        return try {
            when (result?.get(CaptureResult.CONTROL_AWB_STATE)) {
                CameraMetadata.CONTROL_AWB_STATE_INACTIVE -> "inactive"
                CameraMetadata.CONTROL_AWB_STATE_SEARCHING -> "searching"
                CameraMetadata.CONTROL_AWB_STATE_CONVERGED -> "converged"
                CameraMetadata.CONTROL_AWB_STATE_LOCKED -> "locked"
                else -> null
            }
        } catch (e: Exception) { null }
    }
}
