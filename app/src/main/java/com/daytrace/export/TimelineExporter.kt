package com.daytrace.export

import android.content.Context
import android.util.Log
import com.daytrace.data.MessageEntry
import com.daytrace.data.MessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间线 Markdown 导出器
 *
 * 将一天的活动记录按时间线整理成 Markdown 文件。
 * 文件存储在 App 的外部文件目录下：timeline/timeline-2026-08-19.md
 *
 * 格式示例：
 * # 2026-08-19 时间线
 *
 * ## 上午
 *
 * ### 08:30 飞书
 * - 打开应用
 * - 张晓波: 看看有没有帮助
 * - 使用 飞书 120秒
 *
 * ### 09:15 微信
 * - 打开应用
 * ...
 */
object TimelineExporter {

    private const val TAG = "DayTrace/Export"

    /**
     * 导出指定日期的时间线
     * @param dateMillis 该日期的任意时间点（取当天 0:00 ~ 23:59）
     * @return 生成的文件路径，或 null 失败
     */
    suspend fun exportDay(context: Context, dateMillis: Long): String? = withContext(Dispatchers.IO) {
        try {
            val dao = (context.applicationContext as com.daytrace.DayTraceApp).database.messageDao()
            val cal = Calendar.getInstance(Locale.CHINA)

            // 当天 0:00
            cal.timeInMillis = dateMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis

            // 次日 0:00
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val endOfDay = cal.timeInMillis

            val messages = dao.messagesByDateRange(startOfDay, endOfDay, 5000)
            if (messages.isEmpty()) {
                Log.i(TAG, "No messages for this date")
                return@withContext null
            }

            val markdown = generateMarkdown(messages, startOfDay)

            // 写入文件
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(startOfDay))
            val dir = java.io.File(context.getExternalFilesDir(null), "timeline")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "timeline-$dateStr.md")
            file.writeText(markdown, Charsets.UTF_8)

            Log.i(TAG, "Exported ${messages.size} entries to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            null
        }
    }

    /**
     * 导出今天的时间线
     */
    suspend fun exportToday(context: Context): String? {
        return exportDay(context, System.currentTimeMillis())
    }

    /**
     * 获取时间线文件内容（不写文件，直接返回字符串）
     */
    suspend fun getDayMarkdown(context: Context, dateMillis: Long): String? = withContext(Dispatchers.IO) {
        try {
            val dao = (context.applicationContext as com.daytrace.DayTraceApp).database.messageDao()
            val cal = Calendar.getInstance(Locale.CHINA)

            cal.timeInMillis = dateMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis

            cal.add(Calendar.DAY_OF_MONTH, 1)
            val endOfDay = cal.timeInMillis

            val messages = dao.messagesByDateRange(startOfDay, endOfDay, 5000)
            if (messages.isEmpty()) return@withContext null

            generateMarkdown(messages, startOfDay)
        } catch (e: Exception) {
            Log.e(TAG, "getDayMarkdown failed: ${e.message}", e)
            null
        }
    }

    /**
     * 列出所有已导出的时间线文件
     */
    fun listExportedFiles(context: Context): List<String> {
        val dir = java.io.File(context.getExternalFilesDir(null), "timeline")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "md" }
            ?.sortedByDescending { it.name }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * 生成 Markdown 内容
     */
    private fun generateMarkdown(messages: List<MessageEntry>, startOfDay: Long): String {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val tf = SimpleDateFormat("HH:mm", Locale.CHINA)
        val dateStr = df.format(Date(startOfDay))

        val sb = StringBuilder()
        sb.appendLine("# $dateStr 时间线")
        sb.appendLine()
        sb.appendLine("> 本文件由 DayTrace 自动生成，记录当日手机使用轨迹。")
        sb.appendLine("> 可提交给大模型总结你的一天。")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 按小时分组
        val byHour = messages.groupBy { msg ->
            val cal = Calendar.getInstance(Locale.CHINA)
            cal.timeInMillis = msg.timestamp
            cal.get(Calendar.HOUR_OF_DAY)
        }

        // 统计概要
        sb.appendLine("## 概要")
        sb.appendLine()
        sb.appendLine("- 总记录数: ${messages.size}")
        sb.appendLine("- 涉及应用: ${messages.map { it.appName }.distinct().size} 个")

        // App 使用统计
        val appCounts = messages.groupBy { it.appName }
            .map { (app, msgs) -> app to msgs.size }
            .sortedByDescending { it.second }
        sb.appendLine("- 应用分布:")
        appCounts.forEach { (app, count) ->
            sb.appendLine("  - $app: $count 条记录")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 按时段分组
        val morning = (6..11)
        val afternoon = (12..17)
        val evening = (18..23)
        val night = (0..5)

        val sections = listOf(
            "## 清晨 (00:00 - 05:59)" to night,
            "## 上午 (06:00 - 11:59)" to morning,
            "## 下午 (12:00 - 17:59)" to afternoon,
            "## 晚上 (18:00 - 23:59)" to evening
        )

        for ((title, hours) in sections) {
            val hourMessages = byHour.filterKeys { it in hours }
            if (hourMessages.isEmpty()) continue

            sb.appendLine(title)
            sb.appendLine()

            for (hour in hours) {
                val msgs = byHour[hour] ?: continue

                // 按 App 分组
                val byApp = msgs.groupBy { it.appName }

                for ((appName, appMsgs) in byApp) {
                    val firstTime = appMsgs.minOf { it.timestamp }
                    sb.appendLine("### ${tf.format(Date(firstTime))} $appName")
                    sb.appendLine()
                    sb.appendLine("| 时间 | 来源 | 内容 |")
                    sb.appendLine("|------|------|------|")

                    for (msg in appMsgs.sortedBy { it.timestamp }) {
                        val time = tf.format(Date(msg.timestamp))
                        val source = when (msg.source) {
                            "window_change" -> "打开应用"
                            "snapshot" -> "屏幕快照"
                            "app_usage" -> "使用时长"
                            "notification" -> "通知"
                            "system" -> "系统"
                            else -> msg.source
                        }
                        val dir = if (msg.direction == MessageEntry.DIR_OUTGOING) "↑" else "↓"
                        val contact = if (msg.contactOrGroup.isNotEmpty()) "[${msg.contactOrGroup}] " else ""
                        // 转义 Markdown 表格中的 |
                        val text = msg.text.replace("|", "\\|").replace("\n", " ").take(200)
                        sb.appendLine("| $time | $source | $dir $contact$text |")
                    }
                    sb.appendLine()
                }
            }
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("> **提示**: 将此文件内容提交给 AI 助手，可以让它帮你总结今天做了什么、和谁沟通了什么、花了多少时间。")

        return sb.toString()
    }
}
