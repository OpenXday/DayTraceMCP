package com.daytrace.server

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.daytrace.service.CaptureAccessibilityService
import com.daytrace.util.ShizukuHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统操作管理器
 *
 * 通过 Shizuku shell 执行系统级操作，让 AI 可以远程操控手机 UI：
 *
 * 1. **打开 App**：通过 `am start` / `monkey -p` 启动指定 App
 * 2. **点击屏幕坐标**：通过 `input tap <x> <y>` 模拟触摸点击
 * 3. **输入文字**：通过 `input text "<text>"` 在当前焦点输入框输入文字
 * 4. **模拟按键**：通过 `input keyevent <keycode>` 模拟 Home/Back/Recent 等按键
 * 5. **滑动**：通过 `input swipe` 模拟滑动手势
 *
 * 所有操作都需要 Shizuku 运行且已授权。
 */
class SystemActionManager(private val context: Context) {

    companion object {
        private const val TAG = "DayTrace/Action"

        /** 按键名称到 keycode 的映射 */
        val KEY_EVENTS = mapOf(
            "home" to 3,
            "back" to 4,
            "recent" to 187,          // APP_SWITCH
            "menu" to 82,
            "power" to 26,
            "volume_up" to 24,
            "volume_down" to 25,
            "volume_mute" to 164,
            "delete" to 67,
            "enter" to 66,
            "escape" to 111,
            "tab" to 61,
            "space" to 62,
            "search" to 84,
            "camera" to 27,
            "call" to 5,
            "end_call" to 6,
            "notification" to 83,    // NOTIFICATION
            "brightness_down" to 220,
            "brightness_up" to 221,
            "screenshot" to 120       // SCREENSHOT (Android 11+)
        )
    }

    // === 打开 App ===

    /**
     * 打开指定的 App
     *
     * @param packageName 应用包名（如 com.tencent.mm）
     * @param activity 可选，指定要打开的 Activity（如 .ui.LauncherUI）
     */
    fun openApp(packageName: String, activity: String = ""): String {
        if (!ShizukuHelper.hasPermission()) {
            return "Error: Shizuku permission not granted."
        }

        // 安全校验：包名和 Activity 名只允许字母、数字、点、下划线
        val pkgPattern = Regex("^[a-zA-Z][a-zA-Z0-9._]*$")
        if (!pkgPattern.matches(packageName)) {
            return "Error: Invalid package name '$packageName'. Only letters, digits, dots and underscores allowed."
        }
        if (activity.isNotEmpty() && !pkgPattern.matches(activity)) {
            return "Error: Invalid activity name '$activity'."
        }

        // 先尝试通过 monkey 启动（最通用）
        val cmd = if (activity.isNotEmpty()) {
            val fullActivity = if (activity.startsWith(".")) "$packageName$activity" else activity
            "am start -n $packageName/$fullActivity"
        } else {
            // monkey -p <pkg> 1 是最通用的启动方式
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        }

        Log.i(TAG, "openApp: $cmd")
        val result = ShizukuHelper.execShell(cmd)

        return if (result.isSuccess) {
            val output = result.getOrDefault("")
            if (output.contains("Error") || output.contains("exception")) {
                "Failed to open $packageName: $output"
            } else {
                val appName = getAppName(packageName)
                "App '$appName' ($packageName) launched."
            }
        } else {
            "Failed to open $packageName: ${result.exceptionOrNull()?.message}"
        }
    }

    // === 点击屏幕坐标 ===

    /**
     * 在屏幕指定坐标处模拟点击
     *
     * @param x X 坐标
     * @param y Y 坐标
     */
    fun tapScreen(x: Int, y: Int): String {
        if (!ShizukuHelper.hasPermission()) {
            return "Error: Shizuku permission not granted."
        }

        if (x < 0 || y < 0) {
            return "Error: Invalid coordinates ($x, $y). Must be non-negative."
        }

        val cmd = "input tap $x $y"
        Log.i(TAG, "tapScreen: $cmd")
        val result = ShizukuHelper.execShell(cmd)

        return if (result.isSuccess) {
            "Tapped at ($x, $y)."
        } else {
            "Failed to tap at ($x, $y): ${result.exceptionOrNull()?.message}"
        }
    }

    // === 输入文字 ===

    /**
     * 在当前焦点输入框中输入文字
     *
     * 注意：`input text` 不支持中文，仅支持 ASCII 字符。
     * 中文输入需要通过剪贴板 + 粘贴方式实现。
     *
     * @param text 要输入的文字
     */
    fun inputText(text: String): String {
        if (!ShizukuHelper.hasPermission()) {
            return "Error: Shizuku permission not granted."
        }

        // 检查是否包含非 ASCII 字符（中文等）
        val hasNonAscii = text.any { it.code > 127 }

        if (hasNonAscii) {
            // 中文等非 ASCII 文字：通过剪贴板 + 粘贴实现
            return inputTextViaClipboard(text)
        }

        // ASCII 文字：直接用 input text
        // 使用单引号包裹防止 shell 元字符（$ ` 等）展开
        // 文本中的单引号用 '\'' 转义（结束单引号、转义单引号、重新开始单引号）
        val escaped = text.replace("'", "'\\''")
        val cmd = "input text '$escaped'"
        Log.i(TAG, "inputText: $cmd")
        val result = ShizukuHelper.execShell(cmd)

        return if (result.isSuccess) {
            "Text input: ${text.take(100)}"
        } else {
            "Failed to input text: ${result.exceptionOrNull()?.message}"
        }
    }

    /**
     * 通过剪贴板 + 粘贴方式输入中文文字
     *
     * 流程：
     * 1. 写入文字到剪贴板
     * 2. 模拟 Ctrl+V 或长按粘贴（Android 上用 input keyevent 279 = PASTE）
     */
    private fun inputTextViaClipboard(text: String): String {
        // 1. 设置剪贴板
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DayTrace", text))
        } catch (e: Exception) {
            return "Error setting clipboard for paste: ${e.message}"
        }

        // 2. 模拟粘贴 (keycode 279 = PASTE)
        val pasteResult = ShizukuHelper.execShell("input keyevent 279")
        return if (pasteResult.isSuccess) {
            "Text pasted via clipboard: ${text.take(100)}"
        } else {
            "Clipboard set but paste failed: ${pasteResult.exceptionOrNull()?.message}. " +
            "Text is in clipboard, long-press to paste manually."
        }
    }

    // === 模拟按键 ===

    /**
     * 模拟按键
     *
     * @param key 按键名称（home/back/recent/menu/power/volume_up/volume_down 等）
     *           或直接传入 keycode 数字
     */
    fun pressKey(key: String): String {
        if (!ShizukuHelper.hasPermission()) {
            return "Error: Shizuku permission not granted."
        }

        // 解析按键：先查映射表，再尝试直接解析数字
        val keycode = KEY_EVENTS[key.lowercase()]
            ?: key.toIntOrNull()
            ?: return "Error: Unknown key '$key'. Available keys: ${KEY_EVENTS.keys.joinToString(", ")}"

        val cmd = "input keyevent $keycode"
        Log.i(TAG, "pressKey: $cmd")
        val result = ShizukuHelper.execShell(cmd)

        return if (result.isSuccess) {
            "Key pressed: $key (keycode=$keycode)"
        } else {
            "Failed to press key $key: ${result.exceptionOrNull()?.message}"
        }
    }

    // === 滑动手势 ===

    /**
     * 模拟滑动手势
     *
     * @param startX 起始 X
     * @param startY 起始 Y
     * @param endX 结束 X
     * @param endY 结束 Y
     * @param durationMs 滑动时长（毫秒，默认 300）
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 300): String {
        if (!ShizukuHelper.hasPermission()) {
            return "Error: Shizuku permission not granted."
        }

        val cmd = "input swipe $startX $startY $endX $endY $durationMs"
        Log.i(TAG, "swipe: $cmd")
        val result = ShizukuHelper.execShell(cmd)

        return if (result.isSuccess) {
            "Swiped from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms."
        } else {
            "Failed to swipe: ${result.exceptionOrNull()?.message}"
        }
    }

    // === 获取可交互元素 ===

    /**
     * 提取当前屏幕上的所有可交互元素（按钮/输入框/链接等）
     *
     * 通过无障碍服务遍历视图树，过滤出 clickable=true 的节点，
     * 返回它们的文字/描述/坐标，让 AI 可以知道哪些元素可点以及在哪里点。
     */
    fun getScreenInteractiveElements(): String? {
        if (!CaptureAccessibilityService.isRunning) {
            return null
        }

        val service = CaptureAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null

        val elements = mutableListOf<InteractiveElement>()
        collectInteractiveNodes(root, elements)

        try { root.recycle() } catch (_: Exception) {}

        if (elements.isEmpty()) {
            return "No interactive elements found on screen."
        }

        // 按 Y 坐标排序
        elements.sortBy { it.bounds.top }

        return buildString {
            appendLine("=== Interactive Elements (${elements.size}) ===")
            appendLine("Foreground: ${CaptureAccessibilityService.lastForegroundPackage ?: "unknown"}")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())}")
            appendLine()
            elements.forEach { el ->
                val center = el.bounds.let { Pair((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
                val label = el.text.ifEmpty { el.contentDesc.ifEmpty { "(no label)" } }
                appendLine("  [$el.type] $label")
                appendLine("    bounds: (${el.bounds.left},${el.bounds.top})-(${el.bounds.right},${el.bounds.bottom}) center: (${center.first},${center.second})")
                el.viewId?.let { appendLine("    id: $it") }
            }
            appendLine()
            appendLine("Use tap_screen with center coordinates to interact.")
        }
    }

    /**
     * 递归收集所有可交互节点
     */
    private fun collectInteractiveNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<InteractiveElement>,
        depth: Int = 0
    ) {
        // 防止视图树过深导致栈溢出
        if (depth > 50) return

        val isClickable = node.isClickable
        val isFocusable = node.isFocusable
        val isScrollable = node.isScrollable
        val hasClickAction = node.actions and AccessibilityNodeInfo.ACTION_CLICK != 0

        if (isClickable || isFocusable || isScrollable || hasClickAction) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val className = node.className?.toString() ?: ""
            val type = when {
                className.contains("Button", true) -> "Button"
                className.contains("EditText", true) || className.contains("AutoComplete", true) -> "InputField"
                className.contains("CheckBox", true) -> "CheckBox"
                className.contains("RadioButton", true) -> "RadioButton"
                className.contains("Spinner", true) -> "Spinner"
                className.contains("Switch", true) || className.contains("Toggle", true) -> "Toggle"
                className.contains("ImageView", true) -> "ImageClickable"
                isScrollable -> "Scrollable"
                hasClickAction -> "Clickable"
                else -> "Focusable"
            }

            // 只收集有文字/描述或明确类型的
            if (text.isNotEmpty() || desc.isNotEmpty() || isClickable || isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                out.add(InteractiveElement(
                    type = type,
                    text = text,
                    contentDesc = desc,
                    bounds = rect,
                    viewId = node.viewIdResourceName
                ))
            }
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInteractiveNodes(child, out, depth + 1)
        }
    }

    // === 数据类 ===

    data class InteractiveElement(
        val type: String,
        val text: String,
        val contentDesc: String,
        val bounds: Rect,
        val viewId: String?
    )

    // === 工具方法 ===

    private fun getAppName(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
