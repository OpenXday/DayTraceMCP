# 日迹 DayTrace — Android 时间线记录器

> 记录你每天的手机使用轨迹，生成 Markdown 时间线，交给 AI 总结你的一天。

## 解决什么问题

每天我们在手机上的活动碎片化且难以追溯：飞书聊了什么、微信谁发了什么、搜了什么、用了多久……人脑记不住，但手机知道。

DayTrace 用两个系统级服务，静默记录你的手机活动轨迹：

| 通道 | 记录内容 | 技术 | 特点 |
|------|----------|------|------|
| 无障碍服务 | 打开应用、屏幕关键文本、使用时长 | AccessibilityService | 低频快照（30秒），不打扰 |
| 通知监听 | IM 收到的消息 | NotificationListenerService | 事件驱动，不遗漏 |
| Shizuku | 一键授权 | shell 权限执行 `settings put` | 免手动翻设置 |

## 核心功能

### 1. 时间线记录

- **窗口切换**：记录"你打开了哪个 App"
- **定时快照**：每 30 秒轻量提取屏幕关键文本（不做全量遍历，省电）
- **使用时长**：记录每个 App 的使用秒数
- **通知捕获**：飞书/微信/钉钉等 IM 的通知消息完整记录

### 2. Markdown 时间线导出

一键将当天活动导出为 `timeline-2026-08-19.md`：

```markdown
# 2026-08-19 时间线

## 概要
- 总记录数: 42
- 涉及应用: 5 个
- 应用分布:
  - 飞书: 18 条记录
  - 微信: 12 条记录
  ...

## 上午 (06:00 - 11:59)

### 08:30 飞书

| 时间 | 来源 | 内容 |
|------|------|------|
| 08:30 | 打开应用 | ↑ 打开应用 |
| 08:32 | 通知 | ↓ [张晓波] 看看有没有帮助 |
| 08:35 | 快照 | ↑ 张晓波: 看看有没有帮助 |
| 08:40 | 时长 | ↑ 使用 飞书 300秒 |
```

文件路径：`Android/data/com.daytrace/files/timeline/`

### 3. Webhook 摘要推送

不再逐条推送（避免打扰），改为**摘要模式**：
- 在设置中手动点击"推送当天摘要"
- 或通过 MCP 调用 `push_webhook_summary` 工具
- 推送内容为当天时间线的 Markdown（截断至 4000 字符）

兼容 Chanify 格式和通用 Webhook。

### 4. MCP Server

内置 HTTP JSON-RPC 服务器，让 AI 工具直接查询/导出你的时间线：

| 工具 | 说明 |
|------|------|
| `get_timeline` | 获取当天（或指定日期）的时间线 Markdown |
| `export_timeline` | 导出时间线为文件 |
| `push_webhook_summary` | 推送当天摘要到 Webhook |
| `get_recent_messages` | 获取最近记录 |
| `search_messages` | 搜索记录 |
| `get_message_stats` | 统计信息 |
| `get_messages_by_date` | 按日期查询 |
| `list_conversations` | 对话列表 |
| `clear_all_messages` | 清空记录 |

**Claude Desktop 配置**：
```json
{
  "mcpServers": {
    "daytrace": {
      "url": "http://<手机IP>:9876/mcp"
    }
  }
}
```

## 使用方式

### 场景1：每天结束时，用 AI 总结你的一天

1. 在 App 中点击"导出今天" → 生成 Markdown 文件
2. 将文件提交给 AI 助手（ChatGPT / Claude / 豆包等）
3. AI 总结："你今天 8:30 打开了飞书，和张晓波聊了XX，9:15 切到微信……"

### 场景2：通过 MCP 直接让 AI 查询

```bash
# 获取今天的时间线
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_timeline","arguments":{}}}'

# 推送摘要到 Chanify
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"push_webhook_summary","arguments":{}}}'
```

### 场景3：通过 Webhook 定时收到当天摘要

设置 → Webhook → 填入 Chanify URL + Token → 点击"推送当天摘要"

## 安装与使用

### 1. 安装 Shizuku（手机端）

1. 安装 Shizuku（CoolAPK / GitHub）
2. 通过**无线调试**启动（Android 11+）：开发者选项 → 无线调试 → Shizuku 配对
3. 或 ADB：`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`

### 2. 安装 DayTrace

```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
```

### 3. 一键授权

打开 DayTrace → 首页 → 授权 Shizuku → 一键开启所有权限

无障碍服务和通知监听自动开启，无需手动进系统设置。

### 4. 日常使用

- 正常使用手机即可，DayTrace 静默记录
- 想查看时打开 App → 时间线页
- 想总结时导出 Markdown 或通过 MCP 查询

## 项目结构

```
app/src/main/java/com/daytrace/
├── DayTraceApp.kt              # Application，初始化 Room + MCP 自动恢复
├── MainActivity.kt              # Compose UI：首页/时间线/设置
├── data/
│   ├── MessageEntry.kt          # Room Entity：统一记录模型
│   ├── MessageDao.kt            # DAO（搜索/分组/统计/删除/日期范围）
│   └── MessageDatabase.kt       # Room 数据库 (daytrace.db)
├── service/
│   ├── CaptureAccessibilityService.kt   # 无障碍服务（时间线快照模式）
│   └── NotificationCaptureService.kt    # 通知监听服务
├── export/
│   └── TimelineExporter.kt      # Markdown 时间线导出
├── server/
│   ├── McpServer.kt             # MCP JSON-RPC 服务器
│   └── McpServerService.kt      # MCP 前台服务
├── net/
│   └── WebhookSender.kt         # Webhook 摘要推送
├── ui/
│   ├── FilterModes.kt           # 过滤枚举
│   ├── HistoryScreen.kt         # 时间线页面
│   └── SettingsScreen.kt        # 设置页面
└── util/
    ├── ShizukuHelper.kt         # Shizuku 一键授权
    └── Prefs.kt                  # 偏好设置
```

## 设计理念

- **不打扰**：不逐条推送通知，不做高频扫描
- **省电**：30 秒快照 + 窗口切换事件，不做全量 View 遍历
- **不遗漏**：通知监听捕获所有 IM 消息，窗口切换记录所有 App 使用
- **可总结**：生成结构化 Markdown，方便 AI 理解和总结
- **隐私优先**：所有数据仅存本机，Webhook 和 MCP 均为可选

## 构建

```powershell
# 环境要求：JDK 17、Android SDK 36
cd E:\source\daytrace
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 技术参数

- `minSdk = 29` (Android 10)
- `targetSdk = 36` (Android 16)
- `applicationId = com.daytrace`
- `versionName = 0.2.0`

## 已知局限

1. **仅文本**：无障碍服务只能捕获文本，图片/语音/文件内容无法获取
2. **快照精度**：30秒间隔可能遗漏极短的活动（如快速切过某 App）
3. **通知内容隐藏**：部分 IM 在锁屏状态下隐藏通知内容
4. **MCP 安全性**：无认证机制，确保只在可信网络中使用
