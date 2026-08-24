package com.daytrace.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音管理器
 *
 * 使用 AudioRecord + MediaCodec(AAC) + MediaMuxer 实现录音，
 * 输出 AAC 封装的 M4A 文件。
 *
 * 流程：
 * 1. 创建 AudioRecord（MIC, 44100Hz, stereo, 16bit）
 * 2. 创建 MediaCodec AAC encoder
 * 3. 创建 MediaMuxer 输出 M4A
 * 4. 循环读取 PCM → 编码 AAC → 写入 Muxer
 * 5. 指定时长后停止，刷新编码器，关闭 Muxer
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "DayTrace/Audio"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BIT_RATE = 128_000
        private const val MAX_DURATION_SEC = 300
    }

    /** 录音结果 */
    data class AudioResult(
        val file: File,
        val mimeType: String,
        val durationSec: Int,
        val sampleRate: Int,
        val channels: String,
        val fileSize: Long,
        val timestamp: Long
    )

    /**
     * 录音
     *
     * @param durationSec 录音时长（秒），默认 10，最大 300
     * @param format 音频格式："aac" (默认) 或 "wav"
     * @return AudioResult 或 null（失败时）
     */
    fun recordAudio(
        durationSec: Int = 10,
        format: String = "aac"
    ): AudioResult? {
        if (!checkPermission()) return null

        val duration = durationSec.coerceIn(1, MAX_DURATION_SEC)
        val timestamp = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.CHINA).format(Date(timestamp))

        return if (format.lowercase() == "wav") {
            recordWav(duration, dateStr, timestamp)
        } else {
            recordAac(duration, dateStr, timestamp)
        }
    }

    /** 检查录音权限 */
    private fun checkPermission(): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) Log.e(TAG, "RECORD_AUDIO permission not granted")
        return granted
    }

    /**
     * AAC 录音（MediaCodec + MediaMuxer）
     */
    private fun recordAac(duration: Int, dateStr: String, timestamp: Long): AudioResult? {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBufSize")
            return null
        }

        val bufSize = maxOf(minBufSize * 2, 8192)

        // 创建 AudioRecord
        val audioRecord = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}", e)
            return null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            audioRecord.release()
            return null
        }

        // 创建 AAC encoder
        val channels = if (CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }

        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec create failed: ${e.message}", e)
            audioRecord.release()
            return null
        }

        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // 创建输出文件
        val fileName = "audio_${dateStr}.m4a"
        val capturesDir = File(context.getExternalFilesDir("captures"), "audio")
        capturesDir.mkdirs()
        val audioFile = File(capturesDir, fileName)

        // 创建 MediaMuxer
        val muxer = try {
            MediaMuxer(audioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer create failed: ${e.message}", e)
            codec.stop()
            codec.release()
            audioRecord.release()
            return null
        }

        var muxerTrackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuffer = ByteArray(bufSize)
        var totalBytesRead = 0L
        val startTimeMs = System.currentTimeMillis()
        val endTimeMs = startTimeMs + duration * 1000L

        // 启动录音
        audioRecord.startRecording()
        Log.i(TAG, "AAC recording started, duration=${duration}s")

        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS && !Thread.currentThread().isInterrupted) {
            val currentTime = System.currentTimeMillis()
            val inputEos = sawInputEOS || currentTime >= endTimeMs

            // 读取 PCM 数据并送入编码器
            if (!sawInputEOS) {
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    inputBuffer.clear()

                    val readBytes = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        inputBuffer.put(pcmBuffer, 0, readBytes)
                        totalBytesRead += readBytes
                        val ptsUs = (System.currentTimeMillis() - startTimeMs) * 1000
                        codec.queueInputBuffer(inputBufferIndex, 0, readBytes, ptsUs, 0)
                    } else if (readBytes == AudioRecord.ERROR_INVALID_OPERATION || readBytes == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioRecord read error: $readBytes")
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, (System.currentTimeMillis() - startTimeMs) * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    }
                }

                if (inputEos) {
                    // 发送 EOS
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        codec.queueInputBuffer(idx, 0, 0, (System.currentTimeMillis() - startTimeMs) * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    }
                }
            }

            // 从编码器取出编码数据写入 Muxer
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 等待
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.e(TAG, "Output format changed twice")
                        break
                    }
                    val newFormat = codec.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started, track=$muxerTrackIndex")
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        }

        // 清理
        try { audioRecord.stop() } catch (_: Exception) {}
        audioRecord.release()
        codec.stop()
        codec.release()
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()

        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file is empty or missing")
            return null
        }

        val actualDuration = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
        Log.i(TAG, "Audio saved: ${audioFile.absolutePath} (${audioFile.length()} bytes, ${actualDuration}s)")

        return AudioResult(
            file = audioFile,
            mimeType = "audio/mp4",
            durationSec = actualDuration,
            sampleRate = SAMPLE_RATE,
            channels = if (channels == 2) "stereo" else "mono",
            fileSize = audioFile.length(),
            timestamp = timestamp
        )
    }

    /**
     * WAV 录音（直接写 PCM + WAV header）
     */
    private fun recordWav(duration: Int, dateStr: String, timestamp: Long): AudioResult? {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBufSize")
            return null
        }

        val bufSize = maxOf(minBufSize * 2, 8192)

        val audioRecord = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}", e)
            return null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return null
        }

        val fileName = "audio_${dateStr}.wav"
        val capturesDir = File(context.getExternalFilesDir("captures"), "audio")
        capturesDir.mkdirs()
        val audioFile = File(capturesDir, fileName)

        val channels = if (CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val pcmData = mutableListOf<Byte>()
        val pcmBuffer = ByteArray(bufSize)
        val startTimeMs = System.currentTimeMillis()
        val endTimeMs = startTimeMs + duration * 1000L

        audioRecord.startRecording()
        Log.i(TAG, "WAV recording started, duration=${duration}s")

        while (System.currentTimeMillis() < endTimeMs && !Thread.currentThread().isInterrupted) {
            val readBytes = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
            if (readBytes > 0) {
                pcmData.addAll(pcmBuffer.toList().subList(0, readBytes))
            }
        }

        try { audioRecord.stop() } catch (_: Exception) {}
        audioRecord.release()

        // 写 WAV 文件
        val pcmBytes = pcmData.toByteArray()
        writeWavFile(audioFile, pcmBytes, SAMPLE_RATE, channels, 16)

        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "WAV file is empty or missing")
            return null
        }

        val actualDuration = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
        Log.i(TAG, "WAV saved: ${audioFile.absolutePath} (${audioFile.length()} bytes, ${actualDuration}s)")

        return AudioResult(
            file = audioFile,
            mimeType = "audio/wav",
            durationSec = actualDuration,
            sampleRate = SAMPLE_RATE,
            channels = if (channels == 2) "stereo" else "mono",
            fileSize = audioFile.length(),
            timestamp = timestamp
        )
    }

    /** 写入 WAV 文件（PCM + RIFF header） */
    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val chunkSize = 36 + pcmData.size
        val subchunk2Size = pcmData.size
        val blockAlign = channels * (bitsPerSample / 8)

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(chunkSize))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16))  // subchunk1 size
            fos.write(shortToLittleEndian(1))  // audio format (PCM)
            fos.write(shortToLittleEndian(channels))
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(blockAlign))
            fos.write(shortToLittleEndian(bitsPerSample))

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(subchunk2Size))
            fos.write(pcmData)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
}
