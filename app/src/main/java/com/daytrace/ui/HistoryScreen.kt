package com.daytrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * 时间线页面 —— 日历选择 + 时间轴展示
 *
 * - 顶部日历组件可展开/收起，默认收起，显示当前选中日期
 * - 点击日历按钮展开月历视图，选择某天查看该天时间线
 * - 默认显示当天
 * - 左侧竖线 + 彩色圆点 = 时间轴视觉
 * - 按时段分组（清晨/上午/下午/晚上）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { (context.applicationContext as DayTraceApp).database.messageDao() }

    // 默认选当天
    val todayMillis = remember { getStartOfDay(System.currentTimeMillis()) }
    var selectedDate by remember { mutableStateOf(todayMillis) }
    var showCalendar by remember { mutableStateOf(false) }

    var searchText by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var sortDesc by remember { mutableStateOf(true) }

    // 按选中日期查询
    val endOfDay = remember(selectedDate) { selectedDate + 24 * 60 * 60 * 1000 - 1 }
    val dayMessagesFlow = remember(selectedDate, searchText) {
        if (searchText.isNotEmpty()) {
            dao.messagesByDateRangeFlow(selectedDate, endOfDay, 5000)
        } else {
            dao.messagesByDateRangeFlow(selectedDate, endOfDay, 5000)
        }
    }
    val dayMessages by dayMessagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val stats by dao.distinctAppsFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    // 应用过滤
    val filteredMessages = remember(dayMessages, selectedApp) {
        if (selectedApp != null) dayMessages.filter { it.appPackage == selectedApp } else dayMessages
    }

    // 搜索过滤
    val searchFiltered = remember(filteredMessages, searchText) {
        if (searchText.isEmpty()) filteredMessages
        else filteredMessages.filter { it.text.contains(searchText, ignoreCase = true) }
    }

    // 排序
    val sortedMessages = remember(searchFiltered, sortDesc) {
        if (sortDesc) searchFiltered.sortedByDescending { it.timestamp }
        else searchFiltered.sortedBy { it.timestamp }
    }

    // 按时段分组
    val byPeriod = remember(sortedMessages) {
        sortedMessages.groupBy { getPeriod(it.timestamp) }
    }

    val dateLabel = remember(selectedDate) { formatDateLong(selectedDate) }
    val isToday = remember(selectedDate) { selectedDate == todayMillis }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("时间线", fontWeight = FontWeight.Bold)
                        Text(
                            dateLabel + if (isToday) " · 今天" else "",
                            fontSize = 11.sp,
                            color = Color(0xFF6C7086)
                        )
                    }
                },
                actions = {
                    // 日历切换
                    IconButton(onClick = { showCalendar = !showCalendar }) {
                        Icon(
                            if (showCalendar) Icons.Default.CalendarMonth else Icons.Default.CalendarMonth,
                            contentDescription = "日历",
                            tint = if (showCalendar) Color(0xFF89B4FA) else Color.White
                        )
                    }
                    // 前一天
                    IconButton(onClick = {
                        selectedDate = selectedDate - 24 * 60 * 60 * 1000
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "前一天")
                    }
                    // 后一天（不能超过今天）
                    IconButton(
                        onClick = {
                            val next = selectedDate + 24 * 60 * 60 * 1000
                            if (next <= todayMillis) selectedDate = next
                        },
                        enabled = selectedDate < todayMillis
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "后一天")
                    }
                    // 导出
                    IconButton(onClick = {
                        scope.launch {
                            val path = TimelineExporter.exportDay(context, selectedDate)
                            if (path != null) {
                                android.widget.Toast.makeText(context, "已导出: $path", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                android.widget.Toast.makeText(context, "该日期无数据", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "导出")
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
            // 日历组件（可展开/收起）
            if (showCalendar) {
                CalendarPicker(
                    selectedDate = selectedDate,
                    maxDate = todayMillis,
                    onDateSelected = { date ->
                        selectedDate = date
                        showCalendar = false
                    }
                )
                HorizontalDivider(color = Color(0xFF313244))
            }

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
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${sortedMessages.size} 条记录",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.weight(1f))
                // 排序切换
                IconButton(
                    onClick = { sortDesc = !sortDesc },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        if (sortDesc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = "排序",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF6C7086)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 时间线列表
            if (sortedMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isToday) "今天还没有记录\n开启权限后开始记录"
                        else "该日期无记录",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    byPeriod.forEach { (period, periodMsgs) ->
                        item {
                            PeriodHeader(period)
                        }
                        items(periodMsgs, key = { it.id }) { msg ->
                            TimelineItem(msg, dao, scope)
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ========================
// 日历组件
// ========================

@Composable
fun CalendarPicker(
    selectedDate: Long,
    maxDate: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy年M月", Locale.CHINA) }
    val monthCal = remember(selectedDate) {
        Calendar.getInstance(Locale.CHINA).apply {
            timeInMillis = selectedDate
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    var displayMonth by remember { mutableStateOf(monthCal.timeInMillis) }

    val displayCal = remember(displayMonth) {
        Calendar.getInstance(Locale.CHINA).apply {
            timeInMillis = displayMonth
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }

    val year = displayCal.get(Calendar.YEAR)
    val month = displayCal.get(Calendar.MONTH) // 0-11
    val daysInMonth = displayCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = displayCal.get(Calendar.DAY_OF_WEEK) // 1=Sunday

    // 今天 0:00
    val todayStart = remember { getStartOfDay(System.currentTimeMillis()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 月份导航
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = {
                    displayCal.add(Calendar.MONTH, -1)
                    displayMonth = displayCal.timeInMillis
                }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
                }
                Text(
                    dateFormatter.format(Date(displayMonth)),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFCDD6F4),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(
                    onClick = {
                        // 不能超过当前月
                        val now = Calendar.getInstance(Locale.CHINA)
                        if (displayCal.get(Calendar.YEAR) < now.get(Calendar.YEAR) ||
                            (displayCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                             displayCal.get(Calendar.MONTH) < now.get(Calendar.MONTH))) {
                            displayCal.add(Calendar.MONTH, 1)
                            displayMonth = displayCal.timeInMillis
                        }
                    }
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 星期标题
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { day ->
                    Text(
                        day,
                        fontSize = 12.sp,
                        color = Color(0xFF6C7086),
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 日期网格
            val firstDayOffset = if (firstDayOfWeek == Calendar.SUNDAY) 0 else firstDayOfWeek - 1
            val totalCells = firstDayOffset + daysInMonth
            val rows = (totalCells + 6) / 7 // 向上取整

            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val cellIndex = row * 7 + col
                        val dayNum = cellIndex - firstDayOffset + 1

                        if (dayNum in 1..daysInMonth) {
                            val cellCal = Calendar.getInstance(Locale.CHINA).apply {
                                timeInMillis = displayCal.timeInMillis
                                set(Calendar.DAY_OF_MONTH, dayNum)
                            }
                            val cellDate = getStartOfDay(cellCal.timeInMillis)
                            val isSelected = cellDate == selectedDate
                            val isToday = cellDate == todayStart
                            val isFuture = cellDate > todayStart

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clickable(enabled = !isFuture) {
                                        onDateSelected(cellDate)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = when {
                                        isSelected -> Color(0xFF89B4FA)
                                        isToday -> Color(0xFF313244)
                                        else -> Color.Transparent
                                    }
                                ) {
                                    Text(
                                        "$dayNum",
                                        fontSize = 13.sp,
                                        color = when {
                                            isFuture -> Color(0xFF45475A)
                                            isSelected -> Color.White
                                            isToday -> Color(0xFF89B4FA)
                                            else -> Color(0xFFCDD6F4)
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f).height(40.dp))
                        }
                    }
                }
            }
        }
    }
}

// ========================
// 子组件
// ========================

@Composable
fun PeriodHeader(period: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFF313244),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                period,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFA6ADC8),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF313244))
    }
}

@Composable
fun TimelineItem(msg: MessageEntry, dao: MessageDao, scope: kotlinx.coroutines.CoroutineScope) {
    var showDelete by remember { mutableStateOf(false) }

    val isOutgoing = msg.direction == MessageEntry.DIR_OUTGOING
    val dotColor = when {
        msg.source.startsWith("input") -> Color(0xFFF38BA8)   // 输入 = 粉红
        msg.source == "notification" -> Color(0xFFA6E3A1)    // 通知 = 绿
        msg.source == "window_change" -> Color(0xFF89B4FA)   // 切换 = 蓝
        msg.source == "snapshot" -> Color(0xFFF9E2AF)         // 快照 = 黄
        msg.source == "switch_snapshot" -> Color(0xFF89D0FF)  // 切换快照 = 亮蓝
        msg.source == "app_usage" -> Color(0xFFFAB387)       // 时长 = 橙
        msg.source.startsWith("system") -> Color(0xFFF38BA8) // 系统 = 粉红
        else -> Color(0xFF6C7086)
    }

    val sourceLabel = when {
        msg.source == "input" -> "输入"
        msg.source == "input_send" -> "发送"
        msg.source == "input_switch" -> "输入→切换"
        msg.source == "window_change" -> "切换"
        msg.source == "snapshot" -> "快照"
        msg.source == "switch_snapshot" -> "切换快照"
        msg.source == "app_usage" -> "时长"
        msg.source == "notification" -> "通知"
        msg.source == "system" -> "系统"
        msg.source == "system_event" -> "事件"
        else -> msg.source
    }

    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(msg.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // 左侧时间轴
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                timeStr,
                fontSize = 9.sp,
                color = Color(0xFF6C7086),
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = dotColor, radius = 5f)
            }
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    // 删除按钮暂时隐藏
                    // if (showDelete) {
                    //     Spacer(modifier = Modifier.width(4.dp))
                    //     TextButton(
                    //         onClick = { scope.launch { dao.deleteById(msg.id) } },
                    //         contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    //     ) {
                    //         Text("删除", fontSize = 9.sp, color = Color(0xFFF38BA8))
                    //     }
                    // }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    msg.text,
                    fontSize = 13.sp,
                    color = Color(0xFFCDD6F4),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ========================
// 工具函数
// ========================

private fun getStartOfDay(timestamp: Long): Long {
    val cal = Calendar.getInstance(Locale.CHINA)
    cal.timeInMillis = timestamp
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun formatDateLong(timestamp: Long): String {
    val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = timestamp }
    val now = Calendar.getInstance(Locale.CHINA)
    val yesterday = Calendar.getInstance(Locale.CHINA).apply { add(Calendar.DAY_OF_YEAR, -1) }

    val isToday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    val isYesterday = yesterday.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)

    return when {
        isToday -> "今天 " + SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(timestamp))
        isYesterday -> "昨天 " + SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(timestamp))
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(timestamp))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timestamp))
    }
}

private fun getPeriod(timestamp: Long): String {
    val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = timestamp }
    return when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 0..5 -> "清晨 00:00 — 05:59"
        in 6..11 -> "上午 06:00 — 11:59"
        in 12..17 -> "下午 12:00 — 17:59"
        else -> "晚上 18:00 — 23:59"
    }
}
