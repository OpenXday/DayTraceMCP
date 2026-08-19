package com.daytrace.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 消息条目 —— 统一存储"自己发的"和"对方发的"消息
 *
 * direction:
 *   OUTGOING = 自己发出的（通过无障碍服务捕获）
 *   INCOMING = 对方发来的（通过通知监听捕获）
 *
 * chatType:
 *   0 = 未知/未分类
 *   1 = 私聊
 *   2 = 群聊
 */
@Serializable
@Entity(tableName = "messages")
data class MessageEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val appPackage: String,          // 包名 com.tencent.mm / com.tencent.mobileqq / org.telegram.messenger ...
    val appName: String,             // 应用显示名
    val direction: Int,              // 0=OUTGOING, 1=INCOMING
    val contactOrGroup: String,      // 联系人/群名（通知监听可拿到；无障碍可能拿不到）
    val text: String,                // 消息文本
    val source: String,              // 来源标记: "accessibility" / "notification"
    val chatType: Int = 0            // 0=未知, 1=私聊, 2=群聊
) {
    companion object {
        const val DIR_OUTGOING = 0
        const val DIR_INCOMING = 1

        const val CHAT_UNKNOWN = 0
        const val CHAT_PRIVATE = 1
        const val CHAT_GROUP = 2
    }
}
