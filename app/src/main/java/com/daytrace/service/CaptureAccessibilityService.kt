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
import com.daytrace.net.WebhookSender
import com.daytrace.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 无障碍服务 —— 时间线记录器
 *
 * 设计理念：不打扰用户，低频快照，按时间线记录手机使用轨迹。
 *
 * 工作模式：
 * 1. 监听窗口切换事件（TYPE_WINDOW_STATE_CHANGED），记录"用户打开了哪个App"
 * 2. 每 30 秒做一次轻量快照：记录当前前台 App + 屏幕上可见的关键文本
 * 3. 不逐条推送 Webhook，只存 DB
 * 4. Markdown 导出和 Webhook 摘要由其他组件按天/按需触发
 *
 * 这样做的优点：
 * - 省电：不做高频的 View 遍历
 * - 不打扰：不会疯狂推送
 * - 不遗漏：窗口切换 + 定期快照覆盖了主要活动轨迹
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DayTrace/A11y"

        @Volatile var isRunning = false
            private set

        /** 快照间隔（毫秒） */
        private const val SNAPSHOT_INTERVAL_MS = 30_000L

        /** 当前正在前台的应用包名 */
        @Volatile private var currentForegroundPackage: String? = null

        /** 当前前台 App 进入时间 */
        @Volatile private var currentAppEnterTime: Long = 0

        /** 上次快照时间 */
        @Volatile private var lastSnapshotTime: Long = 0

        /** 已记录过的 (packageName, text) 组合，避免重复 */
        private val recordedHashes = ArrayDeque<Int>(300)

        /** 上次窗口切换时记录的包名，避免重复记录同一 App */
        @Volatile private var lastWindowChangePkg: String? = null
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "Accessibility service connected (timeline mode)")

        // 动态配置：只监听窗口切换 + 文本变化（轻量）
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 200
            }
            serviceInfo = info
            Log.i(TAG, "ServiceInfo configured for timeline mode")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure ServiceInfo: ${e.message}")
        }

        // 启动定时快照
        handler.postDelayed(snapshotRunnable, SNAPSHOT_INTERVAL_MS)

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
                handleWindowChange(packageName, event)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // 窗口变化时，如果前台 App 变了，记录
                if (packageName != currentForegroundPackage) {
                    handleWindowChange(packageName, event)
                }
            }
        }
    }

    /**
     * 窗口切换：记录"用户打开了某 App"
     */
    private fun handleWindowChange(packageName: String, event: AccessibilityEvent) {
        val now = System.currentTimeMillis()

        // 记录上一个 App 的使用时长
        val prevPkg = currentForegroundPackage
        if (prevPkg != null && prevPkg != packageName && currentAppEnterTime > 0) {
            val duration = (now - currentAppEnterTime) / 1000
            if (duration > 3) {  // 超过3秒才算有效使用
                recordAppUsage(prevPkg, currentAppEnterTime, now, duration)
            }
        }

        currentForegroundPackage = packageName
        currentAppEnterTime = now
        lastWindowChangePkg = packageName

        // 记录窗口切换事件
        recordEvent(packageName, "打开应用", "window_change")
    }

    /**
     * 定时快照：轻量地记录当前屏幕上可见的关键文本
     */
    private fun takeSnapshot() {
        val pkg = currentForegroundPackage ?: return
        val now = System.currentTimeMillis()

        // 至少使用 10 秒后才做快照（避免快速切换）
        if (now - currentAppEnterTime < 10_000) return

        // 节流：同一 App 每 60 秒最多一次快照
        if (now - lastSnapshotTime < 60_000) return
        lastSnapshotTime = now

        try {
            val root = rootInActiveWindow ?: return
            val visibleText = extractKeyText(root, pkg)
            if (visibleText.isNotEmpty()) {
                recordEvent(pkg, visibleText, "snapshot")
            }
        } catch (e: Exception) {
            Log.w(TAG, "snapshot extract failed: ${e.message}")
        }
    }

    /**
     * 从屏幕节点中提取关键文本（轻量版）
     * 只取前 5 个有意义的文本节点，不做全量遍历
     */
    private fun extractKeyText(root: AccessibilityNodeInfo, packageName: String): String {
        val texts = mutableListOf<String>()
        collectMeaningfulTexts(root, texts, 0, 5, 8)

        if (texts.isEmpty()) return ""

        return texts
            .filter { it.length >= 4 }  // 只要长度 >= 4 的
            .filter { !isUiNoise(it) }
            .take(5)  // 最多取 5 条
            .joinToString(" | ")
    }

    /**
     * 收集有意义的文本节点（深度受限）
     */
    private fun collectMeaningfulTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
        maxDepth: Int,
        maxResults: Int
    ) {
        if (depth > maxDepth || out.size >= maxResults) return

        val text = node.text?.toString()?.trim()
        if (text != null && text.isNotEmpty() && text.length <= 200) {
            out.add(text)
        }

        for (i in 0 until node.childCount) {
            if (out.size >= maxResults) return
            val child = node.getChild(i) ?: continue
            collectMeaningfulTexts(child, out, depth + 1, maxDepth, maxResults)
        }
    }

    /**
     * 判断是否为 UI 噪音文本
     */
    private fun isUiNoise(text: String): Boolean {
        // 纯时间
        if (text.matches(Regex("""^\d{1,2}:\d{2}$"""))) return true
        // 纯数字
        if (text.matches(Regex("""^\d+$"""))) return true
        // 日期
        if (text.matches(Regex("""^\d{1,2}/\d{1,2}"""))) return true
        // 太短（按钮/标签）
        if (text.length <= 3) return true
        // 常见 UI 文本
        val noiseSet = setOf(
            "发送", "取消", "确定", "保存", "返回", "更多", "搜索", "设置",
            "消息", "通讯录", "工作台", "日历", "文档", "我的",
            "分享", "收藏", "转发", "删除", "复制", "详情页",
            "表情", "语音", "视频", "文件", "位置"
        )
        if (text in noiseSet) return true
        return false
    }

    /**
     * 记录一条时间线事件到 DB
     */
    private fun recordEvent(packageName: String, text: String, source: String) {
        // 去重
        val hash = "$packageName:$text:${System.currentTimeMillis() / 60_000}".hashCode()
        synchronized(recordedHashes) {
            if (recordedHashes.contains(hash)) return
            recordedHashes.addFirst(hash)
            if (recordedHashes.size > 300) recordedHashes.removeLast()
        }

        val appName = getAppName(packageName)
        val entry = MessageEntry(
            appPackage = packageName,
            appName = appName,
            direction = MessageEntry.DIR_OUTGOING,
            contactOrGroup = "",
            text = text,
            source = source
        )

        scope.launch {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                app.database.messageDao().insert(entry)
                Log.d(TAG, "Recorded [$appName] via $source: ${text.take(60)}")
            } catch (e: Exception) {
                Log.e(TAG, "record failed: ${e.message}")
            }
        }
    }

    /**
     * 记录 App 使用时长
     */
    private fun recordAppUsage(packageName: String, enterTime: Long, exitTime: Long, durationSec: Long) {
        val appName = getAppName(packageName)
        val text = "使用 $appName ${durationSec}秒"
        val entry = MessageEntry(
            appPackage = packageName,
            appName = appName,
            direction = MessageEntry.DIR_OUTGOING,
            contactOrGroup = "",
            text = text,
            source = "app_usage"
        )

        scope.launch {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                app.database.messageDao().insert(entry)
            } catch (_: Exception) {}
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
        handler.removeCallbacks(snapshotRunnable)
        Log.i(TAG, "Accessibility service destroyed")
    }
}
