package com.daytrace.export

import android.content.Context
import android.util.Log
import com.daytrace.data.MessageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间线 Markdown 记录器
 *
 * 核心设计：每条记录实时追加到当天的 Markdown 文件，不等待手动导出。
 *
 * 文件路径：Android/data/com.daytrace/files/timeline/timeline-2026-08-19.md
 *
 * 文件结构：
 * 1. 文件头（首次创建时写入）：标题 + 说明
 * 2. 实时追加区：每条记录一行，按时间正序
 * 3. 手动调用 exportDay() 时：重新生成完整版（含概要统计）
 */
object TimelineExporter {

    private const val TAG = "DayTrace/Export"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 获取当天的 Markdown 文件 */
    private fun getTodayFile(context: Context, timestamp: Long = System.currentTimeMillis()): File {
        val dateStr = dateFormat.format(Date(timestamp))
        val dir = File(context.getExternalFilesDir(null), "timeline")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "timeline-$dateStr.md")
    }

    /**
     * 追加一条记录到当天的 Markdown 文件
     * 如果文件不存在，先创建并写入头部。
     * 这个方法设计为高频调用，同步写文件（在 IO 线程调用）。
     */
    fun appendEntry(context: Context, entry: MessageEntry) {
        try {
            val file = getTodayFile(context, entry.timestamp)

            // 首次创建：写入文件头
            if (!file.exists()) {
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val dateStr = dateFormat.format(Date(entry.timestamp))
                    writer.write("# $dateStr 时间线\n\n")
                    writer.write("> 由 DayTrace 自动生成，实时记录手机使用轨迹。\n")
                    writer.write("> 每条记录在产生时即刻追加，可随时提交给 AI 总结。\n\n")
                    writer.write("---\n\n")
                    writer.write("| 时间 | 来源 | 应用 | 方向 | 联系人 | 内容 |\n")
                    writer.write("|------|------|------|------|--------|------|\n")
                }
            }

            // 追加一行
            val time = timeFormat.format(Date(entry.timestamp))
            val source = sourceLabel(entry.source)
            val dir = if (entry.direction == MessageEntry.DIR_OUTGOING) "↑" else "↓"
            val contact = if (entry.contactOrGroup.isNotEmpty()) entry.contactOrGroup else "-"
            // 转义 Markdown 表格中的 | 和换行
            val text = entry.text.replace("|", "\\|").replace("\n", " ").take(500)
            val appName = entry.appName.replace("|", "\\|")

            val line = "| $time | $source | $appName | $dir | $contact | $text |\n"

            FileWriter(file, true).use { it.write(line) }
        } catch (e: Exception) {
            Log.w(TAG, "appendEntry failed: ${e.message}")
        }
    }

    private fun sourceLabel(source: String): String {
        return when (source) {
            "input" -> "输入"
            "input_send" -> "发送"
            "input_switch" -> "输入→切换"
            "window_change" -> "切换"
            "snapshot" -> "快照"
            "switch_snapshot" -> "切换快照"
            "app_usage" -> "时长"
            "notification" -> "通知"
            "system" -> "系统"
            "system_event" -> "事件"
            else -> source
        }
    }

    // === 完整导出（含统计概要，适合最终归档）===

    /**
     * 导出指定日期的完整时间线（含统计概要）
     * 会重新从 DB 读取所有记录，生成带统计的完整版 Markdown
     */
    suspend fun exportDay(context: Context, dateMillis: Long): String? = withContext(Dispatchers.IO) {
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

            val markdown = generateFullMarkdown(messages, startOfDay)

            val dateStr = dateFormat.format(Date(startOfDay))
            val dir = File(context.getExternalFilesDir(null), "timeline")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "timeline-$dateStr.md")
            file.writeText(markdown, Charsets.UTF_8)

            Log.i(TAG, "Full export: ${messages.size} entries to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            null
        }
    }

    suspend fun exportToday(context: Context): String? {
        return exportDay(context, System.currentTimeMillis())
    }

    /**
     * 获取当天时间线内容（从实时文件读取，不重新生成）
     */
    suspend fun getDayMarkdown(context: Context, dateMillis: Long): String? = withContext(Dispatchers.IO) {
        try {
            // 优先读取实时文件
            val file = getTodayFile(context, dateMillis)
            if (file.exists()) {
                return@withContext file.readText(Charsets.UTF_8)
            }

            // 文件不存在，从 DB 生成
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

            generateFullMarkdown(messages, startOfDay)
        } catch (e: Exception) {
            Log.e(TAG, "getDayMarkdown failed: ${e.message}", e)
            null
        }
    }

    fun listExportedFiles(context: Context): List<String> {
        val dir = File(context.getExternalFilesDir(null), "timeline")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "md" }
            ?.sortedByDescending { it.name }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * 生成完整版 Markdown（含统计概要 + 按时段分组）
     */
    private fun generateFullMarkdown(messages: List<MessageEntry>, startOfDay: Long): String {
        val dateStr = dateFormat.format(Date(startOfDay))
        val tf = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

        val sb = StringBuilder()
        sb.appendLine("# $dateStr 时间线")
        sb.appendLine()
        sb.appendLine("> 由 DayTrace 自动生成，记录当日手机使用轨迹。")
        sb.appendLine("> 可提交给大模型总结你的一天。")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 统计概要
        sb.appendLine("## 概要")
        sb.appendLine()
        sb.appendLine("- 总记录数: ${messages.size}")
        sb.appendLine("- 涉及应用: ${messages.map { it.appName }.distinct().size} 个")
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

        // 完整记录表
        sb.appendLine("## 完整记录")
        sb.appendLine()
        sb.appendLine("| 时间 | 来源 | 应用 | 方向 | 联系人 | 内容 |")
        sb.appendLine("|------|------|------|------|--------|------|")

        for (msg in messages.sortedBy { it.timestamp }) {
            val time = tf.format(Date(msg.timestamp))
            val source = sourceLabel(msg.source)
            val dir = if (msg.direction == MessageEntry.DIR_OUTGOING) "↑" else "↓"
            val contact = if (msg.contactOrGroup.isNotEmpty()) msg.contactOrGroup else "-"
            val text = msg.text.replace("|", "\\|").replace("\n", " ").take(500)
            val appName = msg.appName.replace("|", "\\|")
            sb.appendLine("| $time | $source | $appName | $dir | $contact | $text |")
        }

        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("> **提示**: 将此文件提交给 AI 助手，让它帮你总结今天做了什么、和谁沟通了什么、花了多少时间。")

        return sb.toString()
    }
}
