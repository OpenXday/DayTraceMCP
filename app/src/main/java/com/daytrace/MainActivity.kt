package com.daytrace

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.daytrace.data.MessageEntry
import com.daytrace.service.CaptureAccessibilityService
import com.daytrace.service.NotificationCaptureService
import com.daytrace.ui.HistoryScreen
import com.daytrace.ui.McpLogScreen
import com.daytrace.ui.SettingsScreen
import com.daytrace.util.Prefs
import com.daytrace.util.ShizukuHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 运行时控制是否从最近任务列表隐藏
        applyRecentsVisibility()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DayTraceRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台时重新检查设置（用户可能在设置页改了）
        applyRecentsVisibility()
    }

    /**
     * 根据 Prefs 设置控制 Activity 是否在最近任务列表中可见
     *
     * 使用 ActivityManager.AppTask 的 excludeFromRecents，或者
     * 通过 setTaskDefaultValues 修改（不同 API level 略有不同）
     *
     * 简单实现：如果用户开启了隐藏，则标记 excludeFromRecents
     * 这个 flag 会在下次进入最近任务时生效
     */
    private fun applyRecentsVisibility() {
        try {
            val hide = Prefs.isHideFromRecents(this)
            // 使用 ActivityManager.AppTask 来控制
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.appTasks
            for (task in tasks) {
                if (task.taskInfo?.taskId == taskId) {
                    // setExcludeFromRecents 需要 API 23+
                    task.setExcludeFromRecents(hide)
                    break
                }
            }
        } catch (e: Exception) {
            // 忽略：某些设备/ROM 可能不支持
        }
    }
}

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Home : Screen("home", "首页", Icons.Default.Home)
    data object History : Screen("history", "时间线", Icons.Default.Schedule)
    data object McpLog : Screen("mcplog", "MCP日志", Icons.Default.Analytics)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayTraceRoot() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Home, Screen.History, Screen.McpLog, Screen.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日迹 DayTrace", fontWeight = FontWeight.Bold) },
                actions = {
                    val context = LocalContext.current
                    IconButton(onClick = {
                        Toast.makeText(context, "状态已刷新", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, fontSize = 10.sp) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.McpLog.route) { McpLogScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { (context.applicationContext as DayTraceApp).database.messageDao() }
    val messages by db.recentMessages(100).collectAsStateWithLifecycle(initialValue = emptyList())

    var a11yEnabled by remember { mutableStateOf(false) }
    var notifEnabled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuAuthed by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(false) }
    var captureEnabled by remember { mutableStateOf(Prefs.isCaptureEnabled(context)) }
    var grantInProgress by remember { mutableStateOf(false) }

    fun refreshStatus() {
        a11yEnabled = ShizukuHelper.isAccessibilityEnabled(context, CaptureAccessibilityService::class.java)
        notifEnabled = ShizukuHelper.isNotificationListenerEnabled(context)
        shizukuRunning = ShizukuHelper.isShizukuRunning()
        shizukuAuthed = ShizukuHelper.hasPermission()
        cameraGranted = ShizukuHelper.isCameraGranted(context)
        micGranted = ShizukuHelper.isMicGranted(context)
    }

    // === Auto refresh ===
    // 1. on screen enter
    LaunchedEffect(Unit) { refreshStatus() }

    // 2. after grant, poll until services come online
    LaunchedEffect(grantInProgress) {
        if (grantInProgress) {
            var elapsed = 0
            while (grantInProgress && elapsed < 15000) {
                delay(500)
                refreshStatus()
                elapsed += 500
                if (a11yEnabled && notifEnabled && cameraGranted && micGranted) {
                    grantInProgress = false
                    Toast.makeText(context, "权限已生效", Toast.LENGTH_SHORT).show()
                    break
                }
            }
            grantInProgress = false
        }
    }

    // 3. periodic poll every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            refreshStatus()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // === Status panel ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("服务状态", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { refreshStatus() }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "手动刷新",
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF89B4FA)
                            )
                        }
                    }
                    StatusRow("无障碍服务（时间线记录）", a11yEnabled)
                    StatusRow("通知监听（消息记录）", notifEnabled)
                    StatusRow("Shizuku 运行中", shizukuRunning)
                    StatusRow("Shizuku 已授权", shizukuAuthed)
                    HorizontalDivider(color = Color(0xFF333344))
                    StatusRow("相机权限（远程拍照/录像）", cameraGranted)
                    StatusRow("麦克风权限（远程录像/录音）", micGranted)
                    HorizontalDivider(color = Color(0xFF333344))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("采集开关", modifier = Modifier.weight(1f))
                        Switch(
                            checked = captureEnabled,
                            onCheckedChange = {
                                captureEnabled = it
                                Prefs.setCaptureEnabled(context, it)
                            }
                        )
                    }
                }
            }
        }

        // === Grant panel ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("一键授权（通过 Shizuku）", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "需要手机已安装 Shizuku 并通过无线调试/ADB 启动",
                        fontSize = 12.sp, color = Color.Gray
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ShizukuHelper.requestPermission()
                                Toast.makeText(context, "请在弹窗中授权 Shizuku", Toast.LENGTH_SHORT).show()
                            },
                            enabled = shizukuRunning && !shizukuAuthed
                        ) { Text("授权 Shizuku") }

                        Button(
                            onClick = {
                                grantInProgress = true
                                scope.launch {
                                    val r1 = ShizukuHelper.enableAccessibilityService(context, CaptureAccessibilityService::class.java)
                                    val r2 = ShizukuHelper.enableNotificationListener(context, NotificationCaptureService::class.java)
                                    // 同时授予相机和录音权限
                                    val r3 = ShizukuHelper.grantCameraAndMicPermissions(context)
                                    if (r1.isSuccess && r2.isSuccess && r3.isSuccess) {
                                        Toast.makeText(context, "授权成功，正在刷新...", Toast.LENGTH_SHORT).show()
                                    } else if (r1.isFailure && r1.exceptionOrNull() is ShizukuHelper.AccessibilityAutoEnableException) {
                                        // 无障碍自动开启失败，引导用户手动开启
                                        Toast.makeText(context, "通知监听已开启。无障碍需手动开启，即将跳转...", Toast.LENGTH_LONG).show()
                                        grantInProgress = false
                                        // 延迟跳转到无障碍设置
                                        kotlinx.coroutines.delay(1500)
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } else {
                                        val err = r1.exceptionOrNull()?.message ?: r2.exceptionOrNull()?.message ?: r3.exceptionOrNull()?.message ?: "未知错误"
                                        Toast.makeText(context, "失败: $err", Toast.LENGTH_LONG).show()
                                        grantInProgress = false
                                    }
                                    refreshStatus()
                                }
                            },
                            enabled = shizukuAuthed && !grantInProgress
                        ) {
                            if (grantInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("等待生效...")
                            } else {
                                Text("一键开启所有权限")
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF333344))
                    Text("手动开启（备选）", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("无障碍设置") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("通知访问设置") }
                    }
                }
            }
        }

        // === Stats ===
        item {
            val outgoingCount = messages.count { it.direction == MessageEntry.DIR_OUTGOING }
            val incomingCount = messages.count { it.direction == MessageEntry.DIR_INCOMING }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$outgoingCount", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF89B4FA))
                        Text("自己发出", fontSize = 12.sp, color = Color.Gray)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$incomingCount", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA6E3A1))
                        Text("对方发来", fontSize = 12.sp, color = Color.Gray)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${messages.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF9E2AF))
                        Text("总计", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }

                // === Recent activity ===
        item {
            Text(
                "最近活动（最新100条，更多请查看时间线页）",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        if (messages.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
                ) {
                    Box(modifier = Modifier.padding(32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("暂无记录\n开启权限后开始记录你的时间线", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }

        items(messages) { msg ->
            MessageCard(msg)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
fun StatusRow(label: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (enabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (enabled) Color(0xFFA6E3A1) else Color(0xFFF38BA8),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            if (enabled) "已开启" else "未开启",
            fontSize = 12.sp,
            color = if (enabled) Color(0xFFA6E3A1) else Color(0xFFF38BA8)
        )
    }
}

@Composable
fun MessageCard(msg: MessageEntry) {
    val isOutgoing = msg.direction == MessageEntry.DIR_OUTGOING
    val bgColor = if (isOutgoing) Color(0xFF1A2B3C) else Color(0xFF1E2E1E)
    val accentColor = if (isOutgoing) Color(0xFF89B4FA) else Color(0xFFA6E3A1)
    val directionLabel = if (isOutgoing) "↑ 发出" else "↓ 收到"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(directionLabel, fontSize = 11.sp, color = accentColor, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Text(msg.appName, fontSize = 11.sp, color = Color.Gray)
                if (msg.contactOrGroup.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("· ${msg.contactOrGroup}", fontSize = 11.sp, color = Color.Gray)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(java.util.Date(msg.timestamp)),
                    fontSize = 10.sp, color = Color(0xFF666666)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                msg.text,
                fontSize = 14.sp,
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text("via ${msg.source}", fontSize = 9.sp, color = Color(0xFF444444))
        }
    }
}
