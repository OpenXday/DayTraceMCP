package com.daytrace.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface McpLogDao {

    @Insert
    suspend fun insert(entry: McpLogEntry): Long

    // === 分页查询 ===

    @Query("SELECT * FROM mcp_logs ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<McpLogEntry>

    @Query("SELECT * FROM mcp_logs ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageFlow(limit: Int, offset: Int): Flow<List<McpLogEntry>>

    // === 按方法过滤的分页查询 ===

    @Query("SELECT * FROM mcp_logs WHERE rpcMethod = :method ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPageByMethod(method: String, limit: Int, offset: Int): List<McpLogEntry>

    @Query("SELECT * FROM mcp_logs WHERE rpcMethod = :method ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageByMethodFlow(method: String, limit: Int, offset: Int): Flow<List<McpLogEntry>>

    // === 按成功/失败过滤 ===

    @Query("SELECT * FROM mcp_logs WHERE success = :success ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageBySuccessFlow(success: Boolean, limit: Int, offset: Int): Flow<List<McpLogEntry>>

    // === 按工具名过滤 ===

    @Query("SELECT * FROM mcp_logs WHERE toolName = :toolName ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageByToolFlow(toolName: String, limit: Int, offset: Int): Flow<List<McpLogEntry>>

    // === 单条查询 ===

    @Query("SELECT * FROM mcp_logs WHERE id = :id")
    suspend fun getById(id: Long): McpLogEntry?

    // === 统计 ===

    @Query("SELECT COUNT(*) FROM mcp_logs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM mcp_logs WHERE success = 1")
    suspend fun countSuccess(): Int

    @Query("SELECT COUNT(*) FROM mcp_logs WHERE success = 0")
    suspend fun countFailed(): Int

    @Query("SELECT COUNT(*) FROM mcp_logs WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    // === 按方法统计 ===

    data class MethodCount(
        val rpcMethod: String,
        val count: Int
    )

    @Query("SELECT rpcMethod, COUNT(*) as count FROM mcp_logs GROUP BY rpcMethod ORDER BY count DESC")
    suspend fun countByMethod(): List<MethodCount>

    // === 按工具统计 ===

    data class ToolCount(
        val toolName: String,
        val count: Int
    )

    @Query("SELECT toolName, COUNT(*) as count FROM mcp_logs WHERE toolName != '' GROUP BY toolName ORDER BY count DESC")
    suspend fun countByTool(): List<ToolCount>

    // === 清理 ===

    @Query("DELETE FROM mcp_logs WHERE timestamp < :before")
    suspend fun clearBefore(before: Long)

    @Query("DELETE FROM mcp_logs")
    suspend fun clearAll()

    // === 最近活动（Flow for UI） ===

    @Query("SELECT * FROM mcp_logs ORDER BY timestamp DESC LIMIT :limit")
    fun recentLogs(limit: Int = 100): Flow<List<McpLogEntry>>

    // === 活跃会话 ===

    @Query("SELECT DISTINCT sessionId FROM mcp_logs WHERE sessionId != '' AND timestamp >= :since ORDER BY timestamp DESC")
    suspend fun activeSessionsSince(since: Long): List<String>

    // === 按会话查询 ===

    @Query("SELECT * FROM mcp_logs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun bySession(sessionId: String): List<McpLogEntry>
}
