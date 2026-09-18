package com.daytrace.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.daytrace.service.CaptureAccessibilityService
import com.daytrace.util.ShizukuHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 屏幕截图与文字提取管理器
 *
 * 提供两个核心能力：
 *
 * 1. **屏幕截屏**：通过 Shizuku shell 执行 `screencap -p` 命令，
 *    以 shell 用户权限截取当前屏幕完整画面，保存为 PNG 文件。
 *    需要无障碍服务处于运行状态（用于触发截图时机），
 *    但实际截图由 Shizuku shell 完成（不依赖 MediaProjection 权限弹窗）。
 *
 * 2. **屏幕文字提取**：通过无障碍服务的 [AccessibilityNodeInfo] 树，
 *    递归遍历当前屏幕所有节点，提取 text / contentDescription，
 *    按 Y 坐标排序（从上到下），返回完整的屏幕可见文字。
 *
 * 这两个能力由 MCP Server 的 `take_screenshot` 和 `get_screen_text` 工具调用。
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "DayTrace/Screen"

        /** 截图超时时间（毫秒） */
        private const val SCREENCAP_TIMEOUT_MS = 15_000L

        /** 截图保存目录（与 photos/videos/audio 同级） */
        private const val SCREENSHOTS_DIR = "screenshots"
    }

    /**
     * 截取当前屏幕，保存为 PNG 文件
     *
     * 通过 Shizuku shell 执行 `screencap -p <filepath>` 命令。
     * Shizuku shell 用户有权限直接调用 framebuffer 截屏。
     *
     * @param format 图片格式：'png'（无损）或 'jpeg'（压缩，体积更小）
     * @param quality JPEG 质量（1-100），仅 format=jpeg 时生效，默认 80
     * @return [ScreenshotResult] 包含文件路径、尺寸等信息；失败返回 null
     */
    fun takeScreenshot(format: String = "png", quality: Int = 80): ScreenshotResult? {
        // 检查 Shizuku 权限
        if (!ShizukuHelper.hasPermission()) {
            Log.e(TAG, "takeScreenshot: Shizuku permission not granted")
            return null
        }

        // 创建保存目录
        val capturesDir = context.getExternalFilesDir("captures")!!
        val screenshotsDir = File(capturesDir, SCREENSHOTS_DIR).apply {
            if (!exists()) mkdirs()
        }

        // 生成文件名
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.CHINA).format(Date())
        val ext = if (format.equals("jpeg", ignoreCase = true) || format.equals("jpg", ignoreCase = true)) {
            "jpg"
        } else {
            "png"
        }
        val filename = "screenshot_${timestamp}.$ext"

        // Shizuku shell 能写入的临时路径（/data/local/tmp/）
        // 使用唯一文件名避免并发截图调用时互相覆盖
        val tmpName = "daytrace_screenshot_${System.currentTimeMillis()}_${Thread.currentThread().id}.$ext"
        val tmpPath = "/data/local/tmp/$tmpName"
        // 最终保存路径（App 外部存储）
        val finalFile = File(screenshotsDir, filename)

        // 执行 screencap 命令
        val isJpeg = ext == "jpg"
        val screencapCmd = if (isJpeg) {
            // Android 14+ 的 screencap 支持 -e jpeg -q <quality>
            // 旧版本不支持 -e 参数，回退为 PNG 后再转换
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "screencap -e jpeg -q $quality $tmpPath"
            } else {
                "screencap -p $tmpPath"
            }
        } else {
            "screencap -p $tmpPath"
        }

        Log.i(TAG, "takeScreenshot: executing: $screencapCmd")
        val result = ShizukuHelper.execShell(screencapCmd)
        if (result.isFailure) {
            Log.e(TAG, "takeScreenshot: screencap failed: ${result.exceptionOrNull()?.message}")
            return null
        }

        // 读取临时文件
        val tmpFile = File(tmpPath)
        if (!tmpFile.exists()) {
            Log.e(TAG, "takeScreenshot: screencap output file not found: $tmpPath")
            return null
        }

        // 复制到最终路径
        try {
            // 如果需要 JPEG 但旧系统只生成了 PNG，则转换
            if (isJpeg && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 读取 PNG 并转换为 JPEG
                val bitmap = BitmapFactory.decodeFile(tmpPath)
                if (bitmap != null) {
                    FileOutputStream(finalFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                    }
                    bitmap.recycle()
                } else {
                    // 解码失败，直接复制 PNG
                    tmpFile.copyTo(finalFile, overwrite = true)
                }
            } else {
                // 直接复制
                tmpFile.copyTo(finalFile, overwrite = true)
            }

            // 删除临时文件
            tmpFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot: copy failed: ${e.message}")
            // 尝试直接用临时文件
            try {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            } catch (e2: Exception) {
                Log.e(TAG, "takeScreenshot: fallback copy also failed: ${e2.message}")
                return null
            }
        }

        // 读取图片尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(finalFile.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight

        // 获取当前前台 App
        val foregroundPackage = try {
            // 通过 Shizuku 获取当前前台 Activity
            val cmd = "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity' | head -1"
            val output = ShizukuHelper.execShell(cmd).getOrDefault("")
            // 解析输出，例如：mResumedActivity: ActivityRecord{... com.tencent.mm/.ui.LauncherUI ...}
            val regex = Regex("([a-zA-Z0-9.]+)/([a-zA-Z0-9._]+)")
            regex.find(output)?.value ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        return ScreenshotResult(
            file = finalFile,
            width = width,
            height = height,
            fileSize = finalFile.length(),
            format = if (isJpeg) "jpeg" else "png",
            foregroundPackage = foregroundPackage,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 提取当前屏幕所有可见文字
     *
     * 通过无障碍服务的 [AccessibilityNodeInfo] 树，递归遍历所有节点，
     * 收集 text 和 contentDescription，按 Y 坐标排序（从上到下）。
     *
     * @return [ScreenTextResult] 包含完整文字内容和元信息；如果无障碍服务未运行返回 null
     */
    fun getScreenText(): ScreenTextResult? {
        // 检查无障碍服务是否运行
        if (!CaptureAccessibilityService.isRunning) {
            Log.w(TAG, "getScreenText: accessibility service not running")
            return null
        }

        // 获取当前屏幕的根节点
        // 由于 AccessibilityService 是系统绑定的服务，我们需要通过静态方法获取
        val service = CaptureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "getScreenText: service instance not available")
            return null
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "getScreenText: rootInActiveWindow is null")
            return null
        }

        // 收集所有文本节点
        val textNodes = mutableListOf<TextNode>()
        collectAllTextNodes(root, textNodes)

        // 释放节点
        try {
            root.recycle()
        } catch (_: Exception) {}

        if (textNodes.isEmpty()) {
            return ScreenTextResult(
                text = "(屏幕上无可见文本)",
                nodeCount = 0,
                foregroundPackage = CaptureAccessibilityService.lastForegroundPackage ?: "unknown",
                timestamp = System.currentTimeMillis()
            )
        }

        // 按 Y 坐标排序（从上到下）
        textNodes.sortBy { it.bounds.top }

        // 构建输出：每条文本一行
        val fullText = textNodes.joinToString("\n") { it.text }

        // 获取前台 App
        val foregroundPackage = CaptureAccessibilityService.lastForegroundPackage ?: "unknown"

        return ScreenTextResult(
            text = fullText,
            nodeCount = textNodes.size,
            foregroundPackage = foregroundPackage,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 递归收集所有有文本的节点
     */
    private fun collectAllTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<TextNode>,
        depth: Int = 0
    ) {
        // 防止视图树过深导致栈溢出
        if (depth > 50) return

        // 收集 text
        val text = node.text?.toString()?.trim()
        if (text != null && text.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(TextNode(text = text, bounds = rect, source = "text"))
        }

        // 收集 contentDescription
        val desc = node.contentDescription?.toString()?.trim()
        if (desc != null && desc.isNotEmpty() && desc != text) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(TextNode(text = desc, bounds = rect, source = "desc"))
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextNodes(child, out, depth + 1)
        }
    }

    /**
     * 截取屏幕的指定区域
     *
     * 先截取全屏，然后裁剪到指定坐标范围。
     *
     * @param left 区域左边界
     * @param top 区域上边界
     * @param right 区域右边界
     * @param bottom 区域下边界
     * @param format 图片格式：'png' 或 'jpeg'
     * @param quality JPEG 质量（1-100）
     * @return [ScreenshotResult] 包含裁剪后的文件信息；失败返回 null
     */
    fun takeScreenshotRegion(
        left: Int, top: Int, right: Int, bottom: Int,
        format: String = "png", quality: Int = 80
    ): ScreenshotResult? {
        if (left >= right || top >= bottom || left < 0 || top < 0) {
            Log.e(TAG, "takeScreenshotRegion: invalid bounds ($left,$top,$right,$bottom)")
            return null
        }

        // 先截全屏
        val fullShot = takeScreenshot("png", quality) ?: return null

        // 注意：takeScreenshot 内部会清理自己的临时文件和最终文件
        // 但 fullShot.file 仍然存在于 screenshots 目录，裁剪后需清理

        // 裁剪
        return try {
            val bitmap = BitmapFactory.decodeFile(fullShot.file.absolutePath)
                ?: return null

            // 确保裁剪范围不超出图片
            val cropLeft = left.coerceAtMost(bitmap.width - 1)
            val cropTop = top.coerceAtMost(bitmap.height - 1)
            val cropRight = right.coerceAtMost(bitmap.width)
            val cropBottom = bottom.coerceAtMost(bitmap.height)

            val cropped = Bitmap.createBitmap(
                bitmap,
                cropLeft, cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop
            )
            bitmap.recycle()

            // 保存裁剪后的图片
            val isJpeg = format.equals("jpeg", ignoreCase = true) || format.equals("jpg", ignoreCase = true)
            val ext = if (isJpeg) "jpg" else "png"

            val capturesDir = context.getExternalFilesDir("captures")!!
            val screenshotsDir = File(capturesDir, SCREENSHOTS_DIR).apply { if (!exists()) mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.CHINA).format(Date())
            val filename = "screenshot_region_${timestamp}.$ext"
            val finalFile = File(screenshotsDir, filename)

            FileOutputStream(finalFile).use { fos ->
                if (isJpeg) {
                    cropped.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                } else {
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
            }
            cropped.recycle()

            // 清理全屏截图临时文件（裁剪不再需要）
            try { fullShot.file.delete() } catch (_: Exception) {}

            ScreenshotResult(
                file = finalFile,
                width = cropRight - cropLeft,
                height = cropBottom - cropTop,
                fileSize = finalFile.length(),
                format = if (isJpeg) "jpeg" else "png",
                foregroundPackage = fullShot.foregroundPackage,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshotRegion: crop failed: ${e.message}")
            null
        }
    }

    /** 将文件编码为 Base64 字符串 */
    fun encodeFileBase64(file: File): String? {
        return try {
            val bytes = file.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encodeFileBase64 failed: ${e.message}")
            null
        }
    }

    // === 数据类 ===

    /** 截图结果 */
    data class ScreenshotResult(
        val file: File,
        val width: Int,
        val height: Int,
        val fileSize: Long,
        val format: String,  // "png" or "jpeg"
        val foregroundPackage: String,
        val timestamp: Long
    )

    /** 屏幕文字提取结果 */
    data class ScreenTextResult(
        val text: String,
        val nodeCount: Int,
        val foregroundPackage: String,
        val timestamp: Long
    )

    /** 文本节点（内部使用） */
    private data class TextNode(
        val text: String,
        val bounds: Rect,
        val source: String  // "text" or "desc"
    )
}
