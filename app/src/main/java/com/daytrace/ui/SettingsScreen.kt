package com.daytrace.ui

import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daytrace.export.TimelineExporter
import com.daytrace.net.WebhookSender
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
                        Text(
                            "Chanify: 填 URL + Token\n通用 webhook: 填 URL",
                            fontSize = 10.sp, color = Color.Gray
                        )
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
                                    // 重启 MCP server
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
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("健康检查: GET http://<手机IP>:$mcpActualPort/", fontSize = 11.sp, color = Color(0xFF888888))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("工具列表: GET http://<手机IP>:$mcpActualPort/mcp/tools", fontSize = 11.sp, color = Color(0xFF888888))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("JSON-RPC 调用示例:", fontSize = 11.sp, color = Color(0xFF888888))
                                    Text(
                                        """curl -X POST http://<手机IP>:$mcpActualPort/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'""",
                                        fontSize = 10.sp,
                                        color = Color(0xFF666666)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("可用工具:", fontSize = 11.sp, color = Color(0xFF888888))
                                    listOf(
                                        "get_timeline - 获取当天时间线(Markdown)",
                                        "export_timeline - 导出时间线文件",
                                        "push_webhook_summary - 推送当天摘要",
                                        "get_recent_messages - 获取最近消息",
                                        "search_messages - 搜索消息",
                                        "get_message_stats - 消息统计",
                                        "get_messages_by_date - 按日期查询",
                                        "list_conversations - 对话列表",
                                        "clear_all_messages - 清空消息"
                                    ).forEach {
                                        Text("  • $it", fontSize = 10.sp, color = Color(0xFFAAAAAA))
                                    }
                                }
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
                    Text(
                        "将当天活动记录导出为 Markdown 文件，可提交给大模型总结",
                        fontSize = 12.sp, color = Color.Gray
                    )
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

            // === Claude Desktop 配置示例 ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Claude Desktop 配置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "在 Claude Desktop 配置文件 (claude_desktop_config.json) 中添加：",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Surface(
                        color = Color(0xFF111122),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            """{
  "mcpServers": {
    "DayTrace": {
      "url": "http://<手机IP>:$mcpActualPort/mcp"
    }
  }
}""",
                            fontSize = 11.sp,
                            color = Color(0xFFA6E3A1),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
