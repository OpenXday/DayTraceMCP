package com.daytrace.ui

/**
 * 消息分组模式
 */
enum class GroupMode(val label: String) {
    NONE("不分组"),
    BY_APP("按应用分组"),
    BY_DATE("按日期分组"),
    BY_CONTACT("按联系人/群分组"),
    BY_CONVERSATION("对话上下文")
}

/**
 * 排序模式
 */
enum class SortMode(val label: String) {
    DESC("最新优先"),
    ASC("最早优先")
}

/**
 * 方向过滤
 */
enum class DirectionFilter(val label: String) {
    ALL("全部"),
    OUTGOING("发出"),
    INCOMING("收到")
}

/**
 * 聊天类型过滤
 */
enum class ChatTypeFilter(val label: String) {
    ALL("全部"),
    PRIVATE("私聊"),
    GROUP("群聊")
}
