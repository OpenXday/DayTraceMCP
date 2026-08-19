package com.daytrace.server

import android.content.Context
import android.util.Log
import com.daytrace.data.MessageEntry
import com.daytrace.export.TimelineExporter
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP (Model Context Protocol) Server
 *
 * 提供一个嵌入式 HTTP JSON-RPC 服务器，让 Claude Desktop / 其他 AI 工具
 * 可以通过 HTTP 接口查询 DayTrace 中采集的消息。
 *
 * 端点：
 *   POST /mcp  (JSON-RPC 2.0)
 *   GET  /       (健康检查)
 *   GET  /mcp/tools (工具列表)
 */
class McpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DayTrace/MCP"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dao by lazy {
        (context.applicationContext as com.daytrace.DayTraceApp).database.messageDao()
    }

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "MCP Server started on port ${getListeningPort()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            stop()
            Log.i(TAG, "MCP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MCP server: ${e.message}")
        }
    }

    fun getPort(): Int = getListeningPort()

    // === NanoHTTPD HTTP handler ===

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // CORS preflight
        if (method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(resp)
            return resp
        }

        // Health check
        if (uri == "/" && method == Method.GET) {
            val health = buildJsonObject {
                put("status", "ok")
                put("server", "DayTrace MCP")
                put("version", "0.1.0")
                put("port", getListeningPort())
            }
            return jsonResponse(health.toString())
        }

        // MCP JSON-RPC endpoint
        if (uri == "/mcp" && method == Method.POST) {
            return handleRpc(session)
        }

        // SSE/info endpoint
        if (uri == "/mcp/sse" && method == Method.GET) {
            return handleSseInfo()
        }

        // OpenAPI-style discovery
        if (uri == "/mcp/tools" && method == Method.GET) {
            val tools = listTools()
            return jsonResponse(tools.toString())
        }

        val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $uri")
        addCorsHeaders(resp)
        return resp
    }

    private fun jsonResponse(body: String, status: Response.Status = Response.Status.OK): Response {
        val resp = newFixedLengthResponse(status, "application/json", body)
        addCorsHeaders(resp)
        return resp
    }

    private fun addCorsHeaders(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

    private fun handleSseInfo(): Response {
        val info = buildJsonObject {
            put("endpoint", "POST /mcp")
            put("methods", "tools/list, tools/call, resources/list, resources/read")
            put("note", "Use HTTP POST /mcp for JSON-RPC 2.0")
            put("discovery", "GET /mcp/tools for tool list")
        }
        return jsonResponse(info.toString())
    }

    private fun handleRpc(session: IHTTPSession): Response {
        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            return errorResponse(-32700, "Parse error: ${e.message}", null)
        }

        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return errorResponse(-32700, "Parse error: ${e.message}", null)
        }

        val id: JsonElement? = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
        val params = request["params"]?.jsonObject

        val result = try {
            when (method) {
                "initialize" -> initializeResult()
                "tools/list" -> listTools()
                "tools/call" -> callTool(params)
                "resources/list" -> listResources()
                "resources/read" -> readResource(params)
                "ping" -> buildJsonObject { put("pong", true) }
                else -> return errorResponse(-32601, "Method not found: $method", id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RPC error: ${e.message}", e)
            return errorResponse(-32603, "Internal error: ${e.message}", id)
        }

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonPrimitive(0))
            put("result", result)
        }
        return jsonResponse(response.toString())
    }

    private fun errorResponse(code: Int, message: String, id: JsonElement?): Response {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonPrimitive(0))
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
        return jsonResponse(response.toString())
    }

    // === MCP Tool definitions ===

    private fun listTools(): JsonObject {
        val tools = buildJsonArray {
            add(toolJson(
                name = "get_recent_messages",
                description = "Get recent captured messages from all IM apps",
                properties = buildJsonObject {
                    put("limit", propObj("integer", "Max messages to return (default 50, max 500)", 50))
                    put("direction", propObj("string", "Filter by direction: 'outgoing', 'incoming', or 'all'", "all"))
                    put("appPackage", propObj("string", "Filter by app package name (e.g. com.tencent.mm)", ""))
                }
            ))
            add(toolJson(
                name = "search_messages",
                description = "Search messages by keyword",
                properties = buildJsonObject {
                    put("keyword", propObj("string", "Search keyword", ""))
                    put("limit", propObj("integer", "Max results (default 50, max 500)", 50))
                },
                required = listOf("keyword")
            ))
            add(toolJson(
                name = "get_message_stats",
                description = "Get message statistics (counts by app, by direction)"
            ))
            add(toolJson(
                name = "get_messages_by_date",
                description = "Get messages within a date range",
                properties = buildJsonObject {
                    put("startTime", propObj("integer", "Start timestamp in milliseconds (epoch)", 0))
                    put("endTime", propObj("integer", "End timestamp in milliseconds (epoch)", 0))
                }
            ))
            add(toolJson(
                name = "get_app_list",
                description = "Get list of all apps that have captured messages"
            ))
            add(toolJson(
                name = "get_conversation",
                description = "Get full conversation context for a specific app and optionally a contact/group. Returns messages in chronological order.",
                properties = buildJsonObject {
                    put("appPackage", propObj("string", "App package name (e.g. com.tencent.mm, com.ss.android.lark)", ""))
                    put("contactOrGroup", propObj("string", "Contact or group name (empty string for all contacts in this app)", ""))
                    put("limit", propObj("integer", "Max messages (default 200, max 1000)", 200))
                },
                required = listOf("appPackage")
            ))
            add(toolJson(
                name = "list_conversations",
                description = "List all conversations (app + contact/group pairs) that have captured messages"
            ))
            add(toolJson(
                name = "get_timeline",
                description = "Get today's (or a specific date's) timeline as Markdown. Returns a chronological summary of phone activity.",
                properties = buildJsonObject {
                    put("date", propObj("string", "Date in YYYY-MM-DD format (empty = today)", ""))
                }
            ))
            add(toolJson(
                name = "export_timeline",
                description = "Export a day's timeline as a Markdown file on the device. Returns the file path.",
                properties = buildJsonObject {
                    put("date", propObj("string", "Date in YYYY-MM-DD format (empty = today)", ""))
                }
            ))
            add(toolJson(
                name = "push_webhook_summary",
                description = "Push the day's timeline summary to the configured Webhook (e.g. Chanify). Returns success/failure.",
                properties = buildJsonObject {
                    put("date", propObj("string", "Date in YYYY-MM-DD format (empty = today)", ""))
                }
            ))
            add(toolJson(
                name = "clear_all_messages",
                description = "Delete all captured messages. Use with caution!"
            ))
        }

        return buildJsonObject {
            put("tools", tools)
        }
    }

    private fun toolJson(
        name: String,
        description: String,
        properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList()
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", properties)
            val reqArray = buildJsonArray {
                required.forEach { s -> add(JsonPrimitive(s)) }
            }
            put("required", reqArray)
        })
    }

    private fun propObj(type: String, description: String, default: Any?): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(description))
        if (default != null) {
            when (default) {
                is String -> put("default", JsonPrimitive(default))
                is Number -> put("default", JsonPrimitive(default))
                is Boolean -> put("default", JsonPrimitive(default))
            }
        }
    }

    /** 解析日期字符串（YYYY-MM-DD），空则返回今天 */
    private fun parseDateOrToday(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val date = sdf.parse(dateStr)
            date?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // === Tool execution ===

    private fun callTool(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val args = params?.get("arguments")?.jsonObject ?: buildJsonObject {}

        val resultText = when (name) {
            "get_recent_messages" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 500) ?: 50
                val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "all"
                val appPackage = args["appPackage"]?.jsonPrimitive?.contentOrNull ?: ""

                val messages = kotlinx.coroutines.runBlocking {
                    when {
                        appPackage.isNotEmpty() -> dao.messagesByAppOnce(appPackage, limit)
                        direction == "outgoing" -> dao.messagesByDirectionOnce(MessageEntry.DIR_OUTGOING, limit)
                        direction == "incoming" -> dao.messagesByDirectionOnce(MessageEntry.DIR_INCOMING, limit)
                        else -> dao.recentMessagesOnce(limit)
                    }
                }
                formatMessages(messages)
            }

            "search_messages" -> {
                val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull ?: ""
                val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 500) ?: 50
                if (keyword.isBlank()) "Error: keyword is required"
                else {
                    val messages = kotlinx.coroutines.runBlocking {
                        dao.searchMessages("%$keyword%", limit)
                    }
                    "Found ${messages.size} messages:\n" + formatMessages(messages)
                }
            }

            "get_message_stats" -> {
                val count = kotlinx.coroutines.runBlocking { dao.count() }
                val apps = kotlinx.coroutines.runBlocking { dao.appCounts() }
                val dirOut = kotlinx.coroutines.runBlocking { dao.countByDirection(MessageEntry.DIR_OUTGOING) }
                val dirIn = kotlinx.coroutines.runBlocking { dao.countByDirection(MessageEntry.DIR_INCOMING) }

                buildString {
                    appendLine("=== DayTrace Message Statistics ===")
                    appendLine("Total messages: $count")
                    appendLine("Outgoing (sent by you): $dirOut")
                    appendLine("Incoming (received): $dirIn")
                    appendLine()
                    appendLine("By app:")
                    apps.forEach { (pkg, appName, cnt) ->
                        appendLine("  $appName ($pkg): $cnt")
                    }
                }
            }

            "get_messages_by_date" -> {
                val startTime = args["startTime"]?.jsonPrimitive?.longOrNull ?: 0L
                val endTime = args["endTime"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE
                val messages = kotlinx.coroutines.runBlocking {
                    dao.messagesByDateRange(startTime, endTime, 500)
                }
                formatMessages(messages)
            }

            "get_app_list" -> {
                val apps = kotlinx.coroutines.runBlocking { dao.appCounts() }
                if (apps.isEmpty()) "No messages captured yet."
                else {
                    buildString {
                        appendLine("=== Captured Apps ===")
                        apps.forEach { (pkg, appName, cnt) ->
                            appendLine("$appName ($pkg): $cnt messages")
                        }
                    }
                }
            }

            "get_conversation" -> {
                val appPackage = args["appPackage"]?.jsonPrimitive?.contentOrNull ?: ""
                val contactOrGroup = args["contactOrGroup"]?.jsonPrimitive?.contentOrNull ?: ""
                val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: 200
                if (appPackage.isBlank()) "Error: appPackage is required"
                else {
                    val messages = kotlinx.coroutines.runBlocking {
                        if (contactOrGroup.isNotEmpty()) {
                            dao.conversationByContact(appPackage, contactOrGroup, limit)
                        } else {
                            dao.conversationByApp(appPackage, limit)
                        }
                    }
                    buildString {
                        appendLine("=== Conversation: $appPackage / ${contactOrGroup.ifEmpty { "(all)"}} ===")
                        appendLine("Total: ${messages.size} messages")
                        appendLine()
                        append(formatMessages(messages))
                    }
                }
            }

            "list_conversations" -> {
                val convs = kotlinx.coroutines.runBlocking { dao.conversationKeys() }
                if (convs.isEmpty()) "No conversations captured yet."
                else {
                    buildString {
                        appendLine("=== Conversations ===")
                        convs.forEach { ck ->
                            val label = if (ck.contactOrGroup.isEmpty()) {
                                "${ck.appName} (${ck.appPackage})"
                            } else {
                                "${ck.appName} · ${ck.contactOrGroup} (${ck.appPackage})"
                            }
                            appendLine("$label: ${ck.count} messages")
                        }
                    }
                }
            }

            "get_timeline" -> {
                val dateStr = args["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val dateMillis = parseDateOrToday(dateStr)
                val markdown = kotlinx.coroutines.runBlocking {
                    TimelineExporter.getDayMarkdown(context, dateMillis)
                }
                markdown ?: "No data found for $dateStr"
            }

            "export_timeline" -> {
                val dateStr = args["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val dateMillis = parseDateOrToday(dateStr)
                val path = kotlinx.coroutines.runBlocking {
                    TimelineExporter.exportDay(context, dateMillis)
                }
                path ?: "Export failed or no data for $dateStr"
            }

            "push_webhook_summary" -> {
                val dateStr = args["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val dateMillis = parseDateOrToday(dateStr)
                kotlinx.coroutines.runBlocking {
                    com.daytrace.net.WebhookSender.pushDaySummary(context, dateMillis)
                }
                "Webhook summary pushed for ${if (dateStr.isEmpty()) "today" else dateStr}"
            }

            "clear_all_messages" -> {
                kotlinx.coroutines.runBlocking { dao.clearAll() }
                "All messages have been deleted."
            }

            else -> "Unknown tool: $name"
        }

        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", JsonPrimitive(resultText))
                })
            })
        }
    }

    private fun formatMessages(messages: List<MessageEntry>): String {
        if (messages.isEmpty()) return "No messages found."
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return buildString {
            messages.forEach { msg ->
                val dir = if (msg.direction == MessageEntry.DIR_OUTGOING) "↑ OUT" else "↓ IN"
                append("[${df.format(Date(msg.timestamp))}] $dir ${msg.appName}")
                if (msg.contactOrGroup.isNotEmpty()) append(" / ${msg.contactOrGroup}")
                append("\n  ${msg.text}")
                append("\n  (via ${msg.source})\n\n")
            }
        }
    }

    // === Resources ===

    private fun listResources(): JsonObject {
        val resources = buildJsonArray {
            add(buildJsonObject {
                put("uri", "DayTrace://messages/recent")
                put("name", "Recent messages")
                put("mimeType", "application/json")
            })
            add(buildJsonObject {
                put("uri", "DayTrace://stats")
                put("name", "Message statistics")
                put("mimeType", "application/json")
            })
        }
        return buildJsonObject {
            put("resources", resources)
        }
    }

    private fun readResource(params: JsonObject?): JsonObject {
        val uri = params?.get("uri")?.jsonPrimitive?.contentOrNull ?: ""
        val content = when (uri) {
            "DayTrace://messages/recent" -> {
                val messages = kotlinx.coroutines.runBlocking { dao.recentMessagesOnce(100) }
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(MessageEntry.serializer()),
                    messages
                )
            }
            "DayTrace://stats" -> {
                val count = kotlinx.coroutines.runBlocking { dao.count() }
                buildJsonObject {
                    put("total_messages", count)
                }.toString()
            }
            else -> """{"error":"Unknown resource: $uri"}"""
        }

        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("uri", uri)
                    put("mimeType", "application/json")
                    put("text", JsonPrimitive(content))
                })
            })
        }
    }

    private fun initializeResult(): JsonObject {
        return buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {})
                put("resources", buildJsonObject {})
            })
            put("serverInfo", buildJsonObject {
                put("name", "DayTrace MCP Server")
                put("version", "0.1.0")
            })
        }
    }
}
