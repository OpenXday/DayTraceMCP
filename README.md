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
| 08:30:13 | 切换快照 | 飞书 | ↑ | - | 产品讨论群\n返回\n更多\n看看有没有帮助\n发送 |
| 08:32:45 | 输入 | 飞书 | ↑ | - | 讨论一下产品需求文档 |
| 08:32:50 | 发送 | 飞书 | ↑ | - | 讨论一下产品需求文档 |
| 08:33:00 | 快照 | 飞书 | ↑ | - | 产品讨论群\n返回\n更多\n好的\n讨论一下产品需求文档\n发送 |
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
3. AI 总结："你今天 8:30 打开了飞书，和产品讨论群聊了XX，9:15 切到微信……"

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

## Cloudflare 公网隧道

MCP Server 默认只能在局域网内访问。DayTrace 集成了 **Cloudflare Quick Tunnel (trycloudflare)**，可将 MCP Server 暴露到公网，让任何设备随时访问。

### 工作原理

在手机上运行 `cloudflared` 二进制，创建临时隧道，获得公网 URL（如 `https://abc-xyz.trycloudflare.com/mcp`）。

### Android DNS 解决方案

`cloudflared` 是 Go 静态编译（CGO_ENABLED=0），只能用纯 Go DNS resolver。Android 上 `/etc/resolv.conf` 不存在，Go fallback 到 `[::1]:53`，导致 DNS 解析失败。

DayTrace 采用 **App 层 DNS 代理** 策略绕过此限制：
1. App 通过 OkHttp 调用 Cloudflare API 创建 Quick Tunnel（App 有正常 DNS）
2. App 通过 Java `InetAddress` 解析 Cloudflare Edge IP（`region1.argotunnel.com`）
3. 构造 credentials JSON + YAML 配置文件（含 Edge IP）
4. 通过 Shizuku 启动 `cloudflared tunnel --config <yml> run`，配置中的 Edge IP 让 cloudflared 直接连 IP，不需要 DNS

### 二进制按需下载

`cloudflared` 二进制**不打入 APK**（APK 仅 55MB），在 App 内按需下载：
- App 自动识别设备架构（arm64-v8a / armeabi-v7a / x86_64）
- 从 [GitHub Releases](https://github.com/cloudflare/cloudflared/releases) 下载对应二进制（约 35MB）
- 下载进度实时显示，也可用浏览器手动下载

### 使用方式

设置 → MCP Server → Cloudflare 公网隧道：
1. 点击「下载二进制」下载 cloudflared
2. 下载完成后打开隧道开关
3. 等待约 10-60 秒获取公网 URL
4. 在外部设备用 `https://xxx.trycloudflare.com/mcp` 访问

> 每次启动 URL 会变（临时隧道），前台通知显示当前 URL，并自动复制到剪贴板

## MCP Server

DayTrace MCP Server 实现了 **MCP Streamable HTTP 传输规范**（协议版本 `2025-06-18`），支持以下 HTTP 方法：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/mcp` | JSON-RPC 2.0 请求（支持 SSE 响应） |
| `GET` | `/mcp` | SSE 流（服务器推送事件，保持连接） |
| `DELETE` | `/mcp` | 终止会话 |
| `GET` | `/` | 健康检查 |
| `GET` | `/mcp/tools` | 工具列表（便利端点） |
| `GET` | `/captures/<category>/<filename>` | 下载拍摄的照片/视频/音频/截屏文件 |

### 会话管理

- `initialize` 请求创建新会话，返回 `Mcp-Session-Id` header
- 后续请求可携带该 header 维持会话
- `DELETE /mcp` 终止会话
- 支持 `notifications/initialized` 通知
- 支持批量 JSON-RPC 请求

### 安全认证

设置 → MCP Server → 安全认证：
- 启用后，请求需携带 `Authorization: Bearer <token>` header
- 也支持 `?token=xxx` query parameter
- 未认证请求返回 `401 Unauthorized`

### MCP 工具列表

| 工具 | 说明 |
|------|------|
| `get_timeline` | 获取当天 Markdown（优先读实时文件） |
| `export_timeline` | 生成带统计概要的完整版 |
| `push_webhook_summary` | 推送当天摘要到 Webhook |
| `get_recent_messages` | 获取最近 DB 记录（可按方向/应用过滤） |
| `search_messages` | 按关键词搜索记录 |
| `get_message_stats` | 统计信息（总数/方向/按应用） |
| `get_messages_by_date` | 按时间范围查询 |
| `get_app_list` | 获取所有已采集消息的应用列表 |
| `get_conversation` | 获取指定应用和联系人的完整对话上下文 |
| `list_conversations` | 列出所有对话（应用 + 联系人/群组） |
| `clear_all_messages` | 清空所有记录（谨慎使用） |
| `take_photo` | 远程拍照（指定前后置/分辨率/闪光灯，返回 Base64 JPEG + 拍摄参数） |
| `record_video` | 远程录像（默认 6s 720p MP4，可指定时长/分辨率） |
| `record_audio` | 远程录音（默认 10s AAC/M4A，可指定时长/格式） |
| `list_captures` | 列出所有已拍摄的照片/视频/音频/截屏文件 |
| `delete_capture` | 删除指定拍摄文件 |
| `grant_camera_mic_permissions` | 通过 Shizuku 自动授予相机/麦克风权限 |
| `take_screenshot` | 截取当前屏幕（通过 Shizuku screencap，返回 Base64 PNG/JPEG + 屏幕分辨率/前台 App） |
| `get_screen_text` | 提取当前屏幕所有可见文字（通过无障碍服务遍历视图树，按 Y 坐标排序） |
| `get_device_info` | 获取设备信息（型号/Android版本/电量/内存/存储/屏幕分辨率/网络状态） |
| `get_app_usage_stats` | 获取今日 App 使用时长统计（各 App 前台时长排行） |
| `get_installed_apps` | 列出所有已安装应用（包名+应用名+版本+是否系统App） |
| `get_battery_history` | 获取电池详细信息（电量/充电状态/健康/温度/电压） |
| `get_clipboard` | 读取系统剪贴板内容 |
| `set_clipboard` | 写入内容到系统剪贴板 |
| `send_notification` | 发送本地通知到手机（AI 提醒用户用） |
| `get_notification_log` | 获取最近通知历史（通知监听服务内存缓存，最近 100 条） |
| `send_text_message` | 发送短信（需 SEND_SMS 权限或 Shizuku） |
| `take_screenshot_region` | 截取屏幕指定区域（先全屏截图再裁剪） |
| `get_screen_interactive_elements` | 提取屏幕所有可交互元素（按钮/输入框/开关等，含坐标） |
| `tap_screen` | 模拟点击屏幕坐标（通过 Shizuku input tap） |
| `input_text` | 在当前焦点输入框输入文字（ASCII 直接输入，中文通过剪贴板粘贴） |
| `press_key` | 模拟按键（Home/Back/Recent/音量等） |
| `swipe` | 模拟滑动手势（指定起止坐标和时长） |
| `open_app` | 远程打开 App（通过 Shizuku am start / monkey） |

### 客户端配置

**Claude Desktop 配置（局域网）**：
```json
{
  "mcpServers": {
    "daytrace": {
      "url": "http://<手机IP>:9876/mcp"
    }
  }
}
```

**Claude Desktop 配置（公网隧道）**：
```json
{
  "mcpServers": {
    "daytrace": {
      "url": "https://xxx-yyy.trycloudflare.com/mcp"
    }
  }
}
```

**带认证的配置**：
```json
{
  "mcpServers": {
    "daytrace": {
      "url": "http://<手机IP>:9876/mcp",
      "headers": {
        "Authorization": "Bearer <your-token>"
      }
    }
  }
}
```

**curl 示例**：
```bash
# 初始化会话
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# 获取今天的时间线
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_timeline","arguments":{}}}'

# 搜索消息
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_messages","arguments":{"keyword":"飞书"}}}'

# 远程拍照（后置摄像头，1080p）
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"take_photo","arguments":{"camera":"back","resolution":"1080p"}}}'

# 远程拍照（前置摄像头，720p，开闪光灯）
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"take_photo","arguments":{"camera":"front","resolution":"720p","flash":"on"}}}'

# 录像（后置，6秒，720p）
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"record_video","arguments":{"camera":"back","duration":6,"resolution":"720p"}}}'

# 录音（10秒，AAC格式）
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"record_audio","arguments":{"duration":10,"format":"aac"}}}'

# 下载拍摄的照片
curl -o photo.jpg http://<手机IP>:9876/captures/photos/photo_2026-08-24_14-30-00-000.jpg

# 通过 Shizuku 授予相机/麦克风权限
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"grant_camera_mic_permissions","arguments":{}}}'
```

## 安装

### 1. 安装 Shizuku

安装 Shizuku（CoolAPK / GitHub），通过无线调试启动（Android 11+）。

> Shizuku 用于：一键授权无障碍权限、执行 cloudflared 二进制（Android 10+ 禁止 App 直接 execve ELF）

### 2. 安装 DayTrace

```powershell
# Debug 版
adb install app\build\outputs\apk\debug\app-debug.apk

# Release 版（推荐）
adb install app\build\outputs\apk\release\app-release.apk
```

### 3. 一键授权

打开 DayTrace → 首页 → 授权 Shizuku → 一键开启所有权限

### 4. 日常使用

- 正常使用手机，DayTrace 静默记录
- 每条记录实时写入 `timeline-yyyy-MM-dd.md`
- 时间线页查看记录
- 提交 MD 文件给 AI 总结

### 5. 公网访问（可选）

- 设置 → MCP Server → Cloudflare 公网隧道
- 下载 cloudflared → 开启隧道 → 获取公网 URL
- 在任意设备通过公网 URL 访问 MCP

## 项目结构

```
app/src/main/java/com/daytrace/
├── DayTraceApp.kt               # Application
├── MainActivity.kt              # Compose UI：首页/时间线/MCP日志/设置
├── data/
│   ├── MessageEntry.kt          # Room Entity
│   ├── MessageDao.kt            # DAO
│   ├── MessageDatabase.kt       # Room (daytrace.db + mcp_logs)
│   ├── McpLogEntry.kt           # MCP 调用日志 Entity（17 字段）
│   └── McpLogDao.kt             # MCP 日志 DAO（分页查询 + 统计）
├── service/
│   ├── CaptureAccessibilityService.kt   # 三通道：快照+输入+切换
│   └── NotificationCaptureService.kt    # 通知监听
├── export/
│   └── TimelineExporter.kt      # 实时追加 MD + 完整导出
├── server/
│   ├── McpServer.kt             # MCP Streamable HTTP 服务器（JSON-RPC + SSE + 会话管理 + 日志拦截）
│   ├── McpServerService.kt       # MCP 前台服务 + Tunnel 生命周期
│   ├── McpLogRecorder.kt         # MCP 日志异步写入器（协程非阻塞）
│   ├── CameraCaptureManager.kt  # Camera2 拍照/录像管理器（远程拍照/录像）
│   ├── AudioCaptureManager.kt   # 录音管理器（AAC/WAV 录音）
│   ├── ScreenCaptureManager.kt  # 屏幕截图/文字提取管理器（Shizuku screencap + 无障碍文字 + 区域裁剪）
│   ├── DeviceInfoManager.kt     # 设备信息管理器（设备信息/App使用统计/已安装应用/电池/剪贴板/通知/短信）
│   ├── SystemActionManager.kt   # 系统操作管理器（打开App/点击/输入/按键/滑动/可交互元素）
│   └── CloudflareTunnelManager.kt  # cloudflared 进程管理 + DNS 代理 + 按需下载
├── net/
│   ├── WebhookSender.kt         # Webhook 摘要推送
│   ├── DiffUploader.kt          # Skill 执行引擎（多步骤 + 占位符 + 差分，暂未启用）
│   ├── SkillRegistry.kt         # Skill Markdown 解析器 + 管理器（暂未启用）
│   ├── UploadScheduler.kt       # 定时上传调度（AlarmManager，暂未启用）
│   └── BootReceiver.kt          # 开机自启动恢复定时上传
├── ui/
│   ├── HistoryScreen.kt         # 时间线页面
│   ├── McpLogScreen.kt          # MCP 调用日志审计页面（概览 + 过滤 + 分页 + 时序详情）
│   ├── FilterModes.kt           # 过滤模式定义
│   └── SettingsScreen.kt        # 设置页面
└── util/
    ├── ShizukuHelper.kt         # Shizuku 一键授权
    └── Prefs.kt                  # 偏好设置
```

## 后台隐藏

App 在设置页提供了「从最近任务隐藏」开关（默认关闭 = 显示）：
- 关闭（默认）：App 正常显示在最近任务列表中
- 开启：App 从最近任务列表隐藏，防止被误划掉删除
- 使用 `ActivityManager.AppTask.setExcludeFromRecents()` 运行时控制
- 前台通知仍保留（系统要求），但任务列表中不显示
- 如需打开 App，从桌面图标启动即可

## MCP 调用日志审计

DayTrace 内置了完整的 MCP Server 调用审计系统，App 内「MCP日志」Tab 可查看每次 MCP 调用的完整记录。

### 功能概览

- **三层展示**：概览卡片 → 过滤栏 → 分页日志列表
- **概览卡片**：总调用数 / 成功率 / 活跃会话数 / 最近调用时间
- **自动刷新**：每 3 秒自动拉取最新日志和统计（无需手动刷新）
- **过滤维度**：按 RPC 方法（initialize / tools/call / ping…）、成功/失败、工具名
- **分页加载**：每次加载 50 条，滚动到底部自动加载更多
- **调用时序详情**：点击任意条目展开，查看完整请求-响应时序（HTTP 方法、路径、客户端 IP、认证结果、请求体、响应体、耗时、错误堆栈）

### 日志字段

| 字段 | 说明 |
|------|------|
| timestamp | 调用时间戳 |
| httpMethod / httpPath | HTTP 方法和路径 |
| clientIp | 客户端真实 IP（穿透 Cloudflare 隧道） |
| responseCode | HTTP 状态码 |
| durationMs | 处理耗时（毫秒） |
| rpcMethod | JSON-RPC 方法名 |
| rpcId | JSON-RPC ID |
| toolName | 工具名（tools/call 时） |
| sessionId | MCP 会话 ID |
| authResult | 认证结果（ok / failed / not_required） |
| requestBody / responseBody | 请求/响应 JSON（截断 5000 字符） |
| success | 是否成功 |
| errorMessage / errorStack | 错误信息和堆栈 |

### 真实客户端 IP 检测

通过 Cloudflare 隧道访问时，MCP Server 会按以下优先级解析真实客户端 IP：

1. `CF-Connecting-IP` header（Cloudflare 注入的真实 IP）
2. `X-Forwarded-For` header（标准代理头，取第一个 IP）
3. `remote-addr` header（直连时的 remote address）
4. fallback `unknown`

### 异步非阻塞写入

日志通过 `McpLogRecorder` 在独立 `CoroutineScope`（`SupervisorJob`）中异步写入数据库，不影响 MCP 请求的响应延迟。

## 设计理念

- **不打扰**：不逐条推送通知，不做高频全量扫描
- **省电**：30 秒快照 + 窗口切换事件，输入捕获只在打字时触发
- **不遗漏**：定时快照 + 输入捕获 + 通知监听三通道覆盖
- **实时性**：每条记录实时写入 MD 文件，随时可取用
- **可总结**：结构化 Markdown 表格，方便 AI 理解
- **公网隧道**：内置 cloudflared 按需下载，一键暴露 MCP 到公网
- **调用审计**：内置 MCP 日志系统，实时查看每次调用的完整时序
- **后台隐藏**：不在最近任务中显示，防误杀
- **隐私优先**：所有数据仅存本机

## 构建

```powershell
cd E:\source\typevault

# Debug 版
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# Release 版（推荐，使用 debug 签名方便直接安装）
.\gradlew.bat assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

> 如遇 Gradle 缓存锁定问题，可加 `--no-daemon --no-build-cache --max-workers=1` 参数。

## 技术参数

- `minSdk = 29` (Android 10)
- `targetSdk = 36` (Android 16)
- `applicationId = com.daytrace`
- `versionName = 0.4.0`

## 更新日志

### v0.4.0

- **新增 17 个 MCP 工具，总计 38 个**，覆盖设备信息查询、远程 UI 操控、通知历史等全方面能力：
  - **屏幕截图与文字提取**：`take_screenshot`（Shizuku screencap，支持 PNG/JPEG，返回 Base64 + 下载 URL）、`get_screen_text`（无障碍服务遍历视图树，按 Y 坐标排序）、`take_screenshot_region`（指定区域截图，先全屏再裁剪）
  - **设备信息**：`get_device_info`（型号/Android版本/电量/内存/存储/屏幕分辨率/网络状态）、`get_app_usage_stats`（各 App 今日前台时长排行）、`get_installed_apps`（包名+应用名+版本+是否系统App）、`get_battery_history`（电量/充电状态/健康/温度/电压）
  - **剪贴板**：`get_clipboard` / `set_clipboard`
  - **通知与短信**：`send_notification`（AI 发送本地提醒通知）、`get_notification_log`（通知监听服务内存缓存，最近 100 条）、`send_text_message`（通过 Shizuku 授予 SEND_SMS 权限后发送短信）
  - **远程 UI 操控**：`get_screen_interactive_elements`（提取可交互元素含坐标）、`tap_screen`（模拟点击）、`input_text`（ASCII 直接输入，中文通过剪贴板粘贴）、`press_key`（Home/Back/Recent/音量等按键）、`swipe`（模拟滑动手势）、`open_app`（远程打开 App）
- **修复：MCP 请求中文乱码**
  - 问题：AI 调用 `send_notification` 等含中文参数的工具时，手机收到的通知内容全是问号乱码。
  - 根因：NanoHTTPD 的 `parseBody()` 用 US-ASCII 编码解码 POST body，UTF-8 编码的中文字节（> 127）被替换为 `?`，信息不可逆丢失。
  - 修复：完全弃用 `parseBody()`，改为直接从 `session.inputStream` 读取 `Content-Length` 指定大小的原始字节，用 UTF-8 解码。
  - 同步修复：SSE 响应 `toByteArray()` 显式指定 UTF-8、Shizuku shell 输出 `bufferedReader` 显式指定 UTF-8、Cloudflare Tunnel 配置文件写入显式指定 UTF-8。
- **修复：MCP 协议兼容性**
  - `notifications/initialized`：某些 MCP 客户端发送带前缀的全名，`when` 分支增加全名匹配。
  - `server/discover`：某些客户端用于服务发现，新增该方法返回服务器能力描述。
  - 认证失败日志显示 "None"：认证失败时 `rpcMethod` 为空，UI 改为显示 "—"。
- **安全加固**：
  - `open_app` 增加包名格式校验（只允许字母、数字、点、下划线），防止 shell 命令注入。
  - `input_text` 改用单引号包裹参数，防止 shell 元字符（`$`、`` ` ``）展开。
- **稳定性修复**：
  - 无障碍节点遍历（`collectInteractiveNodes` / `collectAllTextNodes`）增加递归深度限制（50 层），防止极端视图树栈溢出。
  - 截图临时文件使用唯一文件名（时间戳+线程ID），防止并发截图互相覆盖。
  - `takeScreenshotRegion` 裁剪后清理全屏截图临时文件，防止存储泄露。
  - `get_installed_apps` 限制最多返回 200 个 App，防止输出过大。
  - `parseDumpsysUsageStats` 修复排序结果未赋值导致输出顺序不正确。
  - `sendNotification` 不再每次重复创建/更新 NotificationChannel importance。
  - `sendTextMessage` 移除无效的 `am broadcast` 方式（实际只打开短信 App 不发送），改为通过 Shizuku `pm grant` 授予 SEND_SMS 权限后用 SmsManager 真正发送。

### v0.3.2

- **修复：长时间运行后无障碍服务状态显示异常**
  - 问题：App 运行数天后，首页服务状态面板中"无障碍服务"显示"未开启"，但实际服务正常运行（时间线正常采集），跳转到系统无障碍设置中可以看到服务是开启的。关闭再重新开启后状态恢复正常。
  - 根因：状态检查仅依赖 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 字符串匹配，长时间运行后系统 SettingsProvider 缓存与实际服务运行状态产生偏差，导致检查结果不准确。
  - 修复：改为双重检测策略——优先检查服务进程内的静态标志位 `isRunning`（`onServiceConnected` 时设 true，`onDestroy` 时设 false），该标志位直接反映服务实际运行状态，不受 SettingsProvider 缓存影响；静态标志位为 false 时再 fallback 到 Settings.Secure 字符串检查。
  - 同步修复了通知监听服务（`NotificationCaptureService`）的同类问题。

### v0.3.1

- 初始发布版本

## 已知局限

1. **仅文本**：图片/语音/文件内容无法获取
2. **输入捕获依赖 IME**：部分 app 的自定义输入框可能不触发 TEXT_CHANGED 事件
3. **通知内容隐藏**：部分 IM 锁屏状态下隐藏通知内容
4. **公网隧道 URL 公开**：trycloudflare 临时隧道 URL 无认证，公网可访问（已支持 Bearer Token 认证，建议开启）
5. **Shizuku 依赖**：cloudflared 二进制需要 Shizuku 以 shell 权限运行（Android 10+ 限制）
