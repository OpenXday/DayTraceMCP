package com.daytrace.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daytrace.DayTraceApp
import com.daytrace.data.McpLogDao
import com.daytrace.data.McpLogEntry
import com.daytrace.server.McpServerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * MCP 调用日志页面
 *
 * 三层展示：
 * 1. 概览卡片：总调用数 / 成功率 / 活跃会话 / 最近调用
 * 2. 过滤栏：按方法/成功失败/工具名过滤 + 分页加载
 * 3. 调用时序详情：点击展开查看完整请求-响应时序
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpLogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { (context.applicationContext as DayTraceApp).database.mcpLogDao() }

    // 过滤状态
    var filterMethod by remember { mutableStateOf<String?>(null) }
    var filterSuccess by remember { mutableStateOf<Boolean?>(null) }
    var filterTool by remember { mutableStateOf<String?>(null) }
    var selectedLog by remember { mutableStateOf<McpLogEntry?>(null) }

    // 统计数据
    var totalCount by remember { mutableStateOf(0) }
    var successCount by remember { mutableStateOf(0) }
    var failedCount by remember { mutableStateOf(0) }
    var methodCounts by remember { mutableStateOf<List<McpLogDao.MethodCount>>(emptyList()) }
    var toolCounts by remember { mutableStateOf<List<McpLogDao.ToolCount>>(emptyList()) }

    // 分页
    val pageSize = 50
    var currentPage by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }

    // 自动刷新 tick（每 3 秒递增，触发日志列表 Flow 重新收集）
    var refreshTick by remember { mutableStateOf(0) }

    // 日志列表（使用 Flow 收集，refreshTick 触发自动刷新）
    val logsFlow = remember(filterMethod, filterSuccess, filterTool, currentPage, refreshTick) {
        val offset = currentPage * pageSize
        when {
            filterMethod != null && filterSuccess != null ->
                dao.getPageByMethodFlow(filterMethod!!, pageSize, offset)
            filterSuccess != null ->
                dao.getPageBySuccessFlow(filterSuccess!!, pageSize, offset)
            filterTool != null ->
                dao.getPageByToolFlow(filterTool!!, pageSize, offset)
            else ->
                dao.getPageFlow(pageSize, offset)
        }
    }
    val logs by logsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // 统计数据刷新 + 自动轮询（每 3 秒自动刷新统计与列表）
    LaunchedEffect(filterMethod, filterSuccess, filterTool) {
        while (true) {
            try {
                totalCount = dao.count()
                successCount = dao.countSuccess()
                failedCount = dao.countFailed()
                methodCounts = dao.countByMethod()
                toolCounts = dao.countByTool()
            } catch (e: Exception) {
                // 忽略
            }
            refreshTick++  // 触发日志列表 Flow 重新收集
            delay(3000)
        }
    }

    // 检查是否还有更多数据
    LaunchedEffect(logs) {
        hasMore = logs.size >= pageSize
    }

    val mcpRunning = McpServerService.isRunning
    val mcpPort = McpServerService.mcpPort

    // 详情弹窗
    if (selectedLog != null) {
        McpLogDetailDialog(
            log = selectedLog!!,
            onDismiss = { selectedLog = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MCP 调用日志", fontWeight = FontWeight.Bold)
                        Text(
                            if (mcpRunning) "MCP Server :$mcpPort 运行中"
                            else "MCP Server 未运行",
                            fontSize = 11.sp,
                            color = if (mcpRunning) Color(0xFFA6E3A1) else Color(0xFF6C7086)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        currentPage = 0
                        filterMethod = null
                        filterSuccess = null
                        filterTool = null
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    // 清空日志
                    IconButton(onClick = {
                        scope.launch {
                            dao.clearAll()
                            currentPage = 0
                            Toast.makeText(context, "已清空日志", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空", tint = Color(0xFFF38BA8))
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
            // === 概览卡片 ===
            McpLogOverviewCard(
                totalCount = totalCount,
                successCount = successCount,
                failedCount = failedCount,
                mcpRunning = mcpRunning
            )

            // === 过滤栏 ===
            McpLogFilterBar(
                filterMethod = filterMethod,
                filterSuccess = filterSuccess,
                filterTool = filterTool,
                methodCounts = methodCounts,
                toolCounts = toolCounts,
                onMethodChange = { filterMethod = it; currentPage = 0 },
                onSuccessChange = { filterSuccess = it; currentPage = 0 },
                onToolChange = { filterTool = it; currentPage = 0 }
            )

            // === 当前过滤状态 ===
            val hasFilter = filterMethod != null || filterSuccess != null || filterTool != null
            if (hasFilter) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已过滤: ", fontSize = 11.sp, color = Color(0xFF6C7086))
                    val parts = mutableListOf<String>()
                    filterMethod?.let { parts.add("方法=$it") }
                    filterSuccess?.let { parts.add(if (it) "成功" else "失败") }
                    filterTool?.let { parts.add("工具=$it") }
                    Text(parts.joinToString(", "), fontSize = 11.sp, color = Color(0xFF89B4FA))
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        filterMethod = null
                        filterSuccess = null
                        filterTool = null
                        currentPage = 0
                    }) { Text("清除过滤", fontSize = 11.sp) }
                }
            }

            // === 日志列表 ===
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (totalCount == 0) "暂无 MCP 调用日志\n启用 MCP Server 后，所有调用将自动记录"
                        else "当前过滤条件下无记录",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs, key = { it.id }) { log ->
                        McpLogItem(log = log) { selectedLog = log }
                    }

                    // 分页加载更多
                    if (hasMore) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable { currentPage++ },
                                color = Color(0xFF1E1E2E),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "加载更多 (第 ${currentPage + 1} 页)",
                                        fontSize = 12.sp,
                                        color = Color(0xFF89B4FA)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "共 ${currentPage * pageSize + logs.size} 条（总计 $totalCount 条）",
                                fontSize = 10.sp,
                                color = Color(0xFF6C7086)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========================
// 概览卡片
// ========================

@Composable
fun McpLogOverviewCard(
    totalCount: Int,
    successCount: Int,
    failedCount: Int,
    mcpRunning: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("调用概览", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                // MCP Server 状态指示
                Surface(
                    color = if (mcpRunning) Color(0xFF1E2E1E) else Color(0xFF2E1E1E),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (mcpRunning) "● 运行中" else "○ 已停止",
                        fontSize = 9.sp,
                        color = if (mcpRunning) Color(0xFFA6E3A1) else Color(0xFFF38BA8),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatItem("$totalCount", "总调用", Color(0xFFF9E2AF))
                StatItem("$successCount", "成功", Color(0xFFA6E3A1))
                StatItem("$failedCount", "失败", Color(0xFFF38BA8))
                val rate = if (totalCount > 0) "${(successCount * 100 / totalCount)}%" else "—"
                StatItem(rate, "成功率", Color(0xFF89B4FA))
            }
        }
    }
}

@Composable
fun StatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = Color.Gray)
    }
}

// ========================
// 过滤栏
// ========================

@Composable
fun McpLogFilterBar(
    filterMethod: String?,
    filterSuccess: Boolean?,
    filterTool: String?,
    methodCounts: List<McpLogDao.MethodCount>,
    toolCounts: List<McpLogDao.ToolCount>,
    onMethodChange: (String?) -> Unit,
    onSuccessChange: (Boolean?) -> Unit,
    onToolChange: (String?) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 全部
        FilterChip(
            selected = filterMethod == null && filterSuccess == null && filterTool == null,
            onClick = {
                onMethodChange(null)
                onSuccessChange(null)
                onToolChange(null)
            },
            label = { Text("全部", fontSize = 10.sp) }
        )

        // 成功/失败
        FilterChip(
            selected = filterSuccess == true,
            onClick = { onSuccessChange(if (filterSuccess == true) null else true) },
            label = { Text("✓ 成功", fontSize = 10.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF1E2E1E)
            )
        )
        FilterChip(
            selected = filterSuccess == false,
            onClick = { onSuccessChange(if (filterSuccess == false) null else false) },
            label = { Text("✗ 失败", fontSize = 10.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF2E1E1E)
            )
        )

        // 按方法（空方法名显示为 None）
        methodCounts.take(6).forEach { mc ->
            val displayMethod = mc.rpcMethod.ifEmpty { "None" }
            FilterChip(
                selected = filterMethod == mc.rpcMethod,
                onClick = { onMethodChange(if (filterMethod == mc.rpcMethod) null else mc.rpcMethod) },
                label = { Text("$displayMethod (${mc.count})", fontSize = 10.sp) }
            )
        }

        // 按工具
        if (toolCounts.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            toolCounts.take(5).forEach { tc ->
                FilterChip(
                    selected = filterTool == tc.toolName,
                    onClick = { onToolChange(if (filterTool == tc.toolName) null else tc.toolName) },
                    label = { Text("🔧 ${tc.toolName} (${tc.count})", fontSize = 10.sp) }
                )
            }
        }
    }
}

// ========================
// 日志条目卡片
// ========================

@Composable
fun McpLogItem(log: McpLogEntry, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA) }
    val timeStr = remember(log.id) { df.format(Date(log.timestamp)) }

    val successColor = if (log.success) Color(0xFFA6E3A1) else Color(0xFFF38BA8)
    val methodColor = when {
        log.rpcMethod == "initialize" -> Color(0xFF89B4FA)
        log.rpcMethod == "tools/call" -> Color(0xFFF9E2AF)
        log.rpcMethod == "tools/list" -> Color(0xFFFAB387)
        log.rpcMethod == "ping" -> Color(0xFF6C7086)
        log.rpcMethod == "session_delete" -> Color(0xFFF38BA8)
        log.rpcMethod == "sse_stream" -> Color(0xFF89D0FF)
        log.rpcMethod == "health_check" -> Color(0xFFA6E3A1)
        log.rpcMethod == "batch" -> Color(0xFFCBA6F7)
        else -> Color(0xFF6C7086)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态点
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(successColor)
                )
                Spacer(modifier = Modifier.width(6.dp))

                // 方法标签（空方法名显示 — 表示未进入 RPC 路由）
                val displayMethod = log.rpcMethod.ifEmpty { "—" }
                Surface(
                    color = methodColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        displayMethod,
                        fontSize = 9.sp,
                        color = methodColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                // 工具名
                if (log.toolName.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = Color(0xFF313244),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            log.toolName,
                            fontSize = 9.sp,
                            color = Color(0xFF89B4FA),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 耗时
                Text(
                    "${log.durationMs}ms",
                    fontSize = 10.sp,
                    color = Color(0xFF6C7086),
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeStr,
                    fontSize = 10.sp,
                    color = Color(0xFF6C7086),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))

                // HTTP 方法 + 路径
                Text(
                    "${log.httpMethod} ${log.httpPath}",
                    fontSize = 9.sp,
                    color = Color(0xFF45475A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.weight(1f))

                // 客户端 IP
                if (log.clientIp != "unknown" && log.clientIp.isNotEmpty()) {
                    Text(
                        log.clientIp,
                        fontSize = 9.sp,
                        color = Color(0xFF45475A),
                        maxLines = 1
                    )
                }
            }

            // 错误信息
            if (!log.success && log.errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    log.errorMessage,
                    fontSize = 10.sp,
                    color = Color(0xFFF38BA8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 认证结果
            if (log.authResult != "not_required" && log.authResult != "not_checked") {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Auth: ${log.authResult}",
                    fontSize = 8.sp,
                    color = if (log.authResult == "ok") Color(0xFFA6E3A1) else Color(0xFFF38BA8)
                )
            }

            // 会话 ID 前缀
            if (log.sessionId.isNotEmpty()) {
                Text(
                    "Session: ${log.sessionId.take(8)}…",
                    fontSize = 8.sp,
                    color = Color(0xFF45475A),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ========================
// 调用时序详情弹窗
// ========================

@Composable
fun McpLogDetailDialog(log: McpLogEntry, onDismiss: () -> Unit) {
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (log.success) Color(0xFFA6E3A1) else Color(0xFFF38BA8))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("调用详情", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // === 调用时序 ===
                SectionHeader("调用时序")

                TimelineRow(
                    time = df.format(Date(log.timestamp)),
                    label = "请求到达",
                    detail = "${log.httpMethod} ${log.httpPath}",
                    color = Color(0xFF89B4FA)
                )

                if (log.authResult != "not_required" && log.authResult != "not_checked") {
                    TimelineRow(
                        time = "",
                        label = "认证检查",
                        detail = log.authResult,
                        color = if (log.authResult == "ok") Color(0xFFA6E3A1) else Color(0xFFF38BA8)
                    )
                }

                if (log.rpcMethod.isNotEmpty()) {
                    TimelineRow(
                        time = "",
                        label = "方法分发",
                        detail = log.rpcMethod + if (log.toolName.isNotEmpty()) " → ${log.toolName}" else "",
                        color = Color(0xFFF9E2AF)
                    )
                }

                if (log.sessionId.isNotEmpty()) {
                    TimelineRow(
                        time = "",
                        label = "会话",
                        detail = log.sessionId.take(16) + if (log.sessionId.length > 16) "…" else "",
                        color = Color(0xFF6C7086)
                    )
                }

                TimelineRow(
                    time = df.format(Date(log.timestamp + log.durationMs)),
                    label = "响应返回",
                    detail = "HTTP ${log.responseCode} · ${log.durationMs}ms · ${if (log.success) "成功" else "失败"}",
                    color = if (log.success) Color(0xFFA6E3A1) else Color(0xFFF38BA8),
                    isLast = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // === 请求信息 ===
                SectionHeader("请求信息")
                DetailRow("时间", df.format(Date(log.timestamp)))
                DetailRow("HTTP 方法", log.httpMethod)
                DetailRow("路径", log.httpPath)
                DetailRow("客户端 IP", log.clientIp)
                if (log.rpcId.isNotEmpty()) DetailRow("JSON-RPC ID", log.rpcId)
                if (log.rpcMethod.isNotEmpty()) DetailRow("RPC 方法", log.rpcMethod)
                if (log.toolName.isNotEmpty()) DetailRow("工具名", log.toolName)
                if (log.sessionId.isNotEmpty()) DetailRow("会话 ID", log.sessionId)
                DetailRow("认证结果", log.authResult)

                // === 请求 Body ===
                if (log.requestBody.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader("请求 Body")
                    JsonBlock(log.requestBody)
                }

                // === 响应 Body ===
                if (log.responseBody.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader("响应 Body")
                    JsonBlock(log.responseBody)
                }

                // === 错误信息 ===
                if (!log.success && log.errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader("错误信息")
                    Surface(
                        color = Color(0xFF2A1E1E),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            log.errorMessage,
                            fontSize = 11.sp,
                            color = Color(0xFFF38BA8),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // === 异常堆栈 ===
                if (log.errorStack.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader("异常堆栈")
                    Surface(
                        color = Color(0xFF181825),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            log.errorStack,
                            fontSize = 9.sp,
                            color = Color(0xFF6C7086),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFCDD6F4)
    )
}

@Composable
fun TimelineRow(
    time: String,
    label: String,
    detail: String,
    color: Color,
    isLast: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧时间轴
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(60.dp)
        ) {
            if (time.isNotEmpty()) {
                Text(time, fontSize = 8.sp, color = Color(0xFF6C7086), fontFamily = FontFamily.Monospace)
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(Color(0xFF313244))
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右侧内容
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
            Text(
                detail,
                fontSize = 10.sp,
                color = Color(0xFF9399B2),
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6C7086))
        Text(
            value,
            fontSize = 10.sp,
            color = Color(0xFFCDD6F4),
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
fun JsonBlock(rawJson: String) {
    // 尝试美化 JSON
    val prettyJson = remember(rawJson) {
        try {
            val json = kotlinx.serialization.json.Json { prettyPrint = true }
            val parsed = json.parseToJsonElement(rawJson)
            json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                parsed
            )
        } catch (e: Exception) {
            rawJson
        }
    }

    Surface(
        color = Color(0xFF181825),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            prettyJson,
            fontSize = 9.sp,
            color = Color(0xFFCDD6F4),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
            maxLines = 15
        )
    }
}
