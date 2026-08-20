package com.daytrace.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.daytrace.data.MessageEntry
import com.daytrace.export.TimelineExporter
import com.daytrace.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Webhook 推送器 —— 时间线摘要模式
 *
 * 新模式：
 * - 通知监听不再逐条推送 Webhook（只存 DB）
 * - 提供 pushSummary() 方法，按天推送摘要
 * - 保留 pushMessage() 兼容（但默认不调用）
 *
 * Chanify webhook 格式：
 * POST https://<chanify-server>/v1/sender/<token>
 * Content-Type: text/plain
 * Body: 消息文本（纯文本）
 */
object WebhookSender {

    private const val TAG = "DayTrace/Webhook"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { encodeDefaults = true }

    /**
     * 推送当天时间线摘要
     *
     * 一次性推送完整内容，不分段。
     * 标题按日期+时间段命名（如 "DayTrace 2026-08-20 时间线_09-30-00"）。
     *
     * @param dateMillis 该日期的任意时间点
     */
    fun pushDaySummary(context: Context, dateMillis: Long = System.currentTimeMillis()) {
        if (!Prefs.isWebhookEnabled(context)) return
        val url = Prefs.getWebhookUrl(context)
        if (url.isBlank()) return

        scope.launch {
            try {
                val markdown = TimelineExporter.getDayMarkdown(context, dateMillis)
                if (markdown.isNullOrBlank()) {
                    Log.i(TAG, "No data for day summary, skipping")
                    return@launch
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(dateMillis))
                val timeStr = SimpleDateFormat("HH-mm-ss", Locale.CHINA).format(Date())
                val title = "DayTrace_${dateStr}_$timeStr"

                pushText(url, Prefs.getWebhookToken(context), title, markdown)
                Log.i(TAG, "Day summary pushed for $dateStr (${markdown.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "pushDaySummary failed: ${e.message}")
            }
        }
    }

    /**
     * 推送测试消息
     */
    fun pushTest(context: Context, onResult: (Boolean, String) -> Unit) {
        val url = Prefs.getWebhookUrl(context)
        if (url.isBlank()) {
            mainHandler.post { onResult(false, "Webhook URL 为空") }
            return
        }

        scope.launch {
            try {
                pushText(url, Prefs.getWebhookToken(context), "DayTrace 测试", "DayTrace Webhook 测试消息 ✓")
                mainHandler.post { onResult(true, "推送成功") }
            } catch (e: Exception) {
                Log.e(TAG, "test push failed: ${e.message}", e)
                mainHandler.post { onResult(false, "推送失败: ${e.message}") }
            }
        }
    }

    /**
     * 通过 Webhook 推送文本（供 DiffUploader 调用，从 Prefs 读 URL/Token）
     */
    fun pushText(context: Context, title: String, body: String) {
        val url = Prefs.getWebhookUrl(context)
        val token = Prefs.getWebhookToken(context)
        if (url.isBlank()) {
            Log.w(TAG, "pushText: webhook URL is blank")
            return
        }
        pushText(url, token, title, body)
    }

    /**
     * 推送单条消息（保留兼容，但新模式下不主动调用）
     */
    fun pushMessage(context: Context, entry: MessageEntry) {
        // 时间线模式下，不逐条推送
        // 如需逐条推送，在设置中开启
        if (!Prefs.isWebhookPerMessageEnabled(context)) return
        if (!Prefs.isWebhookEnabled(context)) return
        val url = Prefs.getWebhookUrl(context)
        if (url.isBlank()) return

        scope.launch {
            try {
                val direction = if (entry.direction == MessageEntry.DIR_OUTGOING) "↑" else "↓"
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(entry.timestamp))
                val text = buildString {
                    if (entry.contactOrGroup.isNotEmpty()) {
                        append("[${entry.contactOrGroup}] ")
                    }
                    append(entry.text)
                    append("\n$direction $timeStr")
                }
                pushText(url, Prefs.getWebhookToken(context), entry.appName, text)
            } catch (e: Exception) {
                Log.e(TAG, "pushMessage failed: ${e.message}")
            }
        }
    }

    /**
     * 通过 Webhook 推送文本（内部使用，也被 DiffUploader 调用）
     */
    internal fun pushText(url: String, token: String, title: String, body: String) {
        val isChanify = url.contains("/v1/sender/") || token.isNotBlank()

        if (isChanify) {
            val finalUrl = if (token.isNotBlank() && !url.contains(token)) {
                "${url.trimEnd('/')}/$token"
            } else {
                url
            }
            val payload = if (title.isNotEmpty()) "$title\n\n$body" else body
            doHttpPost(finalUrl, payload, "text/plain; charset=utf-8")
        } else {
            val jsonBody = buildJsonObject {
                put("title", title)
                put("text", body)
            }.toString()
            doHttpPost(url, jsonBody, "application/json; charset=utf-8")
        }
    }

    private fun doHttpPost(url: String, body: String, contentType: String) {
        val mediaType = contentType.toMediaType()
        val requestBody = body.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", contentType)
            .addHeader("User-Agent", "DayTrace/0.2")
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.w(TAG, "webhook responded ${response.code}: ${response.message} body=$respBody")
                throw RuntimeException("HTTP ${response.code}: ${response.message}")
            } else {
                Log.d(TAG, "webhook push ok: $respBody")
            }
        }
    }
}
