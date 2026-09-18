package com.daytrace.server

import android.content.Context
import android.util.Log
import com.daytrace.data.McpLogEntry
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP (Model Context Protocol) Server
 *
 * 提供 MCP Streamable HTTP 传输规范兼容的端点，让 Claude Desktop / 其他 AI 工具
 * 可以通过 HTTP 接口查询 DayTrace 中采集的消息。
 *
 * MCP Streamable HTTP 传输端点：
 *   POST   /mcp        (JSON-RPC 2.0 请求，支持 SSE 响应)
 *   GET    /mcp        (SSE 流 — 服务器推送事件)
 *   DELETE /mcp        (终止会话)
 *   GET    /           (健康检查)
 *   GET    /mcp/tools  (工具列表，便利端点)
 *
 * 所有 MCP 调用均通过 [logRecorder] 异步写入 mcp_logs 表，可在 App 内审计。
 */
class McpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DayTrace/MCP"
        private const val MCP_ENDPOINT = "/mcp"
        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val PROTOCOL_VERSION = "2025-06-18"
    }

    /** MCP 会话管理 */
    private val sessions = ConcurrentHashMap<String, Boolean>()

    /** 生成新的会话 ID */
    private fun newSessionId(): String = UUID.randomUUID().toString()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dao by lazy {
        (context.applicationContext as com.daytrace.DayTraceApp).database.messageDao()
    }

    /** MCP 调用日志记录器（异步写入数据库，不阻塞请求处理） */
    private val logRecorder by lazy {
        McpLogRecorder(context)
    }

    /** 相机拍照/录像管理器 */
    private val cameraManager by lazy {
        CameraCaptureManager(context.applicationContext)
    }

    /** 录音管理器 */
    private val audioManager by lazy {
        AudioCaptureManager(context.applicationContext)
    }

    /** 屏幕截图/文字提取管理器 */
    private val screenManager by lazy {
        ScreenCaptureManager(context.applicationContext)
    }

    /** 设备信息管理器 */
    private val deviceInfoManager by lazy {
        DeviceInfoManager(context.applicationContext)
    }

    /** 系统操作管理器 */
    private val actionManager by lazy {
        SystemActionManager(context.applicationContext)
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

/**
 * 提取客户端真实 IP
 *
 * 当请求通过 Cloudflare Tunnel 或反向代理到达时，NanoHTTPD 的
 * remote-addr 会是 127.0.0.1（隧道本地回环）。此时需要从代理
 * 转发的 header 中提取真实客户端 IP。
 *
 * 优先级（从高到低）：
 * 1. CF-Connecting-IP — Cloudflare 隧道直接写入的真实 IP（单个，无逗号）
 * 2. X-Real-IP — Nginx 等反向代理设置的真实 IP
 * 3. X-Forwarded-For — 标准代理 header，可能含多个 IP（第一个是原始客户端）
 * 4. remote-addr — NanoHTTPD 的直接连接 IP（无代理时为真实客户端）
 * 5. unknown — 兜底
 */
private fun getClientIp(session: IHTTPSession): String {
    // Cloudflare 隧道直接写入的客户端真实 IP
    session.headers["cf-connecting-ip"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    // X-Real-IP（Nginx 等设置）
    session.headers["x-real-ip"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    // X-Forwarded-For（可能逗号分隔，取第一个）
    session.headers["x-forwarded-for"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    // 直接连接 IP（无代理场景）
    session.headers["remote-addr"]?.takeIf { it.isNotEmpty() }?.let { return it }
    return "unknown"
}

    // === NanoHTTPD HTTP handler ===

    override fun serve(session: IHTTPSession): Response {
        val requestStartTime = System.currentTimeMillis()
        val uri = session.uri
        val method = session.method
        val clientIp = getClientIp(session)

        // CORS preflight（不记录日志）
        if (method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(resp)
            return resp
        }

        // 安全认证：如果启用了 Token，校验 Authorization header
        val authEnabled = com.daytrace.util.Prefs.isMcpAuthEnabled(context)
        var authResult = McpLogEntry.AUTH_NOT_REQUIRED
        if (authEnabled) {
            val expectedToken = com.daytrace.util.Prefs.getMcpToken(context)
            if (expectedToken.isNotEmpty()) {
                authResult = McpLogEntry.AUTH_OK
                val authHeader = session.headers["authorization"] ?: ""
                val providedToken = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    authHeader.substring(7).trim()
                } else {
                    // 也支持 query parameter ?token=xxx
                    val params = session.parameters ?: mapOf()
                    params["token"]?.firstOrNull() ?: ""
                }
                if (providedToken != expectedToken) {
                    authResult = McpLogEntry.AUTH_FAILED
                    val resp = newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED,
                        "application/json",
                        """{"error":"Unauthorized","message":"Invalid or missing Bearer token"}"""
                    )
                    resp.addHeader("WWW-Authenticate", "Bearer realm=\"DayTrace MCP\"")
                    addCorsHeaders(resp)
                    // 记录认证失败日志
                    logRecorder.log(
                        httpMethod = method.name,
                        httpPath = uri,
                        clientIp = clientIp,
                        responseCode = 401,
                        durationMs = System.currentTimeMillis() - requestStartTime,
                        rpcMethod = "",
                        sessionId = session.headers[SESSION_HEADER.lowercase()] ?: session.headers[SESSION_HEADER] ?: "",
                        authResult = authResult,
                        requestBody = "",
                        responseBody = """{"error":"Unauthorized","message":"Invalid or missing Bearer token"}""",
                        success = false,
                        errorMessage = "Authentication failed: invalid or missing Bearer token"
                    )
                    return resp
                }
            } else {
                authResult = McpLogEntry.AUTH_NOT_REQUIRED
            }
        } else {
            authResult = McpLogEntry.AUTH_NOT_REQUIRED
        }

        // Health check
        if (uri == "/" && method == Method.GET) {
            val health = buildJsonObject {
                put("status", "ok")
                put("server", "DayTrace MCP")
                put("version", "0.1.0")
                put("port", getListeningPort())
                put("authRequired", authEnabled)
            }
            val resp = jsonResponse(health.toString())
            logRecorder.log(
                httpMethod = "GET",
                httpPath = uri,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "health_check",
                authResult = authResult,
                responseBody = health.toString(),
                success = true
            )
            return resp
        }

        // MCP Streamable HTTP transport endpoint
        if (uri == MCP_ENDPOINT) {
            return handleMcpEndpoint(session, method, clientIp, authResult, requestStartTime)
        }

        // 文件下载端点：/captures/photos/<filename>, /captures/videos/<filename>, /captures/audio/<filename>, /captures/screenshots/<filename>
        if (uri.startsWith("/captures/") && method == Method.GET) {
            return handleCaptureDownload(uri, clientIp, authResult, requestStartTime)
        }

        // OpenAPI-style discovery (convenience endpoint)
        if (uri == "/mcp/tools" && method == Method.GET) {
            val tools = listTools()
            val resp = jsonResponse(tools.toString())
            logRecorder.log(
                httpMethod = "GET",
                httpPath = uri,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "tools_list_endpoint",
                authResult = authResult,
                responseBody = tools.toString(),
                success = true
            )
            return resp
        }

        // Legacy SSE info endpoint
        if (uri == "/mcp/sse" && method == Method.GET) {
            val resp = handleSseInfo()
            logRecorder.log(
                httpMethod = "GET",
                httpPath = uri,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "sse_info",
                authResult = authResult,
                success = true
            )
            return resp
        }

        // 405 for unsupported methods on known paths, 404 for unknown paths
        val resp = if (uri.startsWith("/mcp")) {
            newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed: $method $uri")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $uri")
        }
        addCorsHeaders(resp)
        val respCode = if (uri.startsWith("/mcp")) 405 else 404
        logRecorder.log(
            httpMethod = method.name,
            httpPath = uri,
            clientIp = clientIp,
            responseCode = respCode,
            durationMs = System.currentTimeMillis() - requestStartTime,
            rpcMethod = "",
            authResult = authResult,
            success = false,
            errorMessage = if (respCode == 405) "Method not allowed" else "Not found"
        )
        return resp
    }

    /**
     * 处理 MCP Streamable HTTP 传输端点
     *
     * POST   /mcp — JSON-RPC 请求（可能请求 SSE 响应）
     * GET    /mcp — SSE 流（服务器推送事件）
     * DELETE /mcp — 终止会话
     */
    private fun handleMcpEndpoint(
        session: IHTTPSession,
        method: Method,
        clientIp: String,
        authResult: String,
        requestStartTime: Long
    ): Response {
        return when (method) {
            Method.POST -> handleRpc(session, clientIp, authResult, requestStartTime)
            Method.GET -> handleSseStream(session, clientIp, authResult, requestStartTime)
            Method.DELETE -> handleDeleteSession(session, clientIp, authResult, requestStartTime)
            else -> {
                val resp = newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    "application/json",
                    """{"error":"Method not allowed","message":"Use POST, GET, or DELETE on /mcp"}"""
                )
                addCorsHeaders(resp)
                logRecorder.log(
                    httpMethod = method.name,
                    httpPath = MCP_ENDPOINT,
                    clientIp = clientIp,
                    responseCode = 405,
                    durationMs = System.currentTimeMillis() - requestStartTime,
                    rpcMethod = "",
                    authResult = authResult,
                    success = false,
                    errorMessage = "Method not allowed on /mcp"
                )
                resp
            }
        }
    }

    /** 处理 DELETE /mcp — 终止会话 */
    private fun handleDeleteSession(
        session: IHTTPSession,
        clientIp: String,
        authResult: String,
        requestStartTime: Long
    ): Response {
        val sessionId = session.headers[SESSION_HEADER.lowercase()] ?: session.headers[SESSION_HEADER] ?: ""
        if (sessionId.isNotEmpty()) {
            sessions.remove(sessionId)
            Log.i(TAG, "Session deleted: $sessionId")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
        addCorsHeaders(resp)

        logRecorder.log(
            httpMethod = "DELETE",
            httpPath = MCP_ENDPOINT,
            clientIp = clientIp,
            responseCode = 200,
            durationMs = System.currentTimeMillis() - requestStartTime,
            rpcMethod = "session_delete",
            sessionId = sessionId,
            authResult = authResult,
            success = true
        )
        return resp
    }

    /**
     * 处理 GET /mcp — SSE 流
     *
     * MCP Streamable HTTP 规范：客户端可以打开 GET /mcp 来接收服务器推送事件。
     * 我们返回一个简单的 SSE 流，保持连接并定期发送 ping。
     * 如果客户端没有有效会话，返回 400。
     */
    private fun handleSseStream(
        session: IHTTPSession,
        clientIp: String,
        authResult: String,
        requestStartTime: Long
    ): Response {
        val sessionId = session.headers[SESSION_HEADER.lowercase()] ?: session.headers[SESSION_HEADER] ?: ""
        val acceptHeader = session.headers["accept"] ?: ""

        // 如果客户端不接受 text/event-stream，返回基本信息
        if (!acceptHeader.contains("text/event-stream")) {
            val info = buildJsonObject {
                put("endpoint", "POST /mcp")
                put("methods", "tools/list, tools/call, resources/list, resources/read")
                put("note", "Use HTTP POST /mcp for JSON-RPC 2.0")
                put("discovery", "GET /mcp/tools for tool list")
            }
            val resp = jsonResponse(info.toString())
            logRecorder.log(
                httpMethod = "GET",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "sse_info",
                sessionId = sessionId,
                authResult = authResult,
                responseBody = info.toString(),
                success = true
            )
            return resp
        }

        // 返回 SSE 流
        // NanoHTTPD 的 newChunkedResponse 需要 InputStream
        // 我们创建一个 PipedInputStream/PipedOutputStream 来持续推送 SSE 事件
        val pipedInput = java.io.PipedInputStream(1024)
        val pipedOutput = java.io.PipedOutputStream(pipedInput)

        // 启动一个线程来推送 SSE 事件
        Thread {
            try {
                // 发送初始 ping
                pipedOutput.write(": ping\n\n".toByteArray())
                pipedOutput.flush()

                // 每隔 15 秒发送 ping，保持连接
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(15000)
                    pipedOutput.write(": ping\n\n".toByteArray())
                    pipedOutput.flush()
                }
            } catch (e: Exception) {
                // 连接关闭，正常退出
                Log.d(TAG, "SSE stream closed: ${e.message}")
            } finally {
                try { pipedOutput.close() } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "MCP-SSE-$sessionId"
            start()
        }

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedInput)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        resp.addHeader("X-Accel-Buffering", "no")
        addCorsHeaders(resp)

        logRecorder.log(
            httpMethod = "GET",
            httpPath = MCP_ENDPOINT,
            clientIp = clientIp,
            responseCode = 200,
            durationMs = System.currentTimeMillis() - requestStartTime,
            rpcMethod = "sse_stream",
            sessionId = sessionId,
            authResult = authResult,
            success = true
        )
        return resp
    }

    private fun jsonResponse(body: String, status: Response.Status = Response.Status.OK): Response {
        val resp = newFixedLengthResponse(status, "application/json", body)
        addCorsHeaders(resp)
        return resp
    }

    private fun addCorsHeaders(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id")
        resp.addHeader("Access-Control-Expose-Headers", "Mcp-Session-Id")
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

    private fun handleRpc(
        session: IHTTPSession,
        clientIp: String,
        authResult: String,
        requestStartTime: Long
    ): Response {
        val body = try {
            // 不使用 NanoHTTPD 的 parseBody（它用 US-ASCII 解码 postData，导致中文丢失），
            // 而是直接从 InputStream 读取原始字节，用 UTF-8 解码。
            // 在 serve() 被调用时，inputStream 的位置已经在 header 之后（body 开始处）。
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val rawBytes = ByteArray(contentLength)
                val inputStream = session.inputStream
                var offset = 0
                while (offset < contentLength) {
                    val read = inputStream.read(rawBytes, offset, contentLength - offset)
                    if (read < 0) break
                    offset += read
                }
                String(rawBytes, 0, offset, Charsets.UTF_8)
            } else {
                ""
            }
        } catch (e: Exception) {
            val resp = errorResponse(-32700, "Parse error: ${e.message}", null)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "",
                authResult = authResult,
                requestBody = "",
                success = false,
                errorMessage = "Parse error: ${e.message}",
                errorStack = e.stackTraceToString()
            )
            return resp
        }

        // 空请求体可能是批量请求或 SSE 初始化
        if (body.isBlank()) {
            val resp = errorResponse(-32700, "Parse error: empty request body", null)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "",
                authResult = authResult,
                requestBody = "",
                success = false,
                errorMessage = "Parse error: empty request body"
            )
            return resp
        }

        // 检查是否是批量请求（JSON 数组）
        val isBatchRequest = body.trimStart().startsWith("[")

        val acceptHeader = session.headers["accept"] ?: ""
        val wantsSse = acceptHeader.contains("text/event-stream")

        if (isBatchRequest) {
            return handleBatchRpc(body, session, wantsSse, clientIp, authResult, requestStartTime)
        }

        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            val resp = errorResponse(-32700, "Parse error: ${e.message}", null)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "",
                authResult = authResult,
                requestBody = body,
                success = false,
                errorMessage = "Parse error: ${e.message}",
                errorStack = e.stackTraceToString()
            )
            return resp
        }

        val id: JsonElement? = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
        val params = request["params"]?.jsonObject

        // 检查会话
        val sessionId = session.headers[SESSION_HEADER.lowercase()] ?: session.headers[SESSION_HEADER] ?: ""

        // 用于日志的工具名提取
        val toolName = if (method == "tools/call") {
            params?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        } else ""

        // initialize 请求需要创建新会话
        if (method == "initialize") {
            val newSessId = newSessionId()
            sessions[newSessId] = true
            Log.i(TAG, "New MCP session created: $newSessId")

            val result = initializeResult()
            val response = buildJsonObject {
                put("jsonrpc", "2.0")
                if (id != null) put("id", id) else put("id", JsonPrimitive(0))
                put("result", result)
            }

            val resp = if (wantsSse) {
                // 以 SSE 格式返回响应
                sseResponse(response.toString(), newSessId)
            } else {
                jsonResponse(response.toString())
            }
            resp.addHeader(SESSION_HEADER, newSessId)
            addCorsHeaders(resp)

            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "initialize",
                rpcId = id?.toString() ?: "",
                sessionId = newSessId,
                authResult = authResult,
                requestBody = body,
                responseBody = response.toString(),
                success = true
            )
            return resp
        }

        // 非 initialize 请求需要有效会话（如果客户端使用会话）
        if (sessionId.isNotEmpty() && !sessions.containsKey(sessionId)) {
            val resp = errorResponse(-32000, "Invalid session: $sessionId", id)
            resp.addHeader(SESSION_HEADER, sessionId)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = method ?: "",
                rpcId = id?.toString() ?: "",
                toolName = toolName,
                sessionId = sessionId,
                authResult = authResult,
                requestBody = body,
                success = false,
                errorMessage = "Invalid session: $sessionId"
            )
            return resp
        }

        val result = try {
            when (method) {
                "initialize" -> initializeResult()
                "initialized", "notifications/initialized" -> buildJsonObject { } // 通知，无需响应
                "tools/list" -> listTools()
                "tools/call" -> callTool(params)
                "resources/list" -> listResources()
                "resources/read" -> readResource(params)
                "ping" -> buildJsonObject { put("pong", true) }
                "server/discover" -> buildJsonObject {
                    // 某些 MCP 客户端（如 Claude Desktop 的某些版本）发送 server/discover 做服务发现
                    // 返回服务器能力描述，与 initialize 类似但更简洁
                    put("serverInfo", buildJsonObject {
                        put("name", "DayTrace MCP Server")
                        put("version", "0.1.0")
                    })
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", true) })
                        put("resources", buildJsonObject { put("listChanged", true) })
                    })
                    put("protocolVersion", PROTOCOL_VERSION)
                }
                else -> {
                    val resp = errorResponse(-32601, "Method not found: $method", id)
                    logRecorder.log(
                        httpMethod = "POST",
                        httpPath = MCP_ENDPOINT,
                        clientIp = clientIp,
                        responseCode = 200,
                        durationMs = System.currentTimeMillis() - requestStartTime,
                        rpcMethod = method ?: "",
                        rpcId = id?.toString() ?: "",
                        toolName = toolName,
                        sessionId = sessionId,
                        authResult = authResult,
                        requestBody = body,
                        success = false,
                        errorMessage = "Method not found: $method"
                    )
                    return resp
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RPC error: ${e.message}", e)
            val resp = errorResponse(-32603, "Internal error: ${e.message}", id)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = method ?: "",
                rpcId = id?.toString() ?: "",
                toolName = toolName,
                sessionId = sessionId,
                authResult = authResult,
                requestBody = body,
                success = false,
                errorMessage = "Internal error: ${e.message}",
                errorStack = e.stackTraceToString()
            )
            return resp
        }

        // 通知（id 为 null 的请求）不需要返回结果
        // MCP 规范中通知方法名带 'notifications/' 前缀
        if (id == null && (method == "initialized" || method == "notifications/initialized")) {
            val resp = newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
            addCorsHeaders(resp)
            if (sessionId.isNotEmpty()) resp.addHeader(SESSION_HEADER, sessionId)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = method ?: "",
                sessionId = sessionId,
                authResult = authResult,
                requestBody = body,
                success = true
            )
            return resp
        }

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonPrimitive(0))
            put("result", result)
        }

        val resp = if (wantsSse) {
            sseResponse(response.toString(), sessionId)
        } else {
            jsonResponse(response.toString())
        }
        if (sessionId.isNotEmpty()) {
            resp.addHeader(SESSION_HEADER, sessionId)
        }
        addCorsHeaders(resp)

        logRecorder.log(
            httpMethod = "POST",
            httpPath = MCP_ENDPOINT,
            clientIp = clientIp,
            responseCode = 200,
            durationMs = System.currentTimeMillis() - requestStartTime,
            rpcMethod = method ?: "",
            rpcId = id?.toString() ?: "",
            toolName = toolName,
            sessionId = sessionId,
            authResult = authResult,
            requestBody = body,
            responseBody = response.toString(),
            success = true
        )
        return resp
    }

    /** 处理批量 JSON-RPC 请求 */
    private fun handleBatchRpc(
        body: String,
        session: IHTTPSession,
        wantsSse: Boolean,
        clientIp: String,
        authResult: String,
        requestStartTime: Long
    ): Response {
        val requests = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            val resp = errorResponse(-32700, "Parse error: ${e.message}", null)
            logRecorder.log(
                httpMethod = "POST",
                httpPath = MCP_ENDPOINT,
                clientIp = clientIp,
                responseCode = 200,
                durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "batch",
                authResult = authResult,
                requestBody = body,
                success = false,
                errorMessage = "Batch parse error: ${e.message}",
                errorStack = e.stackTraceToString()
            )
            return resp
        }

        val sessionId = session.headers[SESSION_HEADER.lowercase()] ?: session.headers[SESSION_HEADER] ?: ""

        val results = buildJsonArray {
            for (i in 0 until requests.size) {
                val req = requests[i].jsonObject
                val id: JsonElement? = req["id"]
                val method = req["method"]?.jsonPrimitive?.contentOrNull
                val params = req["params"]?.jsonObject

                if (id == null) {
                    // 通知，不返回结果
                    continue
                }

                val result = try {
                    when (method) {
                        "initialize" -> initializeResult()
                        "initialized", "notifications/initialized" -> buildJsonObject { }
                        "tools/list" -> listTools()
                        "tools/call" -> callTool(params)
                        "resources/list" -> listResources()
                        "resources/read" -> readResource(params)
                        "ping" -> buildJsonObject { put("pong", true) }
                        "server/discover" -> buildJsonObject {
                            put("serverInfo", buildJsonObject {
                                put("name", "DayTrace MCP Server")
                                put("version", "0.1.0")
                            })
                            put("capabilities", buildJsonObject {
                                put("tools", buildJsonObject { put("listChanged", true) })
                                put("resources", buildJsonObject { put("listChanged", true) })
                            })
                            put("protocolVersion", PROTOCOL_VERSION)
                        }
                        else -> buildJsonObject {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        }
                    }
                } catch (e: Exception) {
                    buildJsonObject {
                        put("code", -32603)
                        put("message", "Internal error: ${e.message}")
                    }
                }

                add(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                })
            }
        }

        val resp = jsonResponse(results.toString())
        addCorsHeaders(resp)

        logRecorder.log(
            httpMethod = "POST",
            httpPath = MCP_ENDPOINT,
            clientIp = clientIp,
            responseCode = 200,
            durationMs = System.currentTimeMillis() - requestStartTime,
            rpcMethod = "batch",
            sessionId = sessionId,
            authResult = authResult,
            requestBody = body,
            responseBody = results.toString(),
            success = true
        )
        return resp
    }

    /** 构建 SSE 格式响应 */
    private fun sseResponse(jsonRpcResponse: String, sessionId: String): Response {
        val sseData = "data: $jsonRpcResponse\n\n"
        val stream = java.io.ByteArrayInputStream(sseData.toByteArray(Charsets.UTF_8))
        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", stream)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        resp.addHeader("X-Accel-Buffering", "no")
        return resp
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
                name = "push_timeline_diff",
                description = "Upload the day's timeline diff via the selected Upload Skill (e.g. Biji/OpenAPI, generic JSON, multipart, or webhook). Each upload is named with date+time to prevent overwrite. Only sends incremental content since last upload.",
                properties = buildJsonObject {
                    put("date", propObj("string", "Date in YYYY-MM-DD format (empty = today)", ""))
                    put("forceFull", propObj("boolean", "Force full upload instead of diff (default false)", false))
                }
            ))
            add(toolJson(
                name = "get_upload_progress",
                description = "Get the upload progress for a specific date (how much has been uploaded vs total).",
                properties = buildJsonObject {
                    put("date", propObj("string", "Date in YYYY-MM-DD format (empty = today)", ""))
                }
            ))
            add(toolJson(
                name = "list_skills",
                description = "List all available upload Skills (built-in and imported .skill.md files)."
            ))
            add(toolJson(
                name = "load_skill",
                description = "Load (activate) a Skill by its id. The activated Skill will be used for subsequent uploads.",
                properties = buildJsonObject {
                    put("skillId", propObj("string", "Skill id (e.g. biji, generic_json, webhook)", ""))
                },
                required = listOf("skillId")
            ))
            add(toolJson(
                name = "import_skill",
                description = "Import a Skill from a network URL pointing to a .skill.md Markdown file.",
                properties = buildJsonObject {
                    put("url", propObj("string", "URL to .skill.md file", ""))
                },
                required = listOf("url")
            ))
            add(toolJson(
                name = "clear_all_messages",
                description = "Delete all captured messages. Use with caution!"
            ))
            add(toolJson(
                name = "take_photo",
                description = "Remote capture a photo from the phone camera. Returns base64-encoded JPEG image data and capture parameters.",
                properties = buildJsonObject {
                    put("camera", propObj("string", "Camera to use: 'back' (default) or 'front'", "back"))
                    put("resolution", propObj("string", "Photo resolution: '720p', '1080p' (default), '4k', 'max', or custom 'WxH' (e.g. '1920x1080')", "1080p"))
                    put("flash", propObj("string", "Flash mode: 'off' (default), 'on', or 'auto'", "off"))
                }
            ))
            add(toolJson(
                name = "record_video",
                description = "Record a short video from the phone camera. Default: 6s 720p MP4 with audio. Returns a download URL or base64-encoded video data.",
                properties = buildJsonObject {
                    put("camera", propObj("string", "Camera to use: 'back' (default) or 'front'", "back"))
                    put("duration", propObj("integer", "Duration in seconds (default 6, max 60)", 6))
                    put("resolution", propObj("string", "Video resolution: '720p' (default), '1080p', '480p'", "720p"))
                    put("returnBase64", propObj("boolean", "If true, return video as base64 data (default false, returns download URL)", false))
                }
            ))
            add(toolJson(
                name = "record_audio",
                description = "Record audio from the phone microphone. Default: 10s AAC/M4A format. Returns base64-encoded audio data.",
                properties = buildJsonObject {
                    put("duration", propObj("integer", "Duration in seconds (default 10, max 300)", 10))
                    put("format", propObj("string", "Audio format: 'aac' (default, M4A container) or 'wav'", "aac"))
                }
            ))
            add(toolJson(
                name = "list_captures",
                description = "List all captured photos, videos, and audio files stored on the device."
            ))
            add(toolJson(
                name = "delete_capture",
                description = "Delete a captured file by its filename.",
                properties = buildJsonObject {
                    put("filename", propObj("string", "Filename to delete (e.g. photo_2026-08-24_14-30-00-000.jpg)", ""))
                    put("category", propObj("string", "Category: 'photos', 'videos', 'audio', or 'screenshots'", "photos"))
                },
                required = listOf("filename")
            ))
            add(toolJson(
                name = "grant_camera_mic_permissions",
                description = "Attempt to grant CAMERA and RECORD_AUDIO permissions via Shizuku (shell-level pm grant). Requires Shizuku running and authorized."
            ))
            add(toolJson(
                name = "take_screenshot",
                description = "Capture a screenshot of the current phone screen. Returns base64-encoded image data (PNG or JPEG) and screen metadata. Requires Shizuku running and authorized.",
                properties = buildJsonObject {
                    put("format", propObj("string", "Image format: 'png' (default, lossless) or 'jpeg' (compressed, smaller size)", "png"))
                    put("quality", propObj("integer", "JPEG quality (1-100, default 80). Only used when format=jpeg", 80))
                    put("returnBase64", propObj("boolean", "If true (default), return image as base64 data. If false, only return download URL", true))
                }
            ))
            add(toolJson(
                name = "get_screen_text",
                description = "Extract all visible text from the current phone screen. Uses the accessibility service to traverse the view hierarchy and collect text from all nodes, sorted top-to-bottom by Y position. Set includeBounds=true to get each element's screen coordinates (x, y) and size (width, height) — useful for identifying clickable elements to operate via tap gesture.",
                properties = buildJsonObject {
                    put("includeBounds", propObj("boolean", "If true, include bounding box coordinates [x,y] and size [WxH] for each text element. Use this to find element positions for tap/swipe operations. Default false.", false))
                }
            ))
            // === 设备信息 (3) ===
            add(toolJson(
                name = "get_device_info",
                description = "Get comprehensive device information: model, Android version, battery level/charging state, memory/storage usage, screen resolution, and network status."
            ))
            add(toolJson(
                name = "get_app_usage_stats",
                description = "Get today's app usage statistics (foreground time per app, sorted by duration). Requires PACKAGE_USAGE_STATS permission or Shizuku."
            ))
            add(toolJson(
                name = "get_installed_apps",
                description = "List all installed apps on the device with package name, app name, version, and whether it's a system app.",
                properties = buildJsonObject {
                    put("includeSystem", propObj("boolean", "Include system apps in the list (default false)", false))
                }
            ))
            // === 电池 (1) ===
            add(toolJson(
                name = "get_battery_history",
                description = "Get detailed battery information: level, charging status, health, temperature, voltage, technology, and battery service dump via Shizuku."
            ))
            // === 剪贴板 (2) ===
            add(toolJson(
                name = "get_clipboard",
                description = "Read the current content of the system clipboard."
            ))
            add(toolJson(
                name = "set_clipboard",
                description = "Write text to the system clipboard.",
                properties = buildJsonObject {
                    put("text", propObj("string", "Text to set on the clipboard", ""))
                },
                required = listOf("text")
            ))
            // === 通知 (2) ===
            add(toolJson(
                name = "send_notification",
                description = "Send a local notification to the phone (e.g. for AI to remind the user).",
                properties = buildJsonObject {
                    put("title", propObj("string", "Notification title", ""))
                    put("message", propObj("string", "Notification body text", ""))
                    put("priority", propObj("string", "Priority: 'low', 'default', or 'high'", "default"))
                },
                required = listOf("title", "message")
            ))
            add(toolJson(
                name = "get_notification_log",
                description = "Get recent notification history (captured by the notification listener service, in-memory cache of last 100).",
                properties = buildJsonObject {
                    put("limit", propObj("integer", "Max notifications to return (default 50, max 100)", 50))
                }
            ))
            // === 短信 (1) ===
            add(toolJson(
                name = "send_text_message",
                description = "Send an SMS message to a phone number. Requires SEND_SMS permission (via Shizuku pm grant) or Shizuku shell.",
                properties = buildJsonObject {
                    put("phoneNumber", propObj("string", "Recipient phone number", ""))
                    put("message", propObj("string", "SMS message content", ""))
                },
                required = listOf("phoneNumber", "message")
            ))
            // === 屏幕操作 (3) ===
            add(toolJson(
                name = "take_screenshot_region",
                description = "Capture a screenshot of a specific region of the screen. Takes a full screenshot first, then crops to the specified bounds. Requires Shizuku.",
                properties = buildJsonObject {
                    put("left", propObj("integer", "Left boundary (pixels)", 0))
                    put("top", propObj("integer", "Top boundary (pixels)", 0))
                    put("right", propObj("integer", "Right boundary (pixels)", 0))
                    put("bottom", propObj("integer", "Bottom boundary (pixels)", 0))
                    put("format", propObj("string", "Image format: 'png' (default) or 'jpeg'", "png"))
                    put("quality", propObj("integer", "JPEG quality 1-100 (default 80)", 80))
                },
                required = listOf("left", "top", "right", "bottom")
            ))
            add(toolJson(
                name = "get_screen_interactive_elements",
                description = "Extract all interactive elements (buttons, input fields, toggles, etc.) from the current screen with their coordinates. Useful for AI to know where to tap. Requires accessibility service."
            ))
            add(toolJson(
                name = "tap_screen",
                description = "Simulate a tap at the specified screen coordinates. Requires Shizuku.",
                properties = buildJsonObject {
                    put("x", propObj("integer", "X coordinate (pixels)", 0))
                    put("y", propObj("integer", "Y coordinate (pixels)", 0))
                },
                required = listOf("x", "y")
            ))
            // === UI 操作 (3) ===
            add(toolJson(
                name = "input_text",
                description = "Type text into the currently focused input field. ASCII text uses 'input text' command; non-ASCII (e.g. Chinese) uses clipboard paste. Requires Shizuku.",
                properties = buildJsonObject {
                    put("text", propObj("string", "Text to type into the focused field", ""))
                },
                required = listOf("text")
            ))
            add(toolJson(
                name = "press_key",
                description = "Simulate a key press (Home, Back, Recent, Volume, etc.). Requires Shizuku. Available keys: home, back, recent, menu, power, volume_up, volume_down, volume_mute, delete, enter, escape, tab, space, search, camera, call, end_call, notification, screenshot.",
                properties = buildJsonObject {
                    put("key", propObj("string", "Key name (e.g. 'home', 'back') or keycode number", ""))
                },
                required = listOf("key")
            ))
            add(toolJson(
                name = "swipe",
                description = "Simulate a swipe gesture from one point to another. Requires Shizuku.",
                properties = buildJsonObject {
                    put("startX", propObj("integer", "Start X coordinate", 0))
                    put("startY", propObj("integer", "Start Y coordinate", 0))
                    put("endX", propObj("integer", "End X coordinate", 0))
                    put("endY", propObj("integer", "End Y coordinate", 0))
                    put("durationMs", propObj("integer", "Swipe duration in milliseconds (default 300)", 300))
                },
                required = listOf("startX", "startY", "endX", "endY")
            ))
            // === App 操作 (1) ===
            add(toolJson(
                name = "open_app",
                description = "Open/launch an app by its package name. Requires Shizuku.",
                properties = buildJsonObject {
                    put("packageName", propObj("string", "App package name (e.g. com.tencent.mm)", ""))
                    put("activity", propObj("string", "Optional specific activity to launch (e.g. .ui.LauncherUI)", ""))
                },
                required = listOf("packageName")
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

            "push_timeline_diff" -> {
                val dateStr = args["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val forceFull = args["forceFull"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val dateMillis = parseDateOrToday(dateStr)
                val result = kotlinx.coroutines.runBlocking {
                    com.daytrace.net.DiffUploader.uploadDayDiff(context, dateMillis, forceFull)
                }
                buildString {
                    appendLine("=== Timeline Diff Upload ===")
                    appendLine("Date: ${if (dateStr.isEmpty()) "today" else dateStr}")
                    appendLine("Mode: ${if (forceFull) "full" else "diff"}")
                    appendLine("Success: ${result.success}")
                    appendLine("Message: ${result.message}")
                    appendLine("Uploaded bytes: ${result.uploadedBytes}")
                    appendLine("Total bytes: ${result.totalBytes}")
                }
            }

            "get_upload_progress" -> {
                val dateStr = args["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val dateMillis = parseDateOrToday(dateStr)
                val progress = com.daytrace.net.DiffUploader.getUploadProgress(context, dateMillis)
                buildString {
                    appendLine("=== Upload Progress ===")
                    appendLine("Date: ${progress.dateStr}")
                    appendLine("Total size: ${progress.totalSize} bytes")
                    appendLine("Uploaded: ${progress.uploadedOffset} bytes")
                    appendLine("Remaining: ${progress.remainingBytes} bytes")
                    appendLine("Progress: ${progress.progressPercent}%")
                    appendLine("Complete: ${progress.isComplete}")
                    val lastTime = if (progress.lastUploadTime > 0)
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(progress.lastUploadTime))
                    else "never"
                    appendLine("Last upload: $lastTime")
                }
            }

            "list_skills" -> {
                val skills = com.daytrace.net.SkillManager.getAllSkills(context)
                buildString {
                    appendLine("Available Skills:")
                    for (skill in skills) {
                        appendLine("- ${skill.id}: ${skill.name} ${if (skill.isImported) "(imported)" else "(built-in)"}")
                        appendLine("  ${skill.description}")
                        if (skill.credentials.isNotEmpty()) {
                            appendLine("  Credentials: ${skill.credentials.joinToString(", ") { it.id }}")
                        }
                    }
                }
            }

            "load_skill" -> {
                val skillId = args["skillId"]?.jsonPrimitive?.contentOrNull ?: ""
                val skill = com.daytrace.net.SkillManager.getSkill(context, skillId)
                if (skill != null) {
                    com.daytrace.util.Prefs.setUploadSkillId(context, skillId)
                    "Skill '${skill.name}' ($skillId) activated."
                } else {
                    "Skill not found: $skillId"
                }
            }

            "import_skill" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull ?: ""
                kotlinx.coroutines.runBlocking {
                    val result = com.daytrace.net.SkillManager.importFromUrl(context, url)
                    result.fold(
                        onSuccess = { skill -> "Skill imported: ${skill.name} (${skill.id})" },
                        onFailure = { e -> "Import failed: ${e.message}" }
                    )
                }
            }

            "clear_all_messages" -> {
                kotlinx.coroutines.runBlocking { dao.clearAll() }
                "All messages have been deleted."
            }

            "take_photo" -> {
                val camera = args["camera"]?.jsonPrimitive?.contentOrNull ?: "back"
                val resolution = args["resolution"]?.jsonPrimitive?.contentOrNull ?: "1080p"
                val flash = args["flash"]?.jsonPrimitive?.contentOrNull ?: "off"

                // 检查权限
                val hasPerm = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    "Error: CAMERA permission not granted. Call grant_camera_mic_permissions first."
                } else {
                    val result = cameraManager.takePhoto(camera, resolution, flash)
                    if (result != null) {
                        val base64 = encodeFileBase64(result.file)
                        buildString {
                            appendLine("=== Photo Captured ===")
                            appendLine("Camera: ${result.cameraFacing}")
                            appendLine("Resolution: ${result.width}x${result.height}")
                            appendLine("File size: ${result.fileSize} bytes")
                            appendLine("Flash: ${result.flashMode}")
                            // 拍摄参数
                            result.focalLength?.let { appendLine("Focal length: ${it}mm") }
                            result.iso?.let { appendLine("ISO: $it") }
                            result.exposureTimeNs?.let {
                                val exposureMs = it / 1_000_000.0
                                appendLine("Exposure time: ${exposureMs}ms (${it}ns)")
                            }
                            result.aperture?.let { appendLine("Aperture: f/$it") }
                            result.whiteBalanceMode?.let { appendLine("White balance: $it") }
                            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(result.timestamp))}")
                            appendLine("Download URL: http://<device>:${getListeningPort()}/captures/photos/${result.file.name}")
                            if (base64 != null) {
                                appendLine()
                                appendLine("--- base64 ---")
                                appendLine(base64)
                            } else {
                                appendLine("(base64 encoding failed, use download URL)")
                            }
                        }
                    } else {
                        "Error: Failed to capture photo. Check camera permissions and availability."
                    }
                }
            }

            "record_video" -> {
                val camera = args["camera"]?.jsonPrimitive?.contentOrNull ?: "back"
                val duration = args["duration"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 60) ?: 6
                val resolution = args["resolution"]?.jsonPrimitive?.contentOrNull ?: "720p"
                val returnBase64 = args["returnBase64"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

                // 检查权限
                val hasCamera = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasMic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasCamera || !hasMic) {
                    "Error: CAMERA and RECORD_AUDIO permissions required. Call grant_camera_mic_permissions first."
                } else {
                    val result = cameraManager.recordVideo(camera, duration, resolution)
                    if (result != null) {
                        val base64 = if (returnBase64) encodeFileBase64(result.file) else null
                        buildString {
                            appendLine("=== Video Recorded ===")
                            appendLine("Camera: ${result.cameraFacing}")
                            appendLine("Resolution: ${result.width}x${result.height}")
                            appendLine("Duration: ${result.durationSec}s")
                            appendLine("File size: ${result.fileSize} bytes")
                            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(result.timestamp))}")
                            appendLine("Download URL: http://<device>:${getListeningPort()}/captures/videos/${result.file.name}")
                            if (returnBase64 && base64 != null) {
                                appendLine()
                                appendLine("--- base64 ---")
                                appendLine(base64)
                            } else {
                                appendLine("(Use download URL or set returnBase64=true for inline data)")
                            }
                        }
                    } else {
                        "Error: Failed to record video. Check camera/mic permissions and availability."
                    }
                }
            }

            "record_audio" -> {
                val duration = args["duration"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 300) ?: 10
                val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "aac"

                // 检查权限
                val hasMic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    "Error: RECORD_AUDIO permission not granted. Call grant_camera_mic_permissions first."
                } else {
                    val result = audioManager.recordAudio(duration, format)
                    if (result != null) {
                        val base64 = encodeFileBase64(result.file)
                        buildString {
                            appendLine("=== Audio Recorded ===")
                            appendLine("Format: ${result.mimeType}")
                            appendLine("Duration: ${result.durationSec}s")
                            appendLine("Sample rate: ${result.sampleRate} Hz")
                            appendLine("Channels: ${result.channels}")
                            appendLine("File size: ${result.fileSize} bytes")
                            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(result.timestamp))}")
                            appendLine("Download URL: http://<device>:${getListeningPort()}/captures/audio/${result.file.name}")
                            if (base64 != null) {
                                appendLine()
                                appendLine("--- base64 ---")
                                appendLine(base64)
                            } else {
                                appendLine("(base64 encoding failed, use download URL)")
                            }
                        }
                    } else {
                        "Error: Failed to record audio. Check microphone permissions."
                    }
                }
            }

            "list_captures" -> {
                val capturesDir = context.getExternalFilesDir("captures")!!
                buildString {
                    appendLine("=== Captured Files ===")
                    for (category in listOf("photos", "videos", "audio", "screenshots")) {
                        val dir = File(capturesDir, category)
                        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                        appendLine()
                        appendLine("[$category] (${files.size} files)")
                        if (files.isEmpty()) {
                            appendLine("  (none)")
                        } else {
                            for (f in files) {
                                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(f.lastModified()))
                                appendLine("  ${f.name} (${f.length()} bytes, $date)")
                            }
                        }
                    }
                }
            }

            "delete_capture" -> {
                val filename = args["filename"]?.jsonPrimitive?.contentOrNull ?: ""
                val category = args["category"]?.jsonPrimitive?.contentOrNull ?: "photos"
                if (filename.isBlank()) {
                    "Error: filename is required"
                } else {
                    // 安全检查：防止路径遍历
                    val safeName = File(filename).name
                    val capturesDir = context.getExternalFilesDir("captures")!!
                    val file = File(File(capturesDir, category), safeName)
                    if (file.exists() && file.delete()) {
                        "Deleted: ${file.name}"
                    } else {
                        "File not found or delete failed: $safeName"
                    }
                }
            }

            "grant_camera_mic_permissions" -> {
                val result = com.daytrace.util.ShizukuHelper.grantCameraAndMicPermissions(context)
                if (result.isSuccess) {
                    val cameraGranted = com.daytrace.util.ShizukuHelper.isCameraGranted(context)
                    val micGranted = com.daytrace.util.ShizukuHelper.isMicGranted(context)
                    buildString {
                        appendLine("=== Permission Grant Result ===")
                        appendLine("Camera: ${if (cameraGranted) "granted" else "NOT granted"}")
                        appendLine("Microphone: ${if (micGranted) "granted" else "NOT granted"}")
                        if (!cameraGranted || !micGranted) {
                            appendLine("Some permissions failed. Please grant manually in Android Settings.")
                        }
                    }
                } else {
                    "Error: ${result.exceptionOrNull()?.message}"
                }
            }

            "take_screenshot" -> {
                val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "png"
                val quality = args["quality"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 80
                val returnBase64 = args["returnBase64"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true

                // 检查 Shizuku 权限
                if (!com.daytrace.util.ShizukuHelper.hasPermission()) {
                    "Error: Shizuku permission not granted. Start Shizuku and authorize DayTrace first."
                } else {
                    val result = screenManager.takeScreenshot(format, quality)
                    if (result != null) {
                        val base64 = if (returnBase64) screenManager.encodeFileBase64(result.file) else null
                        buildString {
                            appendLine("=== Screenshot Captured ===")
                            appendLine("Format: ${result.format}")
                            appendLine("Resolution: ${result.width}x${result.height}")
                            appendLine("File size: ${result.fileSize} bytes")
                            appendLine("Foreground app: ${result.foregroundPackage}")
                            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(result.timestamp))}")
                            appendLine("Download URL: http://<device>:${getListeningPort()}/captures/screenshots/${result.file.name}")
                            if (returnBase64 && base64 != null) {
                                appendLine()
                                appendLine("--- base64 ---")
                                appendLine(base64)
                            } else {
                                appendLine("(Use download URL or set returnBase64=true for inline data)")
                            }
                        }
                    } else {
                        "Error: Failed to capture screenshot. Check Shizuku permissions and availability."
                    }
                }
            }

            "get_screen_text" -> {
                // 检查无障碍服务是否运行
                if (!com.daytrace.service.CaptureAccessibilityService.isRunning) {
                    "Error: Accessibility service not running. Enable it in Android Settings or via Shizuku."
                } else {
                    val includeBounds = args["includeBounds"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                    val result = screenManager.getScreenText(includeBounds)
                    if (result != null) {
                        buildString {
                            appendLine("=== Screen Text ===")
                            appendLine("Foreground app: ${result.foregroundPackage}")
                            appendLine("Text nodes: ${result.nodeCount}")
                            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(result.timestamp))}")
                            appendLine()
                            appendLine(result.text)
                            // 如果 includeBounds=true，追加结构化的节点列表（含坐标、尺寸）
                            result.nodes?.let { nodes ->
                                if (nodes.isNotEmpty()) {
                                    appendLine()
                                    appendLine("=== Elements with Bounds ===")
                                    appendLine("format: [x,y] WxH \"text\" (source)")
                                    appendLine("---")
                                    nodes.forEach { n ->
                                        appendLine("[${n.left},${n.top}] ${n.width}x${n.height} \"${n.text}\" (${n.source})")
                                    }
                                }
                            }
                        }
                    } else {
                        "Error: Failed to extract screen text. Accessibility service may not have focus."
                    }
                }
            }

            // === 设备信息 ===

            "get_device_info" -> {
                deviceInfoManager.getDeviceInfo()
            }

            "get_app_usage_stats" -> {
                deviceInfoManager.getAppUsageStats()
            }

            "get_installed_apps" -> {
                val includeSystem = args["includeSystem"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                deviceInfoManager.getInstalledApps(includeSystem)
            }

            // === 电池 ===

            "get_battery_history" -> {
                deviceInfoManager.getBatteryHistory()
            }

            // === 剪贴板 ===

            "get_clipboard" -> {
                deviceInfoManager.getClipboard()
            }

            "set_clipboard" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
                if (text.isBlank()) "Error: text is required"
                else deviceInfoManager.setClipboard(text)
            }

            // === 通知 ===

            "send_notification" -> {
                val title = args["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = args["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val priority = args["priority"]?.jsonPrimitive?.contentOrNull ?: "default"
                if (title.isBlank() || message.isBlank()) {
                    "Error: title and message are required"
                } else {
                    deviceInfoManager.sendNotification(title, message, priority)
                }
            }

            "get_notification_log" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 50
                val records = com.daytrace.service.NotificationCaptureService.getNotificationHistory(limit)
                if (records.isEmpty()) {
                    "No notification history captured yet."
                } else {
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    buildString {
                        appendLine("=== Notification History (${records.size} of last ${minOf(limit, 100)}) ===")
                        records.forEach { r ->
                            val type = if (r.chatType == 2) "[群]" else "[私]"
                            append("[${df.format(Date(r.timestamp))}] ${r.appName} $type")
                            if (r.sender.isNotEmpty()) append(" ${r.sender}")
                            append("\n  ${r.text.take(200)}")
                            append("\n  (${r.packageName})\n\n")
                        }
                    }
                }
            }

            // === 短信 ===

            "send_text_message" -> {
                val phoneNumber = args["phoneNumber"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = args["message"]?.jsonPrimitive?.contentOrNull ?: ""
                if (phoneNumber.isBlank() || message.isBlank()) {
                    "Error: phoneNumber and message are required"
                } else {
                    deviceInfoManager.sendTextMessage(phoneNumber, message)
                }
            }

            // === 屏幕操作 ===

            "take_screenshot_region" -> {
                val left = args["left"]?.jsonPrimitive?.intOrNull ?: 0
                val top = args["top"]?.jsonPrimitive?.intOrNull ?: 0
                val right = args["right"]?.jsonPrimitive?.intOrNull ?: 0
                val bottom = args["bottom"]?.jsonPrimitive?.intOrNull ?: 0
                val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "png"
                val quality = args["quality"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 80

                if (!com.daytrace.util.ShizukuHelper.hasPermission()) {
                    "Error: Shizuku permission not granted."
                } else {
                    val result = screenManager.takeScreenshotRegion(left, top, right, bottom, format, quality)
                    if (result != null) {
                        val base64 = screenManager.encodeFileBase64(result.file)
                        buildString {
                            appendLine("=== Screenshot Region Captured ===")
                            appendLine("Bounds: ($left,$top)-($right,$bottom)")
                            appendLine("Cropped size: ${result.width}x${result.height}")
                            appendLine("Format: ${result.format}")
                            appendLine("File size: ${result.fileSize} bytes")
                            appendLine("Foreground app: ${result.foregroundPackage}")
                            appendLine("Download URL: http://<device>:${getListeningPort()}/captures/screenshots/${result.file.name}")
                            if (base64 != null) {
                                appendLine()
                                appendLine("--- base64 ---")
                                appendLine(base64)
                            } else {
                                appendLine("(base64 encoding failed, use download URL)")
                            }
                        }
                    } else {
                        "Error: Failed to capture screenshot region. Check bounds and Shizuku permissions."
                    }
                }
            }

            "get_screen_interactive_elements" -> {
                if (!com.daytrace.service.CaptureAccessibilityService.isRunning) {
                    "Error: Accessibility service not running. Enable it in Android Settings or via Shizuku."
                } else {
                    actionManager.getScreenInteractiveElements()
                        ?: "Error: Failed to get interactive elements. Accessibility service may not have focus."
                }
            }

            "tap_screen" -> {
                val x = args["x"]?.jsonPrimitive?.intOrNull ?: -1
                val y = args["y"]?.jsonPrimitive?.intOrNull ?: -1
                actionManager.tapScreen(x, y)
            }

            // === UI 操作 ===

            "input_text" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
                if (text.isBlank()) "Error: text is required"
                else actionManager.inputText(text)
            }

            "press_key" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull ?: ""
                if (key.isBlank()) "Error: key is required"
                else actionManager.pressKey(key)
            }

            "swipe" -> {
                val startX = args["startX"]?.jsonPrimitive?.intOrNull ?: 0
                val startY = args["startY"]?.jsonPrimitive?.intOrNull ?: 0
                val endX = args["endX"]?.jsonPrimitive?.intOrNull ?: 0
                val endY = args["endY"]?.jsonPrimitive?.intOrNull ?: 0
                val durationMs = args["durationMs"]?.jsonPrimitive?.intOrNull?.coerceIn(50, 5000) ?: 300
                actionManager.swipe(startX, startY, endX, endY, durationMs)
            }

            // === App 操作 ===

            "open_app" -> {
                val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                val activity = args["activity"]?.jsonPrimitive?.contentOrNull ?: ""
                if (packageName.isBlank()) "Error: packageName is required"
                else actionManager.openApp(packageName, activity)
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

    // === 文件下载端点 ===

    /**
     * 处理 /captures/<category>/<filename> GET 请求
     *
     * 支持的路径：
     *   /captures/photos/<filename>   — 下载照片
     *   /captures/videos/<filename>    — 下载视频
     *   /captures/audio/<filename>     — 下载音频
     */
    private fun handleCaptureDownload(
        uri: String,
        clientIp: String,
        authResult: String,
        requestStartTime: Long
    ): Response {
        // 解析路径：/captures/<category>/<filename>
        val parts = uri.removePrefix("/captures/").split("/", limit = 2)
        if (parts.size != 2) {
            val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Invalid path: $uri")
            addCorsHeaders(resp)
            logRecorder.log(
                httpMethod = "GET", httpPath = uri, clientIp = clientIp,
                responseCode = 404, durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "capture_download", authResult = authResult,
                success = false, errorMessage = "Invalid path"
            )
            return resp
        }

        val category = parts[0]
        val filename = File(parts[1]).name  // 防止路径遍历

        // 验证 category
        if (category !in listOf("photos", "videos", "audio", "screenshots")) {
            val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Invalid category: $category")
            addCorsHeaders(resp)
            logRecorder.log(
                httpMethod = "GET", httpPath = uri, clientIp = clientIp,
                responseCode = 404, durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "capture_download", authResult = authResult,
                success = false, errorMessage = "Invalid category: $category"
            )
            return resp
        }

        val capturesDir = context.getExternalFilesDir("captures")!!
        val file = File(File(capturesDir, category), filename)

        if (!file.exists() || !file.isFile) {
            val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found: $filename")
            addCorsHeaders(resp)
            logRecorder.log(
                httpMethod = "GET", httpPath = uri, clientIp = clientIp,
                responseCode = 404, durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "capture_download", authResult = authResult,
                success = false, errorMessage = "File not found: $filename"
            )
            return resp
        }

        // 确定 MIME type
        val mimeType = when {
            filename.endsWith(".png", true) -> "image/png"
            filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
            filename.endsWith(".mp4", true) -> "video/mp4"
            filename.endsWith(".m4a", true) || filename.endsWith(".aac", true) -> "audio/mp4"
            filename.endsWith(".wav", true) -> "audio/wav"
            else -> "application/octet-stream"
        }

        try {
            val fis = FileInputStream(file)
            val resp = newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
            resp.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
            resp.addHeader("Content-Length", file.length().toString())
            addCorsHeaders(resp)

            logRecorder.log(
                httpMethod = "GET", httpPath = uri, clientIp = clientIp,
                responseCode = 200, durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "capture_download", authResult = authResult,
                responseBody = "file: $filename (${file.length()} bytes)",
                success = true
            )
            return resp
        } catch (e: Exception) {
            val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Read error: ${e.message}")
            addCorsHeaders(resp)
            logRecorder.log(
                httpMethod = "GET", httpPath = uri, clientIp = clientIp,
                responseCode = 500, durationMs = System.currentTimeMillis() - requestStartTime,
                rpcMethod = "capture_download", authResult = authResult,
                success = false, errorMessage = "Read error: ${e.message}"
            )
            return resp
        }
    }

    /** 将文件编码为 Base64 字符串（用于内嵌返回照片/音频） */
    private fun encodeFileBase64(file: File): String? {
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encodeFileBase64 failed: ${e.message}")
            null
        }
    }

    private fun initializeResult(): JsonObject {
        return buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", true)
                })
                put("resources", buildJsonObject {
                    put("listChanged", true)
                })
                put("logging", buildJsonObject {})
            })
            put("serverInfo", buildJsonObject {
                put("name", "DayTrace MCP Server")
                put("version", "0.1.0")
            })
        }
    }
}