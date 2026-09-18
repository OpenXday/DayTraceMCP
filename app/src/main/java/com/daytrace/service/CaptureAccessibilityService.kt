package com.daytrace.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import com.daytrace.data.MessageEntry
import com.daytrace.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 日迹无障碍服务 —— 三通道时间线记录
 *
 * 通道1：定时快照（每30秒）
 *   不管在做什么，每30秒拍一次屏幕快照，提取可见文本
 *
 * 通道2：输入实时捕获（TYPE_VIEW_TEXT_CHANGED）
 *   当你在任何 app 的输入框里打字时，实时记录"在哪个app输入了什么"
 *   完整记录输入内容，不截断
 *
 * 通道3：App切换快照（TYPE_WINDOW_STATE_CHANGED）
 *   每次切换 app 时，立刻拍一张快照 + 记录上一个 app 的使用时长
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DayTrace/A11y"

        @Volatile var isRunning = false
            private set

        /** 当前服务实例（供 MCP Server 等外部调用获取 rootInActiveWindow） */
        @Volatile var instance: CaptureAccessibilityService? = null
            private set

        /** 定时快照间隔（毫秒）—— 30秒 */
        private const val SNAPSHOT_INTERVAL_MS = 30_000L

        /** 当前前台 App（供 MCP Server 读取） */
        @Volatile var lastForegroundPackage: String? = null
            private set

        /** 当前前台 App（内部使用，与 lastForegroundPackage 同步） */
        @Volatile private var currentForegroundPackage: String? = null

        /** 当前前台 App 进入时间 */
        @Volatile private var currentAppEnterTime: Long = 0

        /** 上次定时快照时间 */
        @Volatile private var lastSnapshotTime: Long = 0

        // === 输入捕获状态 ===
        /** 当前正在输入的包名 */
        @Volatile private var inputTrackingPackage: String? = null
        /** 当前输入框的最新文本 */
        @Volatile private var currentInputText: String? = null
        /** 上次记录输入文本的时间（用于判断输入暂停） */
        @Volatile private var lastInputTime: Long = 0
        /** 上次记录的输入文本（用于去重） */
        @Volatile private var lastRecordedInput: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** 定时快照任务 */
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            try {
                if (Prefs.isCaptureEnabled(this@CaptureAccessibilityService)) {
                    takeSnapshot()
                }
            } catch (e: Exception) {
                Log.w(TAG, "snapshot failed: ${e.message}")
            }
            handler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    /** 输入暂停检测：如果用户停止输入超过 3 秒，记录最后一次输入内容 */
    private val inputFlushRunnable = object : Runnable {
        override fun run() {
            try {
                flushInputIfNeeded()
            } catch (_: Exception) {}
            handler.postDelayed(this, 3000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        Log.i(TAG, "Accessibility service connected (3-channel timeline mode)")

        // 三通道配置：窗口切换 + 文本变化 + 内容变化
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                notificationTimeout = 100
            }
            serviceInfo = info
            Log.i(TAG, "ServiceInfo configured: window+text+content")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure ServiceInfo: ${e.message}")
        }

        // 启动定时快照
        handler.postDelayed(snapshotRunnable, SNAPSHOT_INTERVAL_MS)
        // 启动输入暂停检测
        handler.postDelayed(inputFlushRunnable, 3000)

        // 写入数据库标记
        scope.launch {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                val entry = MessageEntry(
                    appPackage = "com.daytrace",
                    appName = "DayTrace",
                    direction = MessageEntry.DIR_OUTGOING,
                    contactOrGroup = "系统",
                    text = "[时间线记录已启动]",
                    source = "system"
                )
                app.database.messageDao().insert(entry)
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Prefs.isCaptureEnabled(this)) return

        val packageName = event.packageName?.toString() ?: return

        // 跳过系统 UI 和自身
        if (packageName == "android" || packageName == "com.android.systemui") return
        if (packageName == "com.daytrace") return
        if (Prefs.isExcluded(this, packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(packageName)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (packageName != currentForegroundPackage) {
                    handleWindowChange(packageName)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleTextInput(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 轻量处理：只在内容变化时更新前台包名
                if (packageName != currentForegroundPackage) {
                    currentForegroundPackage = packageName
                    lastForegroundPackage = packageName
                    currentAppEnterTime = System.currentTimeMillis()
                }
            }
        }
    }

    // ========================
    // 通道1：定时快照
    // ========================

    private fun takeSnapshot() {
        val pkg = currentForegroundPackage ?: return
        val now = System.currentTimeMillis()

        try {
            val root = rootInActiveWindow ?: return
            val visibleText = extractVisibleText(root)

            if (visibleText.isNotEmpty()) {
                recordEvent(pkg, visibleText, "snapshot")
            } else {
                // 即使没有提取到文本，也记录一条"屏幕为空"的快照
                recordEvent(pkg, "(无可见文本)", "snapshot")
            }
            lastSnapshotTime = now
        } catch (e: Exception) {
            Log.w(TAG, "snapshot extract failed: ${e.message}")
        }
    }

    /**
     * 提取屏幕所有可见文本
     * 无过滤、无截断、无深度限制，完整记录屏幕上每一个文本节点
     * 按 Y 坐标排序（从上到下），每条文本独立一行
     */
    private fun extractVisibleText(root: AccessibilityNodeInfo): String {
        val textNodes = mutableListOf<Pair<String, Rect>>()
        collectAllTextNodes(root, textNodes)

        if (textNodes.isEmpty()) return ""

        // 按 Y 坐标排序（从上到下）
        textNodes.sortBy { it.second.top }

        // 每条文本一行，完整保留
        return textNodes
            .map { it.first }
            .joinToString("\n")
    }

    /**
     * 递归收集所有有文本的节点，无深度限制、无数量限制
     */
    private fun collectAllTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<String, Rect>>
    ) {
        // 收集 text
        val text = node.text?.toString()?.trim()
        if (text != null && text.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(text to rect)
        }

        // 收集 contentDescription（部分按钮的文本在这里）
        val desc = node.contentDescription?.toString()?.trim()
        if (desc != null && desc.isNotEmpty() && desc != text) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(desc to rect)
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextNodes(child, out)
        }
    }

    // ========================
    // 通道2：输入实时捕获
    // ========================

    private fun handleTextInput(event: AccessibilityEvent, packageName: String) {
        // 获取输入文本
        var text = event.text?.joinToString("")?.trim() ?: ""
        if (text.isEmpty()) {
            val source = event.source
            text = source?.text?.toString()?.trim() ?: ""
        }

        if (text.isEmpty()) {
            // 输入框被清空 —— 可能是发送了消息
            if (currentInputText != null && inputTrackingPackage == packageName) {
                val sentText = currentInputText!!
                val elapsed = SystemClock.elapsedRealtime() - lastInputTime
                if (sentText.length >= 1 && elapsed < 30_000) {
                    // 记录"发送"的输入内容
                    recordInput(packageName, sentText, "input_send")
                }
                currentInputText = null
                inputTrackingPackage = null
            }
            return
        }

        // 更新输入状态
        inputTrackingPackage = packageName
        currentInputText = text
        lastInputTime = SystemClock.elapsedRealtime()
    }

    /**
     * 输入暂停检测：如果用户停止输入超过 3 秒，记录输入内容
     */
    private fun flushInputIfNeeded() {
        val pkg = inputTrackingPackage ?: return
        val text = currentInputText ?: return
        val now = SystemClock.elapsedRealtime()

        // 如果超过 3 秒没有新的输入事件
        if (now - lastInputTime > 3000) {
            // 只在文本变化时记录（与上次记录的不同）
            if (text != lastRecordedInput && text.length >= 1) {
                recordInput(pkg, text, "input")
                lastRecordedInput = text
            }
            // 不清空，因为用户可能继续输入
        }
    }

    private fun recordInput(packageName: String, text: String, source: String) {
        val appName = getAppName(packageName)
        val entry = MessageEntry(
            appPackage = packageName,
            appName = appName,
            direction = MessageEntry.DIR_OUTGOING,
            contactOrGroup = "",
            text = text,
            source = source
        )

        scope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                app.database.messageDao().insert(entry)
                com.daytrace.export.TimelineExporter.appendEntry(applicationContext, entry)
                Log.d(TAG, "Recorded input [$appName] via $source: ${text.take(80)}")
            } catch (e: Exception) {
                Log.e(TAG, "record input failed: ${e.message}")
            }
        }
    }

    // ========================
    // 通道3：App 切换快照
    // ========================

    private fun handleWindowChange(packageName: String) {
        val now = System.currentTimeMillis()

        // 记录上一个 App 的使用时长
        val prevPkg = currentForegroundPackage
        if (prevPkg != null && prevPkg != packageName && currentAppEnterTime > 0) {
            val duration = (now - currentAppEnterTime) / 1000
            if (duration > 2) {
                recordAppUsage(prevPkg, duration)
            }
        }

        // 如果有未刷新的输入，先记录
        val pendingInput = currentInputText
        if (pendingInput != null && inputTrackingPackage == prevPkg && pendingInput.length >= 1) {
            recordInput(prevPkg!!, pendingInput, "input_switch")
            currentInputText = null
            inputTrackingPackage = null
            lastRecordedInput = null
        }

        currentForegroundPackage = packageName
        lastForegroundPackage = packageName
        currentAppEnterTime = now

        // 记录 App 切换
        recordEvent(packageName, "→ $packageName", "window_change")

        // 立即拍一张快照
        try {
            val root = rootInActiveWindow
            if (root != null) {
                // 延迟 500ms 等界面渲染完成
                handler.postDelayed({
                    try {
                        val delayedRoot = rootInActiveWindow
                        if (delayedRoot != null) {
                            val visibleText = extractVisibleText(delayedRoot)
                            if (visibleText.isNotEmpty()) {
                                recordEvent(packageName, visibleText, "switch_snapshot")
                            }
                        }
                    } catch (_: Exception) {}
                }, 500)
            }
        } catch (_: Exception) {}
    }

    private fun recordAppUsage(packageName: String, durationSec: Long) {
        val appName = getAppName(packageName)
        val entry = MessageEntry(
            appPackage = packageName,
            appName = appName,
            direction = MessageEntry.DIR_OUTGOING,
            contactOrGroup = "",
            text = "使用 $appName ${durationSec}秒",
            source = "app_usage"
        )

        scope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                app.database.messageDao().insert(entry)
                com.daytrace.export.TimelineExporter.appendEntry(applicationContext, entry)
            } catch (_: Exception) {}
        }
    }

    // ========================
    // 公共方法
    // ========================

    private fun recordEvent(packageName: String, text: String, source: String) {
        val appName = getAppName(packageName)
        val entry = MessageEntry(
            appPackage = packageName,
            appName = appName,
            direction = MessageEntry.DIR_OUTGOING,
            contactOrGroup = "",
            text = text,
            source = source
        )

        scope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                app.database.messageDao().insert(entry)
                com.daytrace.export.TimelineExporter.appendEntry(applicationContext, entry)
                Log.d(TAG, "Recorded [$appName] via $source: ${text.take(60)}")
            } catch (e: Exception) {
                Log.e(TAG, "record failed: ${e.message}")
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        handler.removeCallbacks(snapshotRunnable)
        handler.removeCallbacks(inputFlushRunnable)
        Log.i(TAG, "Accessibility service destroyed")
    }
}
