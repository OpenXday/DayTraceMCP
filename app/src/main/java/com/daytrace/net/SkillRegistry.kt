package com.daytrace.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DayTrace Skill 文件格式
 *
 * Skill 文件是 Markdown 格式（.skill.md），结构如下：
 *
 * ---
 * id: biji
 * name: 得到大脑
 * description: 通过得到大脑 API 创建笔记
 * version: 1.0
 * credentials:
 *   - id: api_key
 *     label: API Key
 *     placeholder: gk_live_xxx
 *     isSecret: true
 *   - id: client_id
 *     label: Client ID
 * ---
 *
 * ## Step 1: 创建笔记
 *
 * POST https://openapi.biji.com/open/api/v1/resource/note/create
 *
 * Headers:
 * Authorization: {{api_key}}
 * X-Client-ID: {{client_id}}
 * Content-Type: application/json
 *
 * Body:
 * {"title":"{{title}}","content":"{{content}}","note_type":"markdown"}
 *
 * Extract:
 * noteId = data.note_id
 *
 * Success: success
 *
 * ## Step 2: 追加内容
 *
 * Condition: {{noteId}} != ''
 *
 * POST https://openapi.biji.com/open/api/v1/resource/note/update
 *
 * Headers:
 * Authorization: {{api_key}}
 * X-Client-ID: {{client_id}}
 * Content-Type: application/json
 *
 * Body:
 * {"note_id":"{{noteId}}","title":"{{title}}","content":"{{content}}"}
 *
 * 占位符：
 * - {{credential_id}} — 用户填入的凭证值
 * - {{title}} {{content}} {{date}} {{time}} {{uploadType}} — 系统变量
 * - {{noteId}} 等 — 前一步 Extract 出来的值
 */

@Serializable
data class CredentialField(
    val id: String,
    val label: String,
    val placeholder: String = "",
    val isSecret: Boolean = false,
    val defaultValue: String = ""
)

data class SkillStep(
    val id: String,
    val name: String,
    val condition: String = "",
    val method: String = "POST",
    val url: String,
    val baseUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json",
    val formFields: Map<String, String>? = null,
    val fileUpload: FileUploadConfig? = null,
    val extract: Map<String, String> = emptyMap(),
    val successPath: String? = null
)

data class FileUploadConfig(
    val fieldName: String = "file",
    val fileName: String = "{{title}}.md",
    val mimeType: String = "text/markdown"
)

data class SkillFile(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0",
    val author: String = "",
    val credentials: List<CredentialField> = emptyList(),
    val steps: List<SkillStep>,
    val isImported: Boolean = false
)

/**
 * Markdown Skill 解析器
 *
 * 解析 .skill.md 文件：
 * 1. YAML frontmatter → 元数据 + credentials
 * 2. "## Step N: xxx" → 每个 step
 * 3. step 内的 "Method URL" / "Headers:" / "Body:" / "Extract:" / "Condition:" / "Success:"
 */
object SkillParser {

    private const val TAG = "DayTrace/SkillParser"

    /**
     * 解析 Markdown Skill 文件内容
     */
    fun parse(markdown: String, isImported: Boolean = false): Result<SkillFile> {
        return try {
            // 1. 提取 YAML frontmatter
            val (frontmatter, body) = splitFrontmatter(markdown)

            val id = parseYamlValue(frontmatter, "id")
                ?: return Result.failure(IllegalArgumentException("Skill 缺少 id"))
            val name = parseYamlValue(frontmatter, "name") ?: id
            val description = parseYamlValue(frontmatter, "description") ?: ""
            val version = parseYamlValue(frontmatter, "version") ?: "1.0"
            val author = parseYamlValue(frontmatter, "author") ?: ""

            // 解析 credentials
            val credentials = parseCredentials(frontmatter)

            // 2. 解析 steps
            val steps = parseSteps(body)

            if (steps.isEmpty()) {
                return Result.failure(IllegalArgumentException("Skill 缺少 steps"))
            }

            Result.success(SkillFile(
                id = id,
                name = name,
                description = description,
                version = version,
                author = author,
                credentials = credentials,
                steps = steps,
                isImported = isImported
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 分离 YAML frontmatter 和正文 */
    private fun splitFrontmatter(markdown: String): Pair<String, String> {
        val trimmed = markdown.trimStart()
        if (!trimmed.startsWith("---")) return "" to markdown

        val endIdx = trimmed.indexOf("---", 3)
        if (endIdx < 0) return "" to markdown

        val frontmatter = trimmed.substring(3, endIdx)
        val body = trimmed.substring(endIdx + 3)
        return frontmatter to body
    }

    /** 从 YAML 字符串中提取简单 key: value */
    private fun parseYamlValue(yaml: String, key: String): String? {
        val regex = Regex("^\\s*$key\\s*:\\s*(.+)$", RegexOption.MULTILINE)
        return regex.find(yaml)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
    }

    /** 解析 credentials 列表（YAML list of maps） */
    private fun parseCredentials(yaml: String): List<CredentialField> {
        val result = mutableListOf<CredentialField>()
        // 匹配 credentials: 后面的 list 项
        val credSection = findYamlSection(yaml, "credentials") ?: return result

        // 按 "- " 分割为各 credential
        val items = splitYamlList(credSection)

        for (item in items) {
            val id = parseYamlValue(item, "id") ?: continue
            val label = parseYamlValue(item, "label") ?: id
            val placeholder = parseYamlValue(item, "placeholder") ?: ""
            val isSecret = parseYamlValue(item, "isSecret")?.toBoolean() ?: false
            val defaultValue = parseYamlValue(item, "defaultValue") ?: ""

            result.add(CredentialField(
                id = id, label = label, placeholder = placeholder,
                isSecret = isSecret, defaultValue = defaultValue
            ))
        }

        return result
    }

    /** 找到 YAML 中某个 key 下的 block（如 credentials: 下面缩进的内容） */
    private fun findYamlSection(yaml: String, key: String): String? {
        val regex = Regex("^\\s*$key\\s*:\\s*$", RegexOption.MULTILINE)
        val match = regex.find(yaml) ?: return null
        val startIdx = match.range.last + 1

        // 收集后续缩进行直到非缩进行
        val lines = yaml.substring(startIdx).lines()
        val sectionLines = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            if (!line.startsWith(" ") && !line.startsWith("\t")) break
            sectionLines.add(line)
        }
        return sectionLines.joinToString("\n")
    }

    /** 将 YAML list block 按 "- " 分割 */
    private fun splitYamlList(block: String): List<String> {
        val items = mutableListOf<String>()
        val lines = block.lines()
        val currentItem = mutableListOf<String>()

        for (line in lines) {
            if (line.trimStart().startsWith("- ")) {
                if (currentItem.isNotEmpty()) items.add(currentItem.joinToString("\n"))
                currentItem.clear()
                currentItem.add(line)
            } else if (currentItem.isNotEmpty()) {
                currentItem.add(line)
            }
        }
        if (currentItem.isNotEmpty()) items.add(currentItem.joinToString("\n"))
        return items
    }

    /** 解析 Markdown 正文中的 Step 章节 */
    private fun parseSteps(body: String): List<SkillStep> {
        val steps = mutableListOf<SkillStep>()

        // 按 "## Step" 分割
        val stepRegex = Regex("(?m)^##\\s+Step\\s+\\d+\\s*:?(.*)$")
        val matches = stepRegex.findAll(body).toList()

        if (matches.isEmpty()) return steps

        for (i in matches.indices) {
            val match = matches[i]
            val stepName = match.groupValues.getOrNull(1)?.trim() ?: ""
            val stepBody = if (i + 1 < matches.size) {
                body.substring(match.range.last + 1, matches[i + 1].range.first)
            } else {
                body.substring(match.range.last + 1)
            }

            val step = parseStepBody(stepName, stepBody)
            steps.add(step)
        }

        return steps
    }

    /** 解析单个 step 的正文 */
    private fun parseStepBody(name: String, body: String): SkillStep {
        var stepId = name.lowercase().replace(" ", "_").ifEmpty { "step" }
        var condition = ""
        var method = "POST"
        var url = ""
        var baseUrl = ""
        val headers = mutableMapOf<String, String>()
        var stepBody: String? = null
        var contentType = "application/json"
        val extract = mutableMapOf<String, String>()
        var successPath: String? = null

        val lines = body.lines()
        var i = 0
        var section = ""  // 当前正在解析的 section

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 检测 section 标题
            when {
                trimmed.startsWith("Condition:") -> {
                    condition = trimmed.removePrefix("Condition:").trim()
                    section = ""
                }
                trimmed.startsWith("Headers:") -> {
                    section = "headers"
                }
                trimmed.startsWith("Body:") -> {
                    section = "body"
                    stepBody = ""
                }
                trimmed.startsWith("Extract:") -> {
                    section = "extract"
                }
                trimmed.startsWith("Success:") -> {
                    successPath = trimmed.removePrefix("Success:").trim()
                    section = ""
                }
                trimmed.startsWith("FormFields:") -> {
                    section = "formFields"
                }
                trimmed.startsWith("FileUpload:") -> {
                    section = "fileUpload"
                }
                // HTTP method + URL 行（GET/POST/PUT/DELETE 开头）
                trimmed.matches(Regex("^(GET|POST|PUT|DELETE|PATCH)\\s+\\S+")) -> {
                    val parts = trimmed.split("\\s+".toRegex(), 2)
                    method = parts[0]
                    url = parts.getOrNull(1) ?: ""
                    section = ""
                }
                // baseUrl
                trimmed.startsWith("BaseUrl:") -> {
                    baseUrl = trimmed.removePrefix("BaseUrl:").trim()
                    section = ""
                }
                // contentType
                trimmed.startsWith("ContentType:") -> {
                    contentType = trimmed.removePrefix("ContentType:").trim()
                    section = ""
                }
                // section 内容
                section == "headers" && trimmed.isNotEmpty() -> {
                    val colonIdx = trimmed.indexOf(":")
                    if (colonIdx > 0) {
                        val key = trimmed.substring(0, colonIdx).trim()
                        val value = trimmed.substring(colonIdx + 1).trim()
                        headers[key] = value
                    }
                }
                section == "body" -> {
                    stepBody = (stepBody ?: "") + if (stepBody.isNullOrEmpty()) line else "\n$line"
                }
                section == "extract" && trimmed.isNotEmpty() -> {
                    val eqIdx = trimmed.indexOf("=")
                    if (eqIdx > 0) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        extract[key] = value
                    }
                }
            }
            i++
        }

        // 清理 body（去掉首尾空行）
        stepBody = stepBody?.trim()?.takeIf { it.isNotEmpty() }

        return SkillStep(
            id = stepId,
            name = name,
            condition = condition,
            method = method,
            url = url,
            baseUrl = baseUrl,
            headers = headers,
            body = stepBody,
            contentType = contentType,
            extract = extract,
            successPath = successPath
        )
    }
}

/**
 * Skill 管理器 —— 加载、存储、管理 Skill 文件
 *
 * Skill 来源：
 * 1. 内置（代码中定义的默认 Skill）
 * 2. 本地文件（用户导入 .skill.md）
 * 3. 网络 URL（App 下载并保存）
 *
 * 导入的 Skill 存储在：app filesDir/skills/ 下（.skill.md 文件）
 */
object SkillManager {

    private const val TAG = "DayTrace/SkillMgr"
    private const val SKILLS_DIR = "skills"
    private const val SKILL_FILE_EXT = ".skill.md"

    /** 内置 get笔记 Skill（Markdown 格式） */
    private val BUILTIN_BIJI_MD = """
---
id: biji
name: 得到大脑 (get笔记)
description: 通过得到大脑开放 API 创建/更新笔记
version: 1.0
author: DayTrace
credentials:
  - id: api_key
    label: API Key
    placeholder: gk_live_xxx
    isSecret: true
  - id: client_id
    label: Client ID
    placeholder: cli_xxx
---

## Step 1: 创建笔记

POST https://openapi.biji.com/open/api/v1/resource/note/create

Headers:
Authorization: {{api_key}}
X-Client-ID: {{client_id}}
Content-Type: application/json

Body:
{"title":"{{title}}","content":"{{content}}","note_type":"markdown"}

Extract:
noteId = data.note_id

Success: success

## Step 2: 追加内容

Condition: {{noteId}} != ''

POST https://openapi.biji.com/open/api/v1/resource/note/update

Headers:
Authorization: {{api_key}}
X-Client-ID: {{client_id}}
Content-Type: application/json

Body:
{"note_id":"{{noteId}}","title":"{{title}}","content":"\n\n---\n\n## {{date}} {{time}}\n\n{{content}}","note_type":"markdown","mode":"append"}

Success: success
    """.trim()

    private val BUILTIN_GENERIC_JSON_MD = """
---
id: generic_json
name: 通用 JSON 上传
description: 将时间线内容作为 JSON POST 到自定义端点
version: 1.0
credentials:
  - id: url
    label: 上传 URL
    placeholder: https://your-server.com/api/upload
  - id: token
    label: Bearer Token（可选）
    placeholder: 留空则不发送
    isSecret: true
---

## Step 1: 上传内容

POST {{url}}

Headers:
Content-Type: application/json
Authorization: Bearer {{token}}

Body:
{"fileName":"{{title}}.md","date":"{{date}}","time":"{{time}}","type":"{{uploadType}}","content":"{{content}}"}

Success: success
    """.trim()

    private val BUILTIN_WEBHOOK_MD = """
---
id: webhook
name: Webhook 推送
description: 通过 Webhook 推送时间线摘要（Chanify 等）
version: 1.0
credentials:
  - id: url
    label: Webhook URL
    placeholder: https://api.chanify.net/v1/sender/
  - id: token
    label: Token（可选）
    placeholder: CTRxxx...
    isSecret: true
---

## Step 1: 推送摘要

POST {{url}}

Headers:
Content-Type: text/plain; charset=utf-8

Body:
{{title}}

{{content}}
    """.trim()

    /** 内置 Skill Markdown 文件列表 */
    private val builtinSkillMds: List<String> = listOf(
        BUILTIN_BIJI_MD, BUILTIN_GENERIC_JSON_MD, BUILTIN_WEBHOOK_MD
    )

    /** 解析内置 Skill */
    val builtinSkills: List<SkillFile> by lazy {
        builtinSkillMds.mapNotNull { md ->
            SkillParser.parse(md).getOrNull()
        }
    }

    /** 获取所有可用的 Skill（内置 + 已导入的） */
    fun getAllSkills(context: Context): List<SkillFile> {
        val imported = loadImportedSkills(context)
        val importedIds = imported.map { it.id }.toSet()
        return imported + builtinSkills.filter { it.id !in importedIds }
    }

    /** 根据 ID 获取 Skill */
    fun getSkill(context: Context, id: String): SkillFile? {
        return getAllSkills(context).find { it.id == id }
    }

    /** 获取默认 Skill */
    fun getDefaultSkill(context: Context): SkillFile {
        return getAllSkills(context).firstOrNull() ?: builtinSkills.first()
    }

    // === 导入 Skill ===

    /**
     * 从 Markdown 字符串导入 Skill
     */
    fun importFromMarkdown(context: Context, markdown: String): Result<SkillFile> {
        return try {
            val result = SkillParser.parse(markdown, isImported = true)
            if (result.isSuccess) {
                val skill = result.getOrThrow()
                saveSkillFile(context, skill.id, markdown)
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从网络 URL 下载 Skill 文件
     */
    suspend fun importFromUrl(context: Context, url: String): Result<SkillFile> =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).get()
                    .addHeader("User-Agent", "DayTrace/0.3")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(RuntimeException("HTTP ${response.code}"))
                    }
                    val body = response.body?.string() ?: ""
                    importFromMarkdown(context, body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 从本地文件路径导入
     */
    fun importFromFile(context: Context, filePath: String): Result<SkillFile> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(java.io.FileNotFoundException(filePath))
            val content = file.readText(Charsets.UTF_8)
            importFromMarkdown(context, content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // === Skill 文件存储 ===

    private fun getSkillsDir(context: Context): File {
        val dir = File(context.filesDir, SKILLS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveSkillFile(context: Context, skillId: String, markdown: String) {
        val file = File(getSkillsDir(context), "$skillId$SKILL_FILE_EXT")
        file.writeText(markdown, Charsets.UTF_8)
    }

    private fun loadImportedSkills(context: Context): List<SkillFile> {
        val dir = getSkillsDir(context)
        return dir.listFiles { f -> f.name.endsWith(SKILL_FILE_EXT) }
            ?.mapNotNull { file ->
                try {
                    val md = file.readText(Charsets.UTF_8)
                    SkillParser.parse(md, isImported = true).getOrNull()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load skill ${file.name}: ${e.message}")
                    null
                }
            } ?: emptyList()
    }

    /** 删除导入的 Skill */
    fun deleteSkill(context: Context, skillId: String): Boolean {
        val file = File(getSkillsDir(context), "$skillId$SKILL_FILE_EXT")
        return file.delete()
    }

    /** 判断是否为导入的 Skill */
    fun isImported(context: Context, skillId: String): Boolean {
        return File(getSkillsDir(context), "$skillId$SKILL_FILE_EXT").exists()
    }

    /** 导出 Skill 为 Markdown 字符串 */
    fun exportToMarkdown(context: Context, skillId: String): String? {
        // 内置 Skill 无法直接导出原始 MD（是代码中定义的），可重新生成
        val imported = File(getSkillsDir(context), "$skillId$SKILL_FILE_EXT")
        if (imported.exists()) return imported.readText(Charsets.UTF_8)
        // 内置的从 builtinSkills 找
        return null
    }
}
