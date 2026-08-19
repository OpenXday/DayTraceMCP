# 日迹 DayTrace — Android 时间线记录器

> 记录你每天的手机使用轨迹，实时写入 Markdown，交给 AI 总结你的一天。

## 解决什么问题

每天我们在手机上的活动碎片化且难以追溯：飞书聊了什么、微信谁发了什么、搜了什么、用了多久……人脑记不住，但手机知道。

DayTrace 用两个系统级服务，静默记录你的手机活动轨迹，并**实时写入当天的 Markdown 文件**。

## 三通道采集

| 通道 | 记录内容 | 触发时机 | 技术 |
|------|----------|----------|------|
| 定时快照 | 屏幕可见文本 | 每 30 秒 | AccessibilityService |
| 输入捕获 | 在哪个 app 输入了什么 | 实时（打字时） | TYPE_VIEW_TEXT_CHANGED |
| App 切换 | 切换瞬间 + 屏幕快照 | 每次切换 | TYPE_WINDOW_STATE_CHANGED |
| 通知监听 | IM 收到的消息 | 收到通知时 | NotificationListenerService |
| Shizuku | 一键授权 | 首次安装 | shell 权限 `settings put` |

### 三通道说明

**通道1：定时快照（每 30 秒）**
- 不管在做什么，每 30 秒拍一次屏幕快照
- **完整保存屏幕上所有可见文本**：无过滤、无截断、无深度限制
- 递归遍历整个 View 树，收集每个节点的 text 和 contentDescription
- 按 Y 坐标排序（从上到下），每条文本独立一行
- 包括按钮文字、标签、消息气泡、标题栏等所有可见内容

**通道2：输入实时捕获**
- 当你在任何 app 的输入框打字时，实时记录
- 记录内容：在哪个 app + 输入了什么文字
- 输入暂停 3 秒后自动记录（避免每打一个字就触发）
- 输入框清空时判定为"发送"，记录最终内容

**通道3：App 切换快照**
- 每次从一个 app 切到另一个 app 时，立刻记录切换
- 延迟 500ms 后再拍一张屏幕快照（等界面渲染完成）
- 同时记录上一个 app 的使用时长

## Markdown 实时写入

**核心设计**：每条记录在写入数据库的同时，**实时追加到当天的 Markdown 文件**。

不需要手动"导出"——文件始终是最新的。

### 文件位置

```
手机存储/Android/data/com.daytrace/files/timeline/timeline-2026-08-19.md
```

### 文件格式

```markdown
# 2026-08-19 时间线

> 由 DayTrace 自动生成，实时记录手机使用轨迹。
> 每条记录在产生时即刻追加，可随时提交给 AI 总结。

---

| 时间 | 来源 | 应用 | 方向 | 联系人 | 内容 |
|------|------|------|------|--------|------|
| 08:30:12 | 切换 | 飞书 | ↑ | - | → com.ss.android.lark |
| 08:30:13 | 切换快照 | 飞书 | ↑ | - | 小V贾维斯\n返回\n更多\n看看有没有帮助\n发送 |
| 08:32:45 | 输入 | 飞书 | ↑ | - | 复盘一下这次任务执行流程 |
| 08:32:50 | 发送 | 飞书 | ↑ | - | 复盘一下这次任务执行流程 |
| 08:33:00 | 快照 | 飞书 | ↑ | - | 小V贾维斯\n返回\n更多\n好的\n复盘一下这次任务执行流程\n发送 |
| 08:35:00 | 时长 | 飞书 | ↑ | - | 使用 飞书 300秒 |
| 08:35:01 | 切换 | 微信 | ↑ | - | → com.tencent.mm |
```

### 来源标签含义

| 来源 | 颜色 | 含义 |
|------|------|------|
| 切换 | 蓝色 | 打开了某个 App |
| 切换快照 | 亮蓝 | App 切换后的屏幕快照 |
| 快照 | 黄色 | 每 30 秒的定时快照 |
| 输入 | 粉红 | 在输入框打字 |
| 发送 | 粉红 | 输入框被清空（可能发送了） |
| 输入→切换 | 粉红 | 切换 App 前未保存的输入 |
| 通知 | 绿色 | 收到 IM 消息 |
| 时长 | 橙色 | App 使用时长统计 |
| 系统 | 粉红 | 系统事件 |

## 使用方式

### 场景1：每天结束时，用 AI 总结你的一天

1. 文件始终在实时更新，不需要手动导出
2. 将文件提交给 AI 助手（ChatGPT / Claude / 豆包等）
3. AI 总结："你今天 8:30 打开了飞书，和张晓波聊了XX，9:15 切到微信……"

> 也可以在设置页点击"导出今天"生成带统计概要的完整版。

### 场景2：通过 MCP 让 AI 直接查询

```bash
# 获取今天的时间线（直接读取实时文件）
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_timeline","arguments":{}}}'

# 推送摘要到 Chanify
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"push_webhook_summary","arguments":{}}}'
```

### 场景3：通过 Webhook 收到当天摘要

设置 → Webhook → 填入 Chanify URL + Token → 点击"推送当天摘要"

## MCP Server 工具

| 工具 | 说明 |
|------|------|
| `get_timeline` | 获取当天 Markdown（优先读实时文件） |
| `export_timeline` | 生成带统计概要的完整版 |
| `push_webhook_summary` | 推送当天摘要到 Webhook |
| `get_recent_messages` | 获取最近 DB 记录 |
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

## 安装

### 1. 安装 Shizuku

安装 Shizuku（CoolAPK / GitHub），通过无线调试启动（Android 11+）。

### 2. 安装 DayTrace

```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
```

### 3. 一键授权

打开 DayTrace → 首页 → 授权 Shizuku → 一键开启所有权限

### 4. 日常使用

- 正常使用手机，DayTrace 静默记录
- 每条记录实时写入 `timeline-yyyy-MM-dd.md`
- 时间线页查看记录
- 提交 MD 文件给 AI 总结

## 项目结构

```
app/src/main/java/com/daytrace/
├── DayTraceApp.kt               # Application
├── MainActivity.kt              # Compose UI：首页/时间线/设置
├── data/
│   ├── MessageEntry.kt          # Room Entity
│   ├── MessageDao.kt            # DAO
│   └── MessageDatabase.kt       # Room (daytrace.db)
├── service/
│   ├── CaptureAccessibilityService.kt   # 三通道：快照+输入+切换
│   └── NotificationCaptureService.kt    # 通知监听
├── export/
│   └── TimelineExporter.kt      # 实时追加 MD + 完整导出
├── server/
│   ├── McpServer.kt             # MCP JSON-RPC
│   └── McpServerService.kt      # MCP 前台服务
├── net/
│   └── WebhookSender.kt         # Webhook 摘要推送
├── ui/
│   ├── HistoryScreen.kt         # 时间线页面
│   └── SettingsScreen.kt        # 设置页面
└── util/
    ├── ShizukuHelper.kt         # Shizuku 一键授权
    └── Prefs.kt                  # 偏好设置
```

## 设计理念

- **不打扰**：不逐条推送通知，不做高频全量扫描
- **省电**：30 秒快照 + 窗口切换事件，输入捕获只在打字时触发
- **不遗漏**：定时快照 + 输入捕获 + 通知监听三通道覆盖
- **实时性**：每条记录实时写入 MD 文件，随时可取用
- **可总结**：结构化 Markdown 表格，方便 AI 理解
- **隐私优先**：所有数据仅存本机

## 构建

```powershell
cd E:\source\typevault
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 技术参数

- `minSdk = 29` (Android 10)
- `targetSdk = 36` (Android 16)
- `applicationId = com.daytrace`
- `versionName = 0.2.0`

## 已知局限

1. **仅文本**：图片/语音/文件内容无法获取
2. **输入捕获依赖 IME**：部分 app 的自定义输入框可能不触发 TEXT_CHANGED 事件
3. **通知内容隐藏**：部分 IM 锁屏状态下隐藏通知内容
4. **MCP 安全性**：无认证机制，确保只在可信网络中使用
