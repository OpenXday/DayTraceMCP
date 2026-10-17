package com.daytrace.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MCP 调用日志条目 —— 记录每次 MCP JSON-RPC 调用的完整信息
 *
 * 涵盖：请求到达、认证、路由、方法分发、工具执行、响应返回
 * 用于在 App 内审计 MCP Server 的被调用情况
 */
@Entity(
    tableName = "mcp_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["rpcMethod"]),
        Index(value = ["toolName"]),
        Index(value = ["sessionId"]),
        Index(value = ["success"])
    ]
)
data class McpLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),

    // === HTTP 层面 ===
    val httpMethod: String,             // POST / GET / DELETE
    val httpPath: String,               // /mcp, /mcp/tools, /, /mcp/sse
    val clientIp: String,              // 客户端 IP
    val responseCode: Int,             // HTTP 状态码 (200, 401, 405, 500...)
    val durationMs: Long,              // 处理耗时（毫秒）

    // === JSON-RPC 层面 ===
    val rpcMethod: String,             // initialize, tools/list, tools/call, ping, resources/list...
    val rpcId: String,                 // JSON-RPC id（可能为 null）
    val toolName: String,              // 当 rpcMethod=tools/call 时的工具名
    val sessionId: String,             // MCP 会话 ID

    // === 认证 ===
    val authResult: String,            // ok / failed / not_required / not_checked

    // === 请求/响应 ===
    val requestBody: String,           // 请求 JSON body（截断到 5000 字符）
    val responseBody: String,          // 响应 JSON body（截断到 5000 字符）
    val success: Boolean,              // 调用是否成功
    val errorMessage: String,          // 错误信息（失败时）
    val errorStack: String             // 异常堆栈（失败时，截断）
) {
    companion object {
        const val AUTH_OK = "ok"
        const val AUTH_FAILED = "failed"
        const val AUTH_NOT_REQUIRED = "not_required"
        const val AUTH_NOT_CHECKED = "not_checked"
    }
}
