package com.daytrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daytrace.DayTraceApp
import com.daytrace.data.MessageEntry
import com.daytrace.data.MessageDao
import com.daytrace.export.TimelineExporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 时间线页面 —— 以时间轴形式展示每日活动轨迹
 *
 * 设计理念：
 * - 左侧竖线 + 圆点 = 时间轴视觉
 * - 按时段分组（清晨/上午/下午/晚上）
 * - 每条记录显示时间、应用、来源、内容
 * - 支持搜索、应用过滤、导出 Markdown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { (context.applicationContext as DayTraceApp).database.messageDao() }

    var searchText by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var sortDesc by remember { mutableStateOf(true) }

    val baseFlow = when {
        searchText.isNotEmpty() -> dao.searchMessagesFlow("%$searchText%", 1000)
        sortDesc -> dao.allMessagesDesc()
        else -> dao.allMessagesAsc()
    }

    val allMessages by baseFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val stats by dao.distinctAppsFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    // 应用过滤
    val filteredMessages = remember(allMessages, selectedApp) {
        if (selectedApp != null) allMessages.filter { it.appPackage == selectedApp } else allMessages
    }

    // 按日期分组
    val byDate = remember(filteredMessages) {
        filteredMessages.groupBy { formatDateGroup(it.timestamp) }
            .toList()
            .let { if (sortDesc) it.sortedByDescending { (dateStr, _) -> dateStr } else it.sortedBy { (dateStr, _) -> dateStr } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("时间线", fontWeight = FontWeight.Bold) },
                actions = {
                    // 导出按钮
                    IconButton(onClick = {
                        scope.launch {
                            val path = TimelineExporter.exportToday(context)
                            if (path != null) {
                                android.widget.Toast.makeText(context, "已导出: $path", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                android.widget.Toast.makeText(context, "当天无数据", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "导出Markdown")
                    }
                    // 排序切换
                    IconButton(onClick = { sortDesc = !sortDesc }) {
                        Icon(
                            if (sortDesc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = "排序"
                        )
                    }
                    // 一键清理
                    IconButton(onClick = {
                        scope.launch { dao.clearAll() }
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索框
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("搜索时间线内容...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E1E2E),
                    unfocusedContainerColor = Color(0xFF181828)
                )
            )

            // App 快捷过滤
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = selectedApp == null,
                        onClick = { selectedApp = null },
                        label = { Text("全部", fontSize = 11.sp) }
                    )
                }
                items(stats) { appInfo ->
                    FilterChip(
                        selected = selectedApp == appInfo.appPackage,
                        onClick = { selectedApp = appInfo.appPackage },
                        label = { Text("${appInfo.appName} (${appInfo.count})", fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 统计信息
            Text(
                "共 ${filteredMessages.size} 条记录",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 时间线列表
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                byDate.forEach { (dateLabel, dayMessages) ->
                    // 日期分隔标题
                    item {
                        DateHeader(dateLabel, dayMessages.size)
                    }

                    // 按时段分组
                    val byPeriod = dayMessages.groupBy { getPeriod(it.timestamp) }
                    for ((period, periodMsgs) in byPeriod) {
                        // 时段标题
                        item {
                            PeriodHeader(period)
                        }

                        // 时间线条目
                        items(periodMsgs, key = { it.id }) { msg ->
                            TimelineItem(msg, dao, scope)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun DateHeader(dateLabel: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFF313244),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                dateLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFCDD6F4),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF313244))
        Spacer(modifier = Modifier.width(8.dp))
        Text("$count", fontSize = 12.sp, color = Color(0xFF6C7086))
    }
}

@Composable
fun PeriodHeader(period: String) {
    Text(
        period,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF6C7086),
        modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun TimelineItem(msg: MessageEntry, dao: MessageDao, scope: kotlinx.coroutines.CoroutineScope) {
    var showDelete by remember { mutableStateOf(false) }

    val isOutgoing = msg.direction == MessageEntry.DIR_OUTGOING
    val dotColor = when {
        msg.source == "notification" -> Color(0xFFA6E3A1)
        msg.source == "window_change" -> Color(0xFF89B4FA)
        msg.source == "snapshot" -> Color(0xFFF9E2AF)
        msg.source == "app_usage" -> Color(0xFFFAB387)
        msg.source.startsWith("system") -> Color(0xFFF38BA8)
        else -> Color(0xFF6C7086)
    }

    val sourceLabel = when (msg.source) {
        "window_change" -> "打开"
        "snapshot" -> "快照"
        "app_usage" -> "时长"
        "notification" -> "通知"
        "system" -> "系统"
        "system_event" -> "事件"
        else -> msg.source
    }

    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(msg.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDelete = !showDelete }
    ) {
        // 左侧时间轴
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(44.dp)
        ) {
            // 时间
            Text(
                timeStr,
                fontSize = 10.sp,
                color = Color(0xFF6C7086),
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 圆点
            Canvas(
                modifier = Modifier.size(10.dp)
            ) {
                drawCircle(color = dotColor, radius = 5f)
            }
            // 竖线
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(Color(0xFF313244))
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右侧内容卡片
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E2E)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 来源标签
                    Surface(
                        color = dotColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            sourceLabel,
                            fontSize = 9.sp,
                            color = dotColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 应用名
                    Text(
                        msg.appName,
                        fontSize = 11.sp,
                        color = Color(0xFF9399B2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (msg.contactOrGroup.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "· ${msg.contactOrGroup}",
                            fontSize = 10.sp,
                            color = Color(0xFF6C7086),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (showDelete) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = { scope.launch { dao.deleteById(msg.id) } },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("删除", fontSize = 9.sp, color = Color(0xFFF38BA8))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 内容
                Text(
                    msg.text,
                    fontSize = 13.sp,
                    color = Color(0xFFCDD6F4),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatDateGroup(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }

    val today = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)

    val yesterdayCal = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = yesterdayCal.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
        yesterdayCal.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)

    return when {
        today -> "今天"
        isYesterday -> "昨天"
        msg.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(timestamp))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timestamp))
    }
}

private fun getPeriod(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 0..5 -> "清晨"
        in 6..11 -> "上午"
        in 12..17 -> "下午"
        else -> "晚上"
    }
}
