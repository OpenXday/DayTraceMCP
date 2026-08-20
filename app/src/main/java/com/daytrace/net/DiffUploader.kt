package com.daytrace.net

import android.content.Context
import android.util.Log
import com.daytrace.export.TimelineExporter
import com.daytrace.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Skill 执行引擎
 *
 * 根据 SkillFile（Markdown 格式定义的多步骤 workflow）执行上传流程。
 *
 * 工作流程：
 * 1. 加载选中的 SkillFile（通过 SkillManager）
 * 2. 读取当天 Markdown 文件，计算差分内容
 * 3. 按 step 顺序执行：
 *    a. 检查 condition，满足才执行
 *    b. 用占位符替换 url / headers / body 模板
 *    c. 发送 HTTP 请求
 *    d. 解析响应，提取 extract 定义的变量
 *    e. 提取的变量可在后续 step 中引用
 * 4. 更新字节偏移
 *
 * 占位符：
 * - {{credential_id}} — 用户填入的凭证值
 * - {{title}} {{content}} {{date}} {{time}} {{uploadType}} — 系统变量
 * - {{noteId}} 等 — 前一步 Extract 出来的变量
 */
object DiffUploader {

    private const val TAG = "DayTrace/SkillEngine"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    /**
     * 通过选定的 Skill 上传当天差分内容
     */
    suspend fun uploadDayDiff(
        context: Context,
        dateMillis: Long = System.currentTimeMillis(),
        forceFull: Boolean = false
    ): UploadResult = withContext(Dispatchers.IO) {
        if (!Prefs.isUploadEnabled(context)) {
            return@withContext UploadResult(false, "上传未启用", 0, 0)
        }

        val skillId = Prefs.getUploadSkillId(context)
        val skill = SkillManager.getSkill(context, skillId) ?: SkillManager.getDefaultSkill(context)

        // 获取凭证
        val credentials = Prefs.getSkillCredentials(context, skill.id)
        for (cred in skill.credentials) {
            val value = credentials[cred.id] ?: ""
            if (value.isBlank() && cred.defaultValue.isEmpty()) {
                return@withContext UploadResult(false, "${cred.label} 未填写", 0, 0)
            }
        }

        try {
            val dateStr = dateFormat.format(Date(dateMillis))
            val timeStr = timeFormat.format(Date(System.currentTimeMillis()))

            // 读取当天 Markdown
            val markdown = TimelineExporter.getDayMarkdown(context, dateMillis)
            if (markdown.isNullOrBlank()) {
                return@withContext UploadResult(false, "当天无数据", 0, 0)
            }

            val file = getTimelineFile(context, dateMillis)
            val totalSize = file.length()

            // 获取偏移
            val offsetKey = "skill_offset_${skill.id}_$dateStr"
            val prevOffset = if (forceFull) 0L else Prefs.getLong(context, offsetKey, 0L)

            // 计算差分内容
            val diffContent = if (prevOffset > 0 && prevOffset < totalSize) {
                file.inputStream().use { input ->
                    input.skip(prevOffset)
                    input.readBytes().toString(Charsets.UTF_8)
                }
            } else if (forceFull || prevOffset == 0L) {
                markdown
            } else {
                return@withContext UploadResult(true, "无新增内容", prevOffset, totalSize)
            }

            if (diffContent.isBlank()) {
                return@withContext UploadResult(true, "无新增内容", prevOffset, totalSize)
            }

            val isDiff = prevOffset > 0 && !forceFull
            val title = "DayTrace ${dateStr}_$timeStr"

            // 组装系统占位符
            val basePlaceholders = mutableMapOf(
                "title" to title,
                "content" to diffContent,
                "date" to dateStr,
                "time" to timeStr,
                "uploadType" to if (isDiff) "diff" else "full"
            )
            // 加入凭证
            for (cred in skill.credentials) {
                basePlaceholders[cred.id] = credentials[cred.id] ?: cred.defaultValue
            }
            // 加载已保存的 extract 变量（如 noteId）
            val extractKey = "skill_extract_${skill.id}_$dateStr"
            val savedExtracts = Prefs.getString(context, extractKey, "")
            if (savedExtracts.isNotEmpty()) {
                // 格式: key1=val1;key2=val2
                savedExtracts.split(";").forEach { pair ->
                    val idx = pair.indexOf("=")
                    if (idx > 0) {
                        basePlaceholders[pair.substring(0, idx)] = pair.substring(idx + 1)
                    }
                }
            }

            // 执行 steps
            val runtimeVars = mutableMapOf<String, String>()
            runtimeVars.putAll(basePlaceholders)

            var lastSuccess = false
            var lastError: String? = null

            for (step in skill.steps) {
                // 检查 condition
                if (step.condition.isNotEmpty()) {
                    val resolvedCondition = resolveTemplate(step.condition, runtimeVars)
                    if (!evaluateCondition(resolvedCondition)) {
                        Log.d(TAG, "Step ${step.id} skipped (condition: $resolvedCondition)")
                        continue
                    }
                }

                // 组装 URL
                val resolvedUrl = resolveTemplate(step.url, runtimeVars)
                val fullUrl = if (resolvedUrl.startsWith("http")) resolvedUrl
                    else if (step.baseUrl.isNotEmpty()) "${step.baseUrl.trimEnd('/')}$resolvedUrl"
                    else resolvedUrl

                Log.i(TAG, "Executing step ${step.id} (${step.name}): ${step.method} $fullUrl")

                // 执行请求
                val result = executeStep(step, fullUrl, runtimeVars)

                if (result.success) {
                    // 提取变量
                    for ((varName, jsonPath) in step.extract) {
                        val extracted = result.responseBody?.let { extractJsonField(it, jsonPath) }
                        if (extracted != null) {
                            runtimeVars[varName] = extracted
                            Log.d(TAG, "Extracted $varName = $extracted")
                        }
                    }
                    lastSuccess = true
                } else {
                    lastSuccess = false
                    lastError = result.error
                    Log.w(TAG, "Step ${step.id} failed: ${result.error}")
                    break
                }
            }

            if (lastSuccess) {
                // 更新 offset
                Prefs.putLong(context, offsetKey, totalSize)
                Prefs.putLong(context, "skill_last_upload_time", System.currentTimeMillis())

                // 保存 extract 变量（如 noteId）—— 从 runtimeVars 中提取所有 step 的 extract 变量
                val extractVarNames = skill.steps.flatMap { it.extract.keys }.toSet()
                val extractVars = extractVarNames.mapNotNull { varName ->
                    runtimeVars[varName]?.let { "$varName=$it" }
                }.joinToString(";")
                if (extractVars.isNotEmpty()) {
                    Prefs.putString(context, extractKey, extractVars)
                }

                Log.i(TAG, "Upload success via ${skill.name}: $title, total=${totalSize}B")
                UploadResult(true, "${skill.name} 上传成功: $title", totalSize, totalSize)
            } else {
                UploadResult(false, "${skill.name} 上传失败: ${lastError ?: "未知错误"}", prevOffset, totalSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}", e)
            UploadResult(false, "上传异常: ${e.message}", 0, 0)
        }
    }

    /** 执行单个 step 的 HTTP 请求 */
    private fun executeStep(
        step: SkillStep,
        url: String,
        placeholders: Map<String, String>
    ): StepResult {
        val headerBuilder = Request.Builder().url(url)

        // 组装 headers
        for ((key, value) in step.headers) {
            val resolvedValue = resolveTemplate(value, placeholders)
            if (resolvedValue.isBlank() || resolvedValue == "Bearer ") continue
            headerBuilder.addHeader(key, resolvedValue)
        }

        // 组装 body
        if (step.formFields != null) {
            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            for ((key, value) in step.formFields) {
                val resolvedValue = resolveTemplate(value, placeholders)
                if (key == "file") {
                    val mediaType = step.fileUpload?.mimeType?.let { "$it; charset=utf-8".toMediaType() }
                        ?: "text/markdown; charset=utf-8".toMediaType()
                    val fileName = resolveTemplate(step.fileUpload?.fileName ?: "{{title}}.md", placeholders)
                    multipartBuilder.addFormDataPart(key, fileName, resolvedValue.toRequestBody(mediaType))
                } else {
                    multipartBuilder.addFormDataPart(key, resolvedValue)
                }
            }
            headerBuilder.post(multipartBuilder.build())
        } else if (step.body != null) {
            val body = resolveTemplate(step.body, placeholders)
            val mediaType = if (step.contentType.contains("text/plain")) {
                "text/plain; charset=utf-8".toMediaType()
            } else {
                "${step.contentType}; charset=utf-8".toMediaType()
            }
            headerBuilder.post(body.toRequestBody(mediaType))
        } else {
            headerBuilder.method(step.method, null)
        }

        headerBuilder.addHeader("User-Agent", "DayTrace/0.3")

        return try {
            client.newCall(headerBuilder.build()).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return StepResult(false, null, "HTTP ${response.code}: ${response.message}")
                }

                // 检查 successPath
                if (step.successPath != null) {
                    val successVal = extractJsonField(respBody, step.successPath)
                    if (successVal != null && successVal != "true") {
                        return StepResult(false, null, "API returned success=false: $respBody")
                    }
                }

                StepResult(true, respBody, null)
            }
        } catch (e: Exception) {
            StepResult(false, null, e.message ?: "网络异常")
        }
    }

    /** 模板占位符替换 */
    private fun resolveTemplate(template: String, placeholders: Map<String, String>): String {
        var result = template
        for ((key, value) in placeholders) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }

    /** 条件表达式求值（简单实现） */
    private fun evaluateCondition(condition: String): Boolean {
        val cond = condition.trim()
        if (cond.isEmpty() || cond == "true") return true
        if (cond == "false") return false
        // {{var}} != '' 或 {{var}} != ""
        val notEmptyRegex = Regex(".*!=(.+)")
        val match = notEmptyRegex.matchEntire(cond) ?: return cond.toBoolean()
        val rightPart = match.groupValues[1].trim().trim('\'').trim('"')
        // 提取左边的变量值（已替换）
        val leftPart = cond.substringBefore("!=").trim().trim('\'').trim('"')
        return leftPart != rightPart
    }

    /** 从 JSON 响应中提取嵌套字段 */
    private fun extractJsonField(jsonStr: String, path: String): String? {
        return try {
            val jsonObj = json.parseToJsonElement(jsonStr).jsonObject
            val parts = path.split(".")
            var current: Any? = jsonObj
            for (part in parts) {
                current = when (current) {
                    is JsonObject -> current[part]
                    else -> return null
                }
            }
            when (current) {
                is kotlinx.serialization.json.JsonPrimitive -> current.contentOrNull
                else -> current?.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract field '$path': ${e.message}")
            null
        }
    }

    /** 测试 Skill 连接 */
    suspend fun testSkill(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val skillId = Prefs.getUploadSkillId(context)
        val skill = SkillManager.getSkill(context, skillId) ?: SkillManager.getDefaultSkill(context)
        val credentials = Prefs.getSkillCredentials(context, skill.id)

        for (cred in skill.credentials) {
            val value = credentials[cred.id] ?: ""
            if (value.isBlank() && cred.defaultValue.isEmpty()) {
                return@withContext false to "${cred.label} 未填写"
            }
        }

        val placeholders = mutableMapOf(
            "title" to "DayTrace 连接测试",
            "content" to "这是一个测试消息，验证 ${skill.name} 连接是否正常。",
            "date" to dateFormat.format(Date()),
            "time" to timeFormat.format(Date()),
            "uploadType" to "test"
        )
        for (cred in skill.credentials) {
            placeholders[cred.id] = credentials[cred.id] ?: cred.defaultValue
        }

        // 执行第一个 step
        val firstStep = skill.steps.firstOrNull() ?: return@withContext false to "Skill 无步骤"
        val resolvedUrl = resolveTemplate(firstStep.url, placeholders)
        val fullUrl = if (resolvedUrl.startsWith("http")) resolvedUrl
            else if (firstStep.baseUrl.isNotEmpty()) "${firstStep.baseUrl.trimEnd('/')}$resolvedUrl"
            else resolvedUrl

        return@withContext try {
            val result = executeStep(firstStep, fullUrl, placeholders)
            if (result.success) true to "${skill.name} 连接测试成功" else false to "${skill.name} 连接失败: ${result.error}"
        } catch (e: Exception) {
            false to "${skill.name} 连接异常: ${e.message}"
        }
    }

    private fun getTimelineFile(context: Context, dateMillis: Long): File {
        val dateStr = dateFormat.format(Date(dateMillis))
        val dir = File(context.getExternalFilesDir(null), "timeline")
        return File(dir, "timeline-$dateStr.md")
    }

    fun getUploadProgress(context: Context, dateMillis: Long): UploadProgress {
        val dateStr = dateFormat.format(Date(dateMillis))
        val skillId = Prefs.getUploadSkillId(context)
        val file = getTimelineFile(context, dateMillis)
        val totalSize = if (file.exists()) file.length() else 0L
        val uploadedOffset = Prefs.getLong(context, "skill_offset_${skillId}_$dateStr", 0L)
        val lastUploadTime = Prefs.getLong(context, "skill_last_upload_time", 0L)

        return UploadProgress(dateStr, totalSize, uploadedOffset, lastUploadTime)
    }

    fun resetDayOffset(context: Context, dateMillis: Long) {
        val dateStr = dateFormat.format(Date(dateMillis))
        val skillId = Prefs.getUploadSkillId(context)
        Prefs.putLong(context, "skill_offset_${skillId}_$dateStr", 0L)
        Prefs.putString(context, "skill_extract_${skillId}_$dateStr", "")
        Log.i(TAG, "Reset offset for $dateStr (skill=$skillId)")
    }

    // === 辅助扩展 ===

    /** 从 runtimeVars 中提取 step 定义的 extract 变量 */
    private fun SkillStep.extractVars(runtimeVars: Map<String, String>): String {
        return extract.keys.mapNotNull { varName ->
            runtimeVars[varName]?.let { "$varName=$it" }
        }.joinToString(";")
    }

    // === 数据类 ===

    data class StepResult(
        val success: Boolean,
        val responseBody: String?,
        val error: String?
    )

    data class UploadResult(
        val success: Boolean,
        val message: String,
        val uploadedBytes: Long,
        val totalBytes: Long
    )

    data class UploadProgress(
        val dateStr: String,
        val totalSize: Long,
        val uploadedOffset: Long,
        val lastUploadTime: Long
    ) {
        val isComplete: Boolean get() = totalSize > 0 && uploadedOffset >= totalSize
        val remainingBytes: Long get() = (totalSize - uploadedOffset).coerceAtLeast(0)
        val progressPercent: Int get() = if (totalSize > 0) ((uploadedOffset * 100) / totalSize).toInt() else 0
    }
}
