package com.daytrace.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daytrace.export.TimelineExporter
import com.daytrace.net.DiffUploader
import com.daytrace.net.SkillManager
import com.daytrace.net.UploadScheduler
import com.daytrace.net.WebhookSender
import com.daytrace.server.CloudflareTunnelManager
import com.daytrace.server.McpServerService
import com.daytrace.util.Prefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var webhookEnabled by remember { mutableStateOf(Prefs.isWebhookEnabled(context)) }
    var webhookUrl by remember { mutableStateOf(Prefs.getWebhookUrl(context)) }
    var webhookToken by remember { mutableStateOf(Prefs.getWebhookToken(context)) }
    var webhookTesting by remember { mutableStateOf(false) }

    var mcpEnabled by remember { mutableStateOf(Prefs.isMcpEnabled(context)) }
    var mcpPort by remember { mutableStateOf(Prefs.getMcpPort(context).toString()) }

    val mcpRunning = McpServerService.isRunning
    val mcpActualPort = McpServerService.mcpPort

    // Cloudflare Tunnel
    var tunnelEnabled by remember { mutableStateOf(Prefs.isTunnelEnabled(context)) }
    var tunnelUrl by remember { mutableStateOf(CloudflareTunnelManager.tunnelUrl.ifEmpty { Prefs.getTunnelUrl(context) }) }
    var tunnelRunning by remember { mutableStateOf(CloudflareTunnelManager.isRunning) }
    var tunnelStarting by remember { mutableStateOf(false) }
    var binaryReady by remember { mutableStateOf(CloudflareTunnelManager.isBinaryReady(context)) }
    var binaryDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }

    // Skill 驱动上传
    var uploadEnabled by remember { mutableStateOf(Prefs.isUploadEnabled(context)) }
    var selectedSkillId by remember { mutableStateOf(Prefs.getUploadSkillId(context)) }
    var uploadTimes by remember { mutableStateOf(Prefs.getUploadTimes(context).joinToString(", ")) }
    var uploadAutoScheduled by remember { mutableStateOf(Prefs.isUploadAutoScheduled(context)) }
    var uploadTesting by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<DiffUploader.UploadProgress?>(null) }

    // 后台隐藏
    var hideFromRecents by remember { mutableStateOf(Prefs.isHideFromRecents(context)) }

    val allSkills = remember { SkillManager.getAllSkills(context) }
    val selectedSkill = allSkills.find { it.id == selectedSkillId } ?: SkillManager.getDefaultSkill(context)
    val credentials = remember(selectedSkillId) {
        Prefs.getSkillCredentials(context, selectedSkill.id).toMutableMap()
    }

    // Skill 导入
    var showImportDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === 后台可见性 ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("后台设置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (hideFromRecents) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = if (hideFromRecents) Color(0xFFF9E2AF) else Color(0xFFA6E3A1),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("从最近任务隐藏", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(
                            checked = hideFromRecents,
                            onCheckedChange = {
                                hideFromRecents = it
                                Prefs.setHideFromRecents(context, it)
                                val msg = if (it) "已隐藏，下次进入后台后生效" else "已显示"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    Text(
                        "开启后 App 不在最近任务列表显示，防止被误删除。前台通知仍保留。",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
            }

            // === Webhook 设置 ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Webhook 推送", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "时间线摘要模式：手动或通过 MCP 触发当天摘要推送",
                        fontSize = 12.sp, color = Color.Gray
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用 Webhook", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(
                            checked = webhookEnabled,
                            onCheckedChange = {
                                webhookEnabled = it
                                Prefs.setWebhookEnabled(context, it)
                            }
                        )
                    }
                    if (webhookEnabled) {
                        OutlinedTextField(
                            value = webhookUrl,
                            onValueChange = { webhookUrl = it; Prefs.setWebhookUrl(context, it) },
                            label = { Text("Webhook URL") },
                            placeholder = { Text("https://api.chanify.net/v1/sender/") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = webhookToken,
                            onValueChange = { webhookToken = it; Prefs.setWebhookToken(context, it) },
                            label = { Text("Chanify Token（可选）") },
                            placeholder = { Text("CTRxxx...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    webhookTesting = true
                                    scope.launch {
                                        try {
                                            WebhookSender.pushTest(context) { ok, msg ->
                                                webhookTesting = false
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            webhookTesting = false
                                            Toast.makeText(context, "出错: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !webhookTesting && webhookUrl.isNotBlank()
                            ) {
                                if (webhookTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Text("发送测试")
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        WebhookSender.pushDaySummary(context)
                                        Toast.makeText(context, "已推送当天摘要", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = webhookUrl.isNotBlank()
                            ) { Text("推送当天摘要") }
                        }
                    }
                }
            }

            // === MCP Server 设置 ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("MCP Server", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "嵌入式 HTTP JSON-RPC 服务器，让 Claude Desktop 等 AI 工具直接查询你的消息",
                        fontSize = 12.sp, color = Color.Gray
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用 MCP Server", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(
                            checked = mcpEnabled,
                            onCheckedChange = {
                                mcpEnabled = it
                                Prefs.setMcpEnabled(context, it)
                                if (it) {
                                    McpServerService.start(context)
                                } else {
                                    McpServerService.stop(context)
                                }
                            }
                        )
                    }
                    if (mcpEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("端口", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            OutlinedTextField(
                                value = mcpPort,
                                onValueChange = {
                                    if (it.all { ch -> ch.isDigit() } && it.length <= 5) {
                                        mcpPort = it
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val port = mcpPort.toIntOrNull() ?: 9876
                                    Prefs.setMcpPort(context, port)
                                    scope.launch {
                                        McpServerService.stop(context)
                                        kotlinx.coroutines.delay(100)
                                        McpServerService.start(context)
                                    }
                                }
                            ) { Text("应用") }
                        }

                        if (mcpRunning) {
                            Surface(
                                color = Color(0xFF1E2E1E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("✅ MCP Server 运行中", color = Color(0xFFA6E3A1), fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("地址: http://<手机IP>:$mcpActualPort/mcp", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                                }
                            }

                            // Cloudflare Tunnel
                            HorizontalDivider(color = Color(0xFF333344))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Cloudflare 公网隧道", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "通过 trycloudflare 将 MCP Server 暴露到公网，其他设备可直接访问",
                                        fontSize = 10.sp, color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = tunnelEnabled,
                                    onCheckedChange = { enabled ->
                                        tunnelEnabled = enabled
                                        Prefs.setTunnelEnabled(context, enabled)
                                        if (enabled) {
                                            if (!binaryReady) {
                                                Toast.makeText(context, "请先下载 cloudflared 二进制", Toast.LENGTH_SHORT).show()
                                                tunnelEnabled = false
                                                Prefs.setTunnelEnabled(context, false)
                                                return@Switch
                                            }
                                            tunnelStarting = true
                                            scope.launch {
                                                val ok = CloudflareTunnelManager.start(context, mcpActualPort)
                                                tunnelStarting = false
                                                tunnelRunning = CloudflareTunnelManager.isRunning
                                                tunnelUrl = CloudflareTunnelManager.tunnelUrl
                                                if (ok && tunnelUrl.isNotEmpty()) {
                                                    Toast.makeText(context, "隧道已启动: $tunnelUrl", Toast.LENGTH_LONG).show()
                                                } else {
                                                    val err = CloudflareTunnelManager.lastError ?: "启动失败"
                                                    Toast.makeText(context, "隧道启动失败: $err", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } else {
                                            CloudflareTunnelManager.stop()
                                            tunnelRunning = false
                                            tunnelUrl = ""
                                            Toast.makeText(context, "隧道已关闭", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = !tunnelStarting && !binaryDownloading
                                )
                            }

                            // 二进制下载/状态区域
                            if (!binaryReady) {
                                Surface(
                                    color = Color(0xFF2A1E1E),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("cloudflared 二进制未安装", fontSize = 12.sp, color = Color(0xFFF9E2AF), fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "需要从 GitHub 下载 cloudflared（约 35MB），用于建立公网隧道",
                                            fontSize = 10.sp, color = Color.Gray
                                        )
                                        Text(
                                            "设备架构: ${CloudflareTunnelManager.getDeviceAbi()}",
                                            fontSize = 10.sp, color = Color(0xFF6C7086)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (binaryDownloading) {
                                            Column {
                                                Text("下载中... $downloadProgress%", fontSize = 11.sp, color = Color(0xFF89B4FA))
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { downloadProgress / 100f },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = Color(0xFF89B4FA),
                                                    trackColor = Color(0xFF313244)
                                                )
                                            }
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = {
                                                        binaryDownloading = true
                                                        downloadProgress = 0
                                                        scope.launch {
                                                            val result = CloudflareTunnelManager.downloadBinary(context) { percent ->
                                                                downloadProgress = percent
                                                            }
                                                            binaryDownloading = false
                                                            result.onSuccess {
                                                                binaryReady = true
                                                                Toast.makeText(context, "下载完成，可以开启隧道了", Toast.LENGTH_SHORT).show()
                                                            }.onFailure { e ->
                                                                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    }
                                                ) { Text("下载二进制") }
                                                if (CloudflareTunnelManager.getDownloadUrl() != null) {
                                                    TextButton(onClick = {
                                                        val url = CloudflareTunnelManager.getDownloadUrl()!!
                                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                        context.startActivity(intent)
                                                    }) { Text("浏览器打开", fontSize = 11.sp) }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // 二进制已就绪
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("✓ cloudflared 已安装", fontSize = 11.sp, color = Color(0xFFA6E3A1), modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        CloudflareTunnelManager.deleteBinary(context)
                                        binaryReady = false
                                        if (tunnelRunning) {
                                            CloudflareTunnelManager.stop()
                                            tunnelRunning = false
                                            tunnelUrl = ""
                                        }
                                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("删除", fontSize = 11.sp, color = Color(0xFFF38BA8))
                                    }
                                }
                            }

                            // Tunnel 状态显示
                            if (tunnelStarting) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFF9E2AF)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("正在建立隧道... (最多 30 秒)", fontSize = 12.sp, color = Color(0xFFF9E2AF))
                                }
                            } else if (tunnelRunning && tunnelUrl.isNotEmpty()) {
                                Surface(
                                    color = Color(0xFF1E2E1E),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("🌐 公网地址", color = Color(0xFFA6E3A1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "$tunnelUrl/mcp",
                                            fontSize = 12.sp,
                                            color = Color(0xFF89B4FA),
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "在外部设备访问此地址即可使用 MCP，每次启动 URL 会变",
                                            fontSize = 10.sp, color = Color.Gray
                                        )
                                    }
                                }
                            } else if (tunnelEnabled && !tunnelRunning && binaryReady) {
                                Text("⚠️ 隧道未运行", fontSize = 12.sp, color = Color(0xFFF38BA8))
                            }
                        } else {
                            Text("⏳ 服务启动中... 请等待", fontSize = 12.sp, color = Color(0xFFF9E2AF))
                        }
                    }
                }
            }

            // === 时间线导出 ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("时间线导出", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val path = TimelineExporter.exportToday(context)
                                    if (path != null) {
                                        Toast.makeText(context, "已导出: $path", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "当天无数据", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) { Text("导出今天") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val files = TimelineExporter.listExportedFiles(context)
                                    if (files.isEmpty()) {
                                        Toast.makeText(context, "暂无导出文件", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, files.joinToString("\n"), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        ) { Text("查看已导出") }
                    }
                    Text(
                        "文件路径: Android/data/com.daytrace/files/timeline/",
                        fontSize = 10.sp, color = Color(0xFF666666)
                    )
                }
            }

            // === Skill 驱动差分上传 ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("差分上传", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "选择上传 Skill 并填入凭证，每次上传只发送增量内容，按日期+时间命名防止覆盖",
                        fontSize = 12.sp, color = Color.Gray
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用上传", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(
                            checked = uploadEnabled,
                            onCheckedChange = {
                                uploadEnabled = it
                                Prefs.setUploadEnabled(context, it)
                            }
                        )
                    }

                    if (uploadEnabled) {
                        // Skill 选择器
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("选择上传服务", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showImportDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("导入 Skill", fontSize = 12.sp)
                            }
                        }
                        allSkills.forEach { skill ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSkillId = skill.id
                                        Prefs.setUploadSkillId(context, skill.id)
                                    }
                            ) {
                                RadioButton(
                                    selected = selectedSkillId == skill.id,
                                    onClick = {
                                        selectedSkillId = skill.id
                                        Prefs.setUploadSkillId(context, skill.id)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(skill.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        if (skill.isImported) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(color = Color(0xFF313244), shape = RoundedCornerShape(4.dp)) {
                                                Text("导入", fontSize = 9.sp, color = Color(0xFF89B4FA), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                            }
                                        }
                                    }
                                    Text(skill.description, fontSize = 10.sp, color = Color.Gray)
                                }
                                if (skill.isImported) {
                                    IconButton(onClick = {
                                        SkillManager.deleteSkill(context, skill.id)
                                        Toast.makeText(context, "已删除 ${skill.name}", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFF38BA8), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFF333344))

                        // 凭证输入
                        Text("${selectedSkill.name} 配置", fontSize = 12.sp, color = Color.Gray)
                        selectedSkill.credentials.forEach { cred ->
                            val credValue = remember(selectedSkillId, cred.id) {
                                mutableStateOf(credentials[cred.id] ?: "")
                            }
                            OutlinedTextField(
                                value = credValue.value,
                                onValueChange = {
                                    credValue.value = it
                                    credentials[cred.id] = it
                                    Prefs.setSkillCredential(context, selectedSkill.id, cred.id, it)
                                },
                                label = { Text(cred.label) },
                                placeholder = { Text(cred.placeholder) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (cred.isSecret) PasswordVisualTransformation() else VisualTransformation.None
                            )
                        }
                        if (selectedSkill.credentials.isEmpty()) {
                            Text("该 Skill 无需配置凭证", fontSize = 11.sp, color = Color.Gray)
                        }

                        // 定时上传
                        HorizontalDivider(color = Color(0xFF333344))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("定时自动上传", modifier = Modifier.weight(1f), fontSize = 14.sp)
                            Switch(
                                checked = uploadAutoScheduled,
                                onCheckedChange = {
                                    uploadAutoScheduled = it
                                    Prefs.setUploadAutoScheduled(context, it)
                                    if (it) {
                                        UploadScheduler.scheduleAll(context)
                                        Toast.makeText(context, "定时上传已开启", Toast.LENGTH_SHORT).show()
                                    } else {
                                        UploadScheduler.cancelAll(context)
                                        Toast.makeText(context, "定时上传已关闭", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        if (uploadAutoScheduled) {
                            OutlinedTextField(
                                value = uploadTimes,
                                onValueChange = {
                                    uploadTimes = it
                                    val times = it.split(",").map { t -> t.trim() }.filter { t -> t.isNotEmpty() }
                                    Prefs.setUploadTimes(context, times)
                                },
                                label = { Text("上传时间点（逗号分隔）") },
                                placeholder = { Text("09:00, 12:00, 18:00, 23:00") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                "每个时间点自动上传差分内容，按日期+时间命名防止覆盖",
                                fontSize = 10.sp, color = Color.Gray
                            )
                        }

                        // 手动操作
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    uploadTesting = true
                                    scope.launch {
                                        val result = DiffUploader.uploadDayDiff(context)
                                        uploadTesting = false
                                        uploadProgress = DiffUploader.getUploadProgress(context, System.currentTimeMillis())
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !uploadTesting
                            ) {
                                if (uploadTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("上传中...")
                                } else {
                                    Text("立即上传")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val (ok, msg) = DiffUploader.testSkill(context)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) { Text("测试连接") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        DiffUploader.resetDayOffset(context, System.currentTimeMillis())
                                        uploadProgress = DiffUploader.getUploadProgress(context, System.currentTimeMillis())
                                        Toast.makeText(context, "已重置偏移，下次将全量上传", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) { Text("重置差分") }
                        }

                        // 上传进度
                        uploadProgress?.let { prog ->
                            Surface(
                                color = Color(0xFF111122),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("上传进度", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("日期: ${prog.dateStr}", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                                    Text("已上传: ${prog.uploadedOffset} / ${prog.totalSize} 字节 (${prog.progressPercent}%)", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                                    if (prog.isComplete) {
                                        Text("✓ 全部已上传", fontSize = 11.sp, color = Color(0xFFA6E3A1))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // === Skill 导入弹窗 ===
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("导入 Skill") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("从网络 URL 导入 .skill.md 文件", fontSize = 12.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            label = { Text("Skill URL") },
                            placeholder = { Text("https://example.com/my-skill.skill.md") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            importing = true
                            scope.launch {
                                val result = SkillManager.importFromUrl(context, importUrl.trim())
                                importing = false
                                showImportDialog = false
                                importUrl = ""
                                result.onSuccess { skill ->
                                    Toast.makeText(context, "导入成功: ${skill.name}", Toast.LENGTH_SHORT).show()
                                }.onFailure { e ->
                                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !importing && importUrl.isNotBlank()
                    ) {
                        if (importing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("导入")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) { Text("取消") }
                }
            )
        }
    }
}
