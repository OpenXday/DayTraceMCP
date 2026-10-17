# DayTrace (日迹) — Release Notes

> Android 时间线记录器 · 记录你每天的手机使用轨迹，实时写入 Markdown，交给 AI 总结你的一天。

**Current Version**: 0.4.1 (versionCode 4)  
**Date**: 2026-09-19  
**Min SDK**: 29 (Android 10)  
**Target SDK**: 36 (Android 16)  
**Application ID**: `com.daytrace`

---

## 一、功能总览

DayTrace 是一款 Android 应用，通过系统级服务静默记录手机使用轨迹，并实时写入 Markdown 文件。同时内置 MCP Server（Model Context Protocol），让 AI 助手直接远程查询手机数据、操控设备、拍照录像。

### 核心能力

| 能力域 | 说明 |
|--------|------|
| **三通道采集** | 定时快照 + 输入捕获 + App 切换，全方位记录手机活动 |
| **通知监听** | 捕获 IM 消息通知（微信/飞书/QQ 等） |
| **Markdown 实时写入** | 每条记录即时追加到当天 MD 文件，无需手动导出 |
| **MCP Server** | 35 个远程工具，AI 可直接查询时间线、搜索消息、操控设备 |
| **远程拍照/录像/录音** | 通过 Camera2 API 远程采集照片/视频/音频 |
| **屏幕截图与文字提取** | 通过 Shizuku 截图、无障碍服务提取屏幕文字与控件坐标 |
| **远程 UI 操控** | 模拟点击、输入、滑动、按键、打开 App |
| **Cloudflare 公网隧道** | 一键暴露 MCP 到公网，任何设备可访问 |
| **MCP 调用审计** | 内置日志系统，实时查看每次调用的完整时序 |
| **后台隐藏** | 可从最近任务列表隐藏，防误杀 |

---

## 二、三通道采集

| 通道 | 记录内容 | 触发时机 | 技术 |
|------|----------|----------|------|
| 定时快照 | 屏幕可见文本 | 每 30 秒 | AccessibilityService |
| 输入捕获 | 在哪个 app 输入了什么 | 实时（打字时） | TYPE_VIEW_TEXT_CHANGED |
| App 切换 | 切换瞬间 + 屏幕快照 | 每次切换 | TYPE_WINDOW_STATE_CHANGED |
| 通知监听 | IM 收到的消息 | 收到通知时 | NotificationListenerService |

### 采集说明

- **定时快照**：递归遍历整个 View 树，完整保存屏幕上所有可见文本（无过滤、无截断），按 Y 坐标排序
- **输入捕获**：输入暂停 3 秒后自动记录，输入框清空时判定为"发送"
- **App 切换**：延迟 500ms 后拍屏幕快照（等界面渲染完成），同时记录上一个 App 使用时长

### Markdown 文件

文件位置：`手机存储/Android/data/com.daytrace/files/timeline/timeline-yyyy-MM-dd.md`

格式示例：

```markdown
| 时间 | 来源 | 应用 | 方向 | 联系人 | 内容 |
|------|------|------|------|--------|------|
| 08:30:12 | 切换 | 飞书 | ↑ | - | → com.ss.android.lark |
| 08:32:45 | 输入 | 飞书 | ↑ | - | 讨论一下产品需求文档 |
| 08:33:00 | 快照 | 飞书 | ↑ | - | 产品讨论群 返回 更多 好的 发送 |
| 08:35:00 | 时长 | 飞书 | ↑ | - | 使用 飞书 300秒 |
```

---

## 三、安装与配置

### 步骤 1：安装 Shizuku

安装 Shizuku（CoolAPK / GitHub），通过无线调试启动（Android 11+）。

> Shizuku 用于：一键授权无障碍/通知监听权限、执行 cloudflared 二进制、授予相机/麦克风权限。

### 步骤 2：安装 DayTrace

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

> Release APK 使用 debug 签名，可直接安装，无需额外 keystore。

### 步骤 3：一键授权

打开 DayTrace → 首页 → 授权 Shizuku → 点击「一键开启所有权限」

自动授予：无障碍服务 + 通知监听 + 相机 + 麦克风

### 步骤 4：日常使用

- 正常使用手机，DayTrace 静默记录
- 每条记录实时写入 `timeline-yyyy-MM-dd.md`
- 时间线页查看记录
- 提交 MD 文件给 AI 总结，或通过 MCP 让 AI 直接查询

### 步骤 5：公网访问（可选）

设置 → MCP Server → Cloudflare 公网隧道：

1. 点击「下载二进制」下载 cloudflared（约 35MB，按设备架构自动选择）
2. 下载完成后打开隧道开关
3. 等待 10-60 秒获取公网 URL
4. 在外部设备用 `https://xxx.trycloudflare.com/mcp` 访问

> 每次启动 URL 会变（临时隧道），前台通知显示当前 URL，并自动复制到剪贴板。

---

## 四、MCP Server

### 协议

DayTrace MCP Server 实现 MCP Streamable HTTP 传输规范（协议版本 `2025-06-18`）。

| HTTP 方法 | 路径 | 说明 |
|-----------|------|------|
| `POST` | `/mcp` | JSON-RPC 2.0 请求（支持 SSE 响应） |
| `GET` | `/mcp` | SSE 流（服务器推送事件） |
| `DELETE` | `/mcp` | 终止会话 |
| `GET` | `/` | 健康检查 |
| `GET` | `/mcp/tools` | 工具列表（便利端点） |
| `GET` | `/captures/<category>/<filename>` | 下载拍摄的照片/视频/音频/截屏文件 |

### 安全认证

设置 → MCP Server → 安全认证：

- 启用后，请求需携带 `Authorization: Bearer <token>` header
- 也支持 `?token=xxx` query parameter
- 未认证请求返回 `401 Unauthorized`

### 客户端配置

**Claude Desktop（局域网）**：

```json
{
  "mcpServers": {
    "daytrace": {
      "url": "http://<手机IP>:9876/mcp"
    }
  }
}
```

**Claude Desktop（公网隧道）**：

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

### curl 示例

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

# 获取屏幕文字（含控件坐标）
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_screen_text","arguments":{"includeBounds":true}}}'

# 模拟点击屏幕
curl -X POST http://<手机IP>:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"tap_screen","arguments":{"x":540,"y":960}}}'
```

---

## 五、MCP 工具列表（35 个）

### 消息查询与时间线（11 个）

| 工具 | 参数 | 用途 |
|------|------|------|
| `get_timeline` | `date` (YYYY-MM-DD, 空=今天) | 获取当天时间线 Markdown |
| `export_timeline` | `date` | 导出时间线为 Markdown 文件，返回文件路径 |
| `push_webhook_summary` | `date` | 推送当天摘要到 Webhook |
| `get_recent_messages` | `limit`(1-500), `direction`(outgoing/incoming/all), `appPackage` | 获取最近消息 |
| `search_messages` | `keyword`(必填), `limit` | 按关键词搜索消息 |
| `get_messages_by_date` | `startTime`(ms), `endTime`(ms) | 按时间范围查询 |
| `get_message_stats` | 无 | 消息统计（总数/收发/按应用） |
| `get_app_list` | 无 | 有记录的 App 列表 |
| `list_conversations` | 无 | 列出所有对话（应用 + 联系人/群组） |
| `get_conversation` | `appPackage`(必填), `contactOrGroup`, `limit`(1-1000) | 获取指定应用和联系人的完整对话 |
| `clear_all_messages` | 无 | ⚠️ 清空所有记录（不可恢复） |

### 远程拍照 / 录像 / 录音（6 个）

| 工具 | 参数 | 用途 |
|------|------|------|
| `take_photo` | `camera`(back/front), `resolution`(720p/1080p/4k/max/WxH), `flash`(off/on/auto) | 远程拍照，返回 Base64 JPEG + 拍摄参数 |
| `record_video` | `camera`, `duration`(1-60s), `resolution`(720p/1080p/480p), `returnBase64` | 录像，默认 6s 720p MP4，返回下载 URL 或 Base64 |
| `record_audio` | `duration`(1-300s), `format`(aac/wav) | 录音，默认 10s AAC/M4A，返回 Base64 |
| `list_captures` | 无 | 列出所有拍摄文件 |
| `delete_capture` | `filename`(必填), `category`(photos/videos/audio/screenshots) | 删除拍摄文件 |
| `grant_camera_mic_permissions` | 无 | 通过 Shizuku 授予 CAMERA 和 RECORD_AUDIO 权限 |

### 屏幕截图与文字提取（2 个）

| 工具 | 参数 | 用途 |
|------|------|------|
| `take_screenshot` | `format`(png/jpeg), `quality`(1-100), `returnBase64` | 通过 Shizuku 截图，返回 Base64 + 屏幕分辨率/前台 App |
| `get_screen_text` | `includeBounds`(bool, 默认 false) | 通过无障碍服务提取屏幕所有可见文字。`includeBounds=true` 时返回每个元素的矩形坐标、宽高、类名 |

### 设备信息与系统操作（16 个）

| 工具 | 参数 | 用途 |
|------|------|------|
| `get_device_info` | 无 | 设备信息（型号/Android版本/电量/内存/存储/屏幕/网络） |
| `get_app_usage_stats` | 无 | 今日 App 使用时长排行 |
| `get_installed_apps` | `includeSystem`(bool) | 列出所有已安装应用 |
| `get_battery_history` | 无 | 电池信息（电量/充电/健康/温度/电压） |
| `get_clipboard` | 无 | 读取剪贴板 |
| `set_clipboard` | `text`(必填) | 写入剪贴板 |
| `send_notification` | `title`, `message`(必填), `priority` | 发送本地通知 |
| `get_notification_log` | `limit`(1-100) | 通知历史（内存缓存，最近 100 条） |
| `send_text_message` | `phoneNumber`, `message`(必填) | 发送短信 |
| `take_screenshot_region` | `left`,`top`,`right`,`bottom`(必填), `format`,`quality` | 截取屏幕指定区域 |
| `get_screen_interactive_elements` | 无 | 提取可交互元素（按钮/输入框，含坐标） |
| `tap_screen` | `x`,`y`(必填) | 模拟点击 |
| `input_text` | `text`(必填) | 输入文字（ASCII 直接，中文通过剪贴板） |
| `press_key` | `key`(必填) | 模拟按键（home/back/recent/volume 等） |
| `swipe` | `startX`,`startY`,`endX`,`endY`(必填), `durationMs` | 模拟滑动手势 |
| `open_app` | `packageName`(必填), `activity` | 远程打开 App |

**`press_key` 可用按键**：home, back, recent, menu, power, volume_up, volume_down, volume_mute, delete, enter, escape, tab, space, search, camera, call, end_call, notification, screenshot

### 权限要求

| 工具 | 依赖 |
|------|------|
| `take_screenshot` / `take_screenshot_region` / `tap_screen` / `input_text` / `press_key` / `swipe` / `open_app` | Shizuku 运行且已授权 |
| `get_screen_text` / `get_screen_interactive_elements` | 无障碍服务已开启 |
| `take_photo` / `record_video` / `record_audio` | 相机/麦克风权限（调 `grant_camera_mic_permissions` 或手动授予） |
| `send_text_message` | SEND_SMS 权限（通过 Shizuku `pm grant`） |
| `get_app_usage_stats` | PACKAGE_USAGE_STATS 权限 |

### 文件下载

拍摄文件可通过 HTTP GET 下载：

- 照片：`GET /captures/photos/<filename>`
- 视频：`GET /captures/videos/<filename>`
- 音频：`GET /captures/audio/<filename>`
- 截屏：`GET /captures/screenshots/<filename>`

文件存储位置：`Android/data/com.daytrace/files/captures/{photos,videos,audio,screenshots}/`

---

## 六、Cloudflare 公网隧道

MCP Server 默认仅局域网可访问。DayTrace 集成 Cloudflare Quick Tunnel，一键暴露到公网。

### 工作原理

1. App 通过 OkHttp 调用 Cloudflare API 创建 Quick Tunnel（App 有正常 DNS）
2. App 通过 Java `InetAddress` 解析 Cloudflare Edge IP
3. 构造 credentials JSON + YAML 配置文件（含 Edge IP）
4. 通过 Shizuku 启动 `cloudflared tunnel --config <yml> run`

> `cloudflared` 二进制不打入 APK，在 App 内按需下载（约 35MB），自动识别设备架构。

---

## 七、MCP 调用日志审计

DayTrace 内置完整的 MCP Server 调用审计系统，App 内「MCP日志」Tab 可查看每次调用的完整记录。

### 功能

- **三层展示**：概览卡片 → 过滤栏 → 分页日志列表
- **概览卡片**：总调用数 / 成功率 / 活跃会话数 / 最近调用时间
- **自动刷新**：每 3 秒自动拉取
- **过滤维度**：按 RPC 方法、成功/失败、工具名
- **分页加载**：每次 50 条，滚动到底自动加载
- **调用时序详情**：点击展开查看完整请求-响应时序

### 日志字段

| 字段 | 说明 |
|------|------|
| timestamp | 调用时间戳 |
| httpMethod / httpPath | HTTP 方法和路径 |
| clientIp | 客户端真实 IP（穿透 Cloudflare 隧道） |
| responseCode | HTTP 状态码 |
| durationMs | 处理耗时（毫秒） |
| rpcMethod | JSON-RPC 方法名 |
| toolName | 工具名 |
| sessionId | MCP 会话 ID |
| authResult | 认证结果 |
| requestBody / responseBody | 请求/响应 JSON（截断 5000 字符） |
| success | 是否成功 |
| errorMessage / errorStack | 错误信息和堆栈 |

> 日志通过 `McpLogRecorder` 在独立 CoroutineScope 中异步写入，不影响 MCP 请求响应延迟。

---

## 八、其他功能

### 后台隐藏

设置页提供「从最近任务隐藏」开关（默认关闭）：
- 开启后 App 从最近任务列表隐藏，防止被误划掉
- 使用 `ActivityManager.AppTask.setExcludeFromRecents()` 控制
- 前台通知仍保留（系统要求），桌面图标启动

### Webhook 推送

设置 → Webhook → 填入 Chanify URL + Token → 点击"推送当天摘要"

也可通过 MCP 工具 `push_webhook_summary` 远程触发。

---

## 九、设计理念

- **不打扰**：不逐条推送通知，不做高频全量扫描
- **省电**：30 秒快照 + 窗口切换事件，输入捕获只在打字时触发
- **不遗漏**：定时快照 + 输入捕获 + 通知监听三通道覆盖
- **实时性**：每条记录实时写入 MD 文件，随时可取用
- **可总结**：结构化 Markdown 表格，方便 AI 理解
- **公网隧道**：内置 cloudflared 按需下载，一键暴露 MCP 到公网
- **调用审计**：内置 MCP 日志系统，实时查看每次调用的完整时序
- **后台隐藏**：不在最近任务中显示，防误杀
- **隐私优先**：所有数据仅存本机，`allowBackup=false` 防止数据导出

---

## 十、已知局限

1. **仅文本**：图片/语音/文件内容无法获取
2. **输入捕获依赖 IME**：部分 app 的自定义输入框可能不触发 TEXT_CHANGED 事件
3. **通知内容隐藏**：部分 IM 锁屏状态下隐藏通知内容
4. **公网隧道 URL 公开**：trycloudflare 临时隧道 URL 无认证，公网可访问（已支持 Bearer Token 认证，建议开启）
5. **Shizuku 依赖**：cloudflared 二进制需要 Shizuku 以 shell 权限运行

---

## 十一、技术参数

| 参数 | 值 |
|------|-----|
| applicationId | `com.daytrace` |
| versionName | 0.4.1 |
| versionCode | 4 |
| minSdk | 29 (Android 10) |
| targetSdk | 36 (Android 16) |
| compileSdk | 36 |
| MCP 工具数 | 35 |
| MCP 协议版本 | 2025-06-18 |

---

## 十二、构建

```powershell
cd E:\source\typevault

# Debug 版
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# Release 版（使用 debug 签名，可直接安装）
.\gradlew.bat assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

> 如遇 Gradle 缓存锁定问题，可加 `--no-daemon --no-build-cache --max-workers=1` 参数。

---

## 十三、版本历史

### v0.4.1 (2026-09-19)

- **增强：`get_screen_text` 支持控件坐标与尺寸**
  - 新增 `includeBounds` 参数（bool，默认 false）。设为 true 时，返回每个文本元素的完整矩形坐标（left/top/right/bottom）、宽高、所属类名，使 AI 能精确定位屏幕上的文字位置，配合 `tap_screen` 等 UI 操控工具实现自动化操作。
  - 默认行为不变（仅返回纯文本列表），保持向后兼容。
- **安全加固**：`android:allowBackup` 设为 `false`，防止通过 adb backup 导出应用数据。
- **文档更新**：清理文档中隐藏功能（差分上传/Skill 导入）的残留描述，统一 MCP 工具列表与实际实现一致。

### v0.4.0

- **新增 17 个 MCP 工具**，覆盖设备信息查询、远程 UI 操控、通知历史等全方面能力
- **修复：MCP 请求中文乱码**（NanoHTTPD parseBody US-ASCII → 直接读取原始字节 UTF-8 解码）
- **修复：MCP 协议兼容性**（notifications/initialized 全名匹配、server/discover 方法）
- **安全加固**：open_app 包名校验、input_text 单引号包裹防注入
- **稳定性修复**：递归深度限制、并发截图唯一文件名、get_installed_apps 限制 200 条

### v0.3.2

- 修复：长时间运行后无障碍/通知监听服务状态显示异常（双重检测策略）

### v0.3.1

- 初始发布版本
