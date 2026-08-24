package com.daytrace.server

import android.content.Context
import android.util.Log
import com.daytrace.data.McpLogDao
import com.daytrace.data.McpLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MCP 调用日志记录器
 *
 * 以非阻塞方式将每次 MCP 调用记录到 Room 数据库。
 * 使用独立的 IO 协程作用域，不影响请求处理线程。
 *
 * 记录的内容包括：
 * - HTTP 层面：方法、路径、客户端 IP、状态码、耗时
 * - JSON-RPC 层面：method、id、toolName、sessionId
 * - 认证结果
 * - 请求/响应 body（截断到 5000 字符防止数据库膨胀）
 * - 成功/失败 + 错误信息
 */
class McpLogRecorder(private val context: Context) {

    companion object {
        private const val TAG = "DayTrace/MCPLog"
        private const val MAX_BODY_LENGTH = 5000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao: McpLogDao by lazy {
        (context.applicationContext as com.daytrace.DayTraceApp).database.mcpLogDao()
    }

    /**
     * 异步记录一条 MCP 调用日志
     */
    fun log(
        httpMethod: String,
        httpPath: String,
        clientIp: String,
        responseCode: Int,
        durationMs: Long,
        rpcMethod: String = "",
        rpcId: String = "",
        toolName: String = "",
        sessionId: String = "",
        authResult: String = McpLogEntry.AUTH_NOT_CHECKED,
        requestBody: String = "",
        responseBody: String = "",
        success: Boolean = true,
        errorMessage: String = "",
        errorStack: String = ""
    ) {
        val entry = McpLogEntry(
            timestamp = System.currentTimeMillis(),
            httpMethod = httpMethod,
            httpPath = httpPath,
            clientIp = clientIp,
            responseCode = responseCode,
            durationMs = durationMs,
            rpcMethod = rpcMethod,
            rpcId = rpcId,
            toolName = toolName,
            sessionId = sessionId,
            authResult = authResult,
            requestBody = truncate(requestBody, MAX_BODY_LENGTH),
            responseBody = truncate(responseBody, MAX_BODY_LENGTH),
            success = success,
            errorMessage = truncate(errorMessage, MAX_BODY_LENGTH),
            errorStack = truncate(errorStack, MAX_BODY_LENGTH)
        )

        scope.launch {
            try {
                dao.insert(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write MCP log: ${e.message}")
            }
        }
    }

    private fun truncate(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        return s.take(maxLen) + "...[truncated]"
    }
}
