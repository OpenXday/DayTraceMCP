package com.daytrace.service

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.daytrace.data.MessageEntry
import com.daytrace.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知监听服务 —— 捕获"对方发来的消息"
 *
 * 通过系统通知获取各IM应用的新消息。
 * 优点：不需要点开聊天（不触发已读），跨应用通用
 * 局限：只能拿到通知中显示的内容（部分IM通知会隐藏内容）
 */
class NotificationCaptureService : NotificationListenerService() {

    companion object {
        private const val TAG = "DayTrace/Notif"

        @Volatile var isRunning = false
            private set

        /** 已知IM/AI 应用包名 */
        private val IM_PACKAGES = setOf(
            // 传统IM
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.whatsapp",
            "com.discord",
            "com.slack",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "com.microsoft.teams",
            "com.skype.raider",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.signal.android",
            "im.vector.app",
            // 中文社交
            "com.bilibili.app.in",
            "com.sina.weibo",
            "com.zhihu.android",
            "com.xingin.xhs",
            "com.ss.android.ugc.aweme",
            // AI 应用
            "com.coze.space",                 // 扣子
            "com.bytedance.android.doubaoime",// 豆包
            "com.zhipu.agent",               // 智谱 agent
            "com.zhipuai.qingyan",            // 智谱清言
            "com.moonshot.kimichat",          // Kimi
            "com.openai.chatgpt",             // ChatGPT
            "ai.perplexity.app.android",      // Perplexity
            "ai.perplexity.comet",
            "com.tencent.hunyuan.app.chat",   // 腾讯混元
            "com.tencent.workbuddy.app"      // 腾讯元宝
        )

        /** 通知中要跳过的标题 */
        private val SKIP_TITLES = setOf(
            "正在输入…", "对方正在输入", "typing…", "typing",
            "微信", "QQ", "Telegram", "WhatsApp", "飞书", "钉钉"
        )

        /** 群聊特征关键词（出现在 subText 或消息文本中） */
        private val GROUP_KEYWORDS = setOf(
            "群", "group", "Group", "群聊", "群组"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentKeys = ArrayDeque<String>(50)

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!Prefs.isCaptureEnabled(this)) return

        val packageName = sbn.packageName
        if (Prefs.isExcluded(this, packageName)) return
        if (!IM_PACKAGES.contains(packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras

        // 跳过正在进行中的通知
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim() ?: ""
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim() ?: ""

        // === 尝试获取 RemoteInput 历史消息 ===
        // 部分应用会把多条消息打包到 EXTRA_MESSAGES 中
        val remoteInputMessages = mutableListOf<String>()
        val messagesArr = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messagesArr != null) {
            for (parcelable in messagesArr) {
                try {
                    @Suppress("DEPRECATION")
                    val bundle = parcelable as? android.os.Bundle
                    val mText = bundle?.getCharSequence("text")?.toString()?.trim() ?: ""
                    if (mText.isNotEmpty()) remoteInputMessages.add(mText)
                } catch (_: Exception) {}
            }
        }

        // === 解析 bigText 中可能包含的多行消息 ===
        // 飞书/钉钉等 IM 的 bigText 可能含 "\n" 分隔的多条消息
        val bigTextLines = if (bigText.isNotEmpty()) bigText.split("\n").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

        // 消息正文优先取 bigText，其次 text
        // 如果 bigText 是多行的，最后一行通常是最新消息
        val messageText = when {
            bigText.isNotEmpty() && bigText != text -> bigText
            text.isNotEmpty() -> text
            remoteInputMessages.isNotEmpty() -> remoteInputMessages.last()
            else -> return
        }

        if (messageText.length < 2) return
        if (SKIP_TITLES.contains(messageText)) return

        // 去重
        val dedupKey = "$packageName:$title:${messageText.hashCode()}"
        synchronized(recentKeys) {
            if (recentKeys.contains(dedupKey)) return
            recentKeys.addFirst(dedupKey)
            if (recentKeys.size > 50) recentKeys.removeLast()
        }

        // === 判断群聊/私聊 ===
        // 微信群聊通知特征：
        //   title = 发送者名, subText/subText 含群名, text = "发送者: 消息内容"
        //   或 messageText 含 "在群聊中" / "(群名)"
        // 钉钉群聊：subText 含群名
        // 通用：如果 subText 或 summaryText 不为空且与 title 不同 → 群聊

        val isGroup = isGroupChat(packageName, title, subText, summaryText, infoText, messageText)

        // === 提取联系人/群名 ===
        // 飞书通知特征：title="飞书", text="发送者: 消息内容"
        // 需要从 text 中提取发送者名作为 contactOrGroup
        val sender = when {
            isGroup -> {
                // 群聊：优先 subText > summaryText > infoText > title
                val groupName = subText.takeIf { it.isNotEmpty() }
                    ?: summaryText.takeIf { it.isNotEmpty() }
                    ?: infoText.takeIf { it.isNotEmpty() }
                    ?: title
                groupName
            }
            subText.isNotEmpty() && subText != title -> subText
            title.isNotEmpty() && !SKIP_TITLES.contains(title) -> title
            else -> {
                // title 是应用名（如"飞书"），从消息文本中提取发送者名
                // 格式: "发送者名: 消息内容"
                extractSenderFromText(messageText)
            }
        }

        // 群聊时从消息文本中提取发送者名
        val actualSender = if (isGroup) {
            extractGroupSender(messageText, sender)
        } else {
            sender
        }

        val chatType = if (isGroup) MessageEntry.CHAT_GROUP else MessageEntry.CHAT_PRIVATE

        // 对于私聊，如果从 text 提取到了发送者名，去掉前缀
        val cleanMessageText = if (!isGroup && sender.isNotEmpty() && messageText.startsWith("$sender: ")) {
            messageText.substring("$sender: ".length).trim()
        } else {
            messageText
        }

        saveIncomingMessage(packageName, actualSender, cleanMessageText, chatType)

        // === 保存 bigText 中的多行消息（如果有多行且不等于主消息） ===
        // 飞书/钉钉等 IM 的 bigText 可能含多条消息，逐行解析
        if (bigTextLines.size > 1) {
            bigTextLines.forEach line@{ line ->
                if (line == messageText) return@line
                if (line.length < 2) return@line
                if (SKIP_TITLES.contains(line)) return@line
                val lineDedupKey = "$packageName:$title:${line.hashCode()}"
                synchronized(recentKeys) {
                    if (recentKeys.contains(lineDedupKey)) return@synchronized
                    recentKeys.addFirst(lineDedupKey)
                    if (recentKeys.size > 50) recentKeys.removeLast()
                }
                // 对每行也提取发送者名并清理前缀
                val lineSender = if (!isGroup && sender.isEmpty()) extractSenderFromText(line) else actualSender
                val cleanLine = if (!isGroup && lineSender.isNotEmpty() && line.startsWith("$lineSender: ")) {
                    line.substring("$lineSender: ".length).trim()
                } else {
                    line
                }
                saveIncomingMessage(packageName, lineSender.ifEmpty { actualSender }, cleanLine, chatType)
            }
        }

        // === 保存 RemoteInput 多条历史消息 ===
        remoteInputMessages.forEach rMsg@{ rMsg ->
            if (rMsg == messageText) return@rMsg
            if (rMsg.length < 2) return@rMsg
            val rDedupKey = "$packageName:$title:${rMsg.hashCode()}"
            synchronized(recentKeys) {
                if (recentKeys.contains(rDedupKey)) return@synchronized
                recentKeys.addFirst(rDedupKey)
                if (recentKeys.size > 50) recentKeys.removeLast()
            }
            saveIncomingMessage(packageName, actualSender, rMsg, chatType)
        }
    }

    /**
     * 判断是否为群聊通知
     */
    private fun isGroupChat(
        packageName: String,
        title: String,
        subText: String,
        summaryText: String,
        infoText: String,
        messageText: String
    ): Boolean {
        // 如果 subText 或 summaryText 不为空，很可能是群聊
        if (subText.isNotEmpty() && subText != title) return true
        if (summaryText.isNotEmpty() && summaryText != title) return true

        // 微信群聊：消息文本格式为 "发送者: 消息内容"
        if (packageName == "com.tencent.mm") {
            // 群聊消息通常含 ": " 前缀（发送者名）
            val colonIdx = messageText.indexOf(": ")
            if (colonIdx > 0 && colonIdx < 20) return true
            // 或文本中含群名特征
            if (messageText.contains("在群聊中") || messageText.contains("群公告")) return true
        }

        // 钉钉群聊
        if (packageName == "com.alibaba.android.rimet") {
            if (infoText.isNotEmpty()) return true
        }

        // 通用：检查是否含群关键词
        // 注意：只在 subText 为空时才用文本关键词判断，避免误判
        return false
    }

    /**
     * 从群聊消息文本中提取发送者名
     * 微信群聊通知格式: "张三: 消息内容"
     */
    private fun extractGroupSender(messageText: String, groupName: String): String {
        val colonIdx = messageText.indexOf(": ")
        if (colonIdx in 1..20) {
            val sender = messageText.substring(0, colonIdx).trim()
            val actualText = messageText.substring(colonIdx + 2).trim()
            return if (sender.isNotEmpty()) "$sender @$groupName" else groupName
        }
        return groupName
    }

    /**
     * 从消息文本中提取发送者名
     * 飞书私聊通知格式: "发送者名: 消息内容"
     * 当 title 是应用名（如"飞书"）时，从 text 中提取发送者名
     */
    private fun extractSenderFromText(messageText: String): String {
        // 查找 ": " 分隔符
        val colonIdx = messageText.indexOf(": ")
        if (colonIdx in 1..30) {
            val sender = messageText.substring(0, colonIdx).trim()
            // 过滤掉明显的非发送者名文本
            if (sender.isNotEmpty() &&
                !sender.contains("通知") &&
                !sender.contains("提醒") &&
                !sender.contains("[") &&
                !sender.contains("http")) {
                return sender
            }
        }
        return ""
    }

    private fun saveIncomingMessage(packageName: String, sender: String, text: String, chatType: Int) {
        val appName = try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }

        // 如果是群聊且提取了发送者，需要从 text 中去掉发送者前缀
        val cleanText = if (chatType == MessageEntry.CHAT_GROUP && text.contains(": ")) {
            val colonIdx = text.indexOf(": ")
            if (colonIdx in 1..20) text.substring(colonIdx + 2).trim() else text
        } else {
            // 私聊：如果 sender 已提取且 text 含 "sender: " 前缀，去掉
            if (sender.isNotEmpty() && text.startsWith("$sender: ")) {
                text.substring("$sender: ".length).trim()
            } else {
                text
            }
        }

        val entry = MessageEntry(
            appPackage = packageName,
            appName = appName,
            direction = MessageEntry.DIR_INCOMING,
            contactOrGroup = sender,
            text = cleanText,
            source = "notification",
            chatType = chatType
        )

        scope.launch {
            try {
                val app = applicationContext as com.daytrace.DayTraceApp
                app.database.messageDao().insert(entry)
                Log.d(TAG, "Saved incoming [$appName/$sender] group=$chatType: ${cleanText.take(50)}")
            } catch (e: Exception) {
                Log.e(TAG, "save failed: ${e.message}")
            }
        }
    }
}
