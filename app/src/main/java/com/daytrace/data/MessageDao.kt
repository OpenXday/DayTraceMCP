package com.daytrace.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(entry: MessageEntry): Long

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun recentMessages(limit: Int = 500): Flow<List<MessageEntry>>

    @Query("SELECT * FROM messages WHERE direction = :dir ORDER BY timestamp DESC LIMIT :limit")
    fun messagesByDirection(dir: Int, limit: Int = 500): Flow<List<MessageEntry>>

    @Query("SELECT * FROM messages WHERE appPackage = :pkg ORDER BY timestamp DESC LIMIT :limit")
    fun messagesByApp(pkg: String, limit: Int = 500): Flow<List<MessageEntry>>

    @Query("SELECT * FROM messages WHERE direction = :dir ORDER BY timestamp DESC LIMIT :limit")
    suspend fun messagesByDirectionOnce(dir: Int, limit: Int = 500): List<MessageEntry>

    @Query("SELECT * FROM messages WHERE appPackage = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun messagesByAppOnce(pkg: String, limit: Int = 500): List<MessageEntry>

    @Query("SELECT * FROM messages WHERE text LIKE :keyword ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchMessages(keyword: String, limit: Int = 500): List<MessageEntry>

    @Query("SELECT * FROM messages WHERE text LIKE :keyword ORDER BY timestamp DESC LIMIT :limit")
    fun searchMessagesFlow(keyword: String, limit: Int = 500): Flow<List<MessageEntry>>

    @Query("SELECT * FROM messages WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC LIMIT :limit")
    suspend fun messagesByDateRange(startTime: Long, endTime: Long, limit: Int = 500): List<MessageEntry>

    @Query("SELECT * FROM messages WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC LIMIT :limit")
    fun messagesByDateRangeFlow(startTime: Long, endTime: Long, limit: Int = 500): Flow<List<MessageEntry>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentMessagesOnce(limit: Int = 500): List<MessageEntry>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE direction = :dir")
    suspend fun countByDirection(dir: Int): Int

    @Query("SELECT COUNT(*) FROM messages WHERE chatType = :chatType")
    suspend fun countByChatType(chatType: Int): Int

    data class AppCount(
        val appPackage: String,
        val appName: String,
        val count: Int
    )

    @Query("SELECT appPackage, appName, COUNT(*) as count FROM messages GROUP BY appPackage, appName ORDER BY count DESC")
    suspend fun appCounts(): List<AppCount>

    @Query("SELECT appPackage, appName, COUNT(*) as count FROM messages GROUP BY appPackage, appName ORDER BY appName ASC")
    suspend fun distinctApps(): List<AppCount>

    @Query("SELECT appPackage, appName, COUNT(*) as count FROM messages GROUP BY appPackage, appName ORDER BY appName ASC")
    fun distinctAppsFlow(): Flow<List<AppCount>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun allMessagesAsc(): Flow<List<MessageEntry>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun allMessagesDesc(): Flow<List<MessageEntry>>

    @Query("SELECT DISTINCT contactOrGroup FROM messages WHERE contactOrGroup != '' ORDER BY contactOrGroup ASC")
    suspend fun distinctContacts(): List<String>

    @Query("SELECT contactOrGroup, COUNT(*) as count FROM messages WHERE contactOrGroup != '' GROUP BY contactOrGroup ORDER BY count DESC")
    suspend fun contactCounts(): List<ContactCount>

    data class ContactCount(
        val contactOrGroup: String,
        val count: Int
    )

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    @Query("DELETE FROM messages WHERE appPackage = :pkg")
    suspend fun clearByApp(pkg: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun clearBefore(before: Long)

    // === 对话上下文相关 ===

    /**
     * 获取某个应用+联系人的完整对话上下文（按时间正序）
     */
    @Query("SELECT * FROM messages WHERE appPackage = :pkg ORDER BY timestamp ASC LIMIT :limit")
    suspend fun conversationByApp(pkg: String, limit: Int = 1000): List<MessageEntry>

    /**
     * 获取某个应用+联系人的完整对话上下文（按时间正序）
     */
    @Query("SELECT * FROM messages WHERE appPackage = :pkg AND contactOrGroup = :contact ORDER BY timestamp ASC LIMIT :limit")
    suspend fun conversationByContact(pkg: String, contact: String, limit: Int = 1000): List<MessageEntry>

    /**
     * 获取所有不同的 (appPackage, contactOrGroup) 组合
     */
    data class ConversationKey(
        val appPackage: String,
        val appName: String,
        val contactOrGroup: String,
        val count: Int
    )

    @Query("SELECT appPackage, appName, contactOrGroup, COUNT(*) as count FROM messages GROUP BY appPackage, appName, contactOrGroup ORDER BY MAX(timestamp) DESC")
    suspend fun conversationKeys(): List<ConversationKey>

    @Query("SELECT appPackage, appName, contactOrGroup, COUNT(*) as count FROM messages GROUP BY appPackage, appName, contactOrGroup ORDER BY MAX(timestamp) DESC")
    fun conversationKeysFlow(): Flow<List<ConversationKey>>
}
