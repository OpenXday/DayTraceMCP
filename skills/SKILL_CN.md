---
name: daytrace-mcp
version: 0.3.1
description: 通过 DayTrace MCP Server（远程 Streamable HTTP 端点）连接手机使用轨迹数据，生成各类报表；还支持远程拍照、录像、录音。触发场景：用户说"总结我的一天/按时间线总结""按 app 总结""按联系人/会话总结""周报/本周总结""联系人排行榜""增量分析/自上次以来的变化/新增了什么""最近消息""搜索某关键词""消息统计""导出/推送今天的时间线""拍照""录像""录音""前置/后置摄像头"等，或任何需要查询手机通知、消息、App 使用记录并汇总，或远程采集照片/视频/音频的需求。
argument-hint: [报表类型，如 timeline/app/conversation/search/stats/export，可选日期或关键词]
allowed-tools: bash, fetch_output, read_file
---

# DayTrace MCP · 手机使用轨迹报表

DayTrace 是用户手机上运行的记录服务（屏幕快照/App 切换/IM 消息/通知），通过 MCP
暴露查询接口。本 skill 指导如何调用它生成报表。

## 1. 连接信息（重要）

- **端点**：由环境变量 `DAYTRACE_URL` 提供（MCP Streamable HTTP 端点，如
  Cloudflare Tunnel 地址；**不在此文档内置任何真实端点**）
- **认证**：由环境变量 `DAYTRACE_TOKEN` 提供（Bearer token，向用户确认）
- **协议**：MCP Streamable HTTP，JSON-RPC 2.0，POST 到端点

> ⚠️ 每次请求都需带 `Content-Type: application/json` 和 `Authorization` 头。
> 该服务为无状态端点，无需会话 Cookie；`notifications/initialized` 可能报
> "Method not found"，忽略即可，`initialize` 成功后直接 `tools/call`。

## 2. 调用方式（推荐：helper 脚本）

本 skill 自带 `daytrace.py` 助手（与 SKILL.md 同目录，即
`${CLAUDE_SKILL_DIR}/daytrace.py`），封装了连接、认证、UTF-8 编码与
联系人名自动匹配，**优先使用它**，避免裸 curl 的中文编码坑：

```bash
python "${CLAUDE_SKILL_DIR}/daytrace.py" <命令> [参数]
```

常用命令示例：

```bash
# 时间线（date 为空=今天）
python daytrace.py timeline 2026-01-01
# 消息统计 / App 列表 / 会话列表
python daytrace.py stats
python daytrace.py apps
python daytrace.py conversations
# 会话详情 —— 联系人名自动匹配，无需记 @ 后缀全名
python daytrace.py conv com.tencent.mm 联系人甲
# 关键词搜索
python daytrace.py search 关键词示例
# 最近消息 / 日期区间（ISO 时间或毫秒时间戳）
python daytrace.py recent 50 all
python daytrace.py messages "2026-01-01 09:00:00" "2026-01-01 23:59:59"
# 导出 / 上传进度 / 上传 Skill 列表
python daytrace.py export 2026-01-01
python daytrace.py progress 2026-01-01
python daytrace.py skills
```

环境变量 `DAYTRACE_URL` / `DAYTRACE_TOKEN` **必填**（连接端点与认证凭据，
不在文档中内置）。示例：

```bash
DAYTRACE_URL=https://<mcp-endpoint>/mcp DAYTRACE_TOKEN=<token> \
  python "${CLAUDE_SKILL_DIR}/daytrace.py" timeline
```

### 备用：裸 curl（脚本不可用时）

```bash
# 1) 初始化（握手，确认服务器在线）
curl -s -X POST "$MCP_URL" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MCP_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"atomcode","version":"1.0.0"}}}'

# 2) 调用工具（通用模板：把 name/arguments 换成下面任一工具）
curl -s -X POST "$MCP_URL" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MCP_TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_timeline","arguments":{"date":"2026-01-01"}}}'
```

返回 `result.content[0].text` 即结果正文。curl 传中文参数时建议用
python/JSON 构造请求体（见 daytrace.py），避免 shell 编码问题。

## 3. 工具目录（23 个方法）

### 消息查询与时间线（16 个）

| 工具 | 参数 | 用途 |
|------|------|------|
| `get_timeline` | `date` (YYYY-MM-DD, 空=今天) | 当天时间线 Markdown（表格：时间/来源/应用/方向/联系人/内容） |
| `get_messages_by_date` | `startTime`/`endTime` (毫秒时间戳) | 日期范围内的全部记录（含快照文本） |
| `get_recent_messages` | `limit`(默认50,最大500), `direction`(outgoing/incoming/all), `appPackage` | 最近消息 |
| `search_messages` | `keyword`(必填), `limit` | 按关键词搜索 |
| `get_message_stats` | 无 | 消息统计（总数、收发、按 app 计数） |
| `get_app_list` | 无 | 有记录的 App 列表 |
| `list_conversations` | 无 | 会话列表（app + 联系人/群组 + 消息数） |
| `get_conversation` | `appPackage`(必填), `contactOrGroup`, `limit`(默认200,最大1000) | 某 App 内单联系人/群的完整对话（按时间正序） |
| `export_timeline` | `date` | 在设备端导出时间线为 Markdown 文件，返回文件路径 |
| `push_webhook_summary` | `date` | 把当天总结推送到已配置 Webhook（如 Chanify） |
| `push_timeline_diff` | `date`, `forceFull` | 增量上传时间线到已加载的上传 Skill |
| `get_upload_progress` | `date` | 查询某天上传进度 |
| `list_skills` | 无 | 列出可用的上传 Skill（内置 + 导入的 .skill.md） |
| `load_skill` | `skillId`(必填) | 激活某个上传 Skill |
| `import_skill` | `url`(必填) | 从网络 URL 导入 .skill.md |
| `clear_all_messages` | 无 | ⚠️ 危险：删除全部记录，**绝不调用** |

### 远程拍照 / 录像 / 录音（7 个）

| 工具 | 参数 | 用途 |
|------|------|------|
| `take_photo` | `camera`(back/front), `resolution`(720p/1080p/4k/max/WxH), `flash`(off/on/auto) | 远程拍照，返回 Base64 JPEG + 拍摄参数（焦距/ISO/曝光/光圈/白平衡） |
| `record_video` | `camera`(back/front), `duration`(1-60秒), `resolution`(720p/1080p/480p), `returnBase64`(bool) | 录像，默认 6s 720p MP4（含音频），返回下载 URL 或 Base64 |
| `record_audio` | `duration`(1-300秒), `format`(aac/wav) | 录音，默认 10s AAC/M4A，返回 Base64 音频 |
| `list_captures` | 无 | 列出设备上所有已拍摄的照片/视频/音频文件 |
| `delete_capture` | `filename`(必填), `category`(photos/videos/audio) | 按文件名删除拍摄文件 |
| `grant_camera_mic_permissions` | 无 | 通过 Shizuku 自动授予 CAMERA 和 RECORD_AUDIO 权限（pm grant） |

> **权限要求**：使用拍照/录像/录音工具前，必须先授予相机和麦克风权限。
> 首次使用时调 `grant_camera_mic_permissions`（需 Shizuku 运行且已授权），
> 或在 App 首页「一键开启所有权限」中自动授权，也可手动在系统设置中授予。
>
> **文件下载**：拍摄的文件可通过 HTTP 下载：
> - 照片：`GET /captures/photos/<filename>`
> - 视频：`GET /captures/videos/<filename>`
> - 音频：`GET /captures/audio/<filename>`
>
> **文件存储位置**：`Android/data/com.daytrace/files/captures/{photos,videos,audio}/`

**日期换算**：`date` 用 `YYYY-MM-DD`；`get_messages_by_date` 用毫秒时间戳
（本地时区 00:00:00 ~ 23:59:59.999）。可用
`date -d "2026-01-01 00:00:00" +%s%3N` 生成起点（示例日期）。

## 4. 报表流程

### 4.1 按时间线总结（最常用）
1. 调 `get_timeline`，`date` 传用户要的日期（缺省=今天）。
2. 输出可能巨大（全天快照可达 ~1MB）。若被截断，用 `fetch_output` 按
   offset 分段读取完整内容（每次 64KB 左右，直到读完 artifact）。
3. 提炼时间线：按时间段（凌晨/清晨/上午/中午/下午/晚间）归纳，标出
   - 家人/重要联系人的通话、消息（注意 通知增量总结 里的核心要点）
   - 工作事项（钉钉/飞书通知中的截止时间）
   - 娱乐/学习内容（浏览的仓库、公众号文章、视频）
   - 健康数据（步数/距离/热量/睡眠，出现在桌面快照里）
4. 输出格式：总览（天气/健康/关键词）+ 分段时间线 + 今日要点&待办表格。

### 4.2 按 App 总结
1. 调 `get_message_stats` 看总量与各 App 计数（注意：这是**全量累计**，不是单日）。
2. 单日口径用 `get_messages_by_date` 或 `get_timeline` 过滤；需要分 App 明细时
   调 `get_app_list` 确认包名，再 `get_conversation` 取该 App 内容。
3. 结论给出：使用最多的 App、收发比例、主要用途分类。

### 4.3 按联系人/会话总结
1. `list_conversations` 拿全部会话（含消息数），筛选用户关心的联系人/群。
2. 取会话消息并总结聊天要点。**优先用脚本自动匹配**：
   ```bash
   python daytrace.py conv <appPackage> <联系人名> [limit]
   ```
   脚本会自动从会话列表里找到最匹配的联系人（精确 > 子串），无需手动
   拼 `@群昵称` 这类全名；`conv` 会先打印匹配到的实际名称再返回内容。
3. 若脚本匹配不到（无接近名称），先 `python daytrace.py conversations`
   看完整名称，再 `python daytrace.py call get_conversation '{"appPackage":"...","contactOrGroup":"..."}'` 手动传参。
   - ⚠️ 实测：`contactOrGroup` 需与 `list_conversations` 返回的名称**精确
     一致**（含 @ 后缀如 `联系人甲 @群昵称`）；名称不匹配会返回
     `Total: 0 messages`。裸 curl/手写参数时务必用列表里的完整名称。
   - 中文参数请用 daytrace.py（统一 UTF-8），避免 shell 编码问题。

### 4.4 关键词搜索
- `search_messages` 传 `keyword`，可加 `limit`。适用于找某话题/某人的消息。

### 4.5 导出 / 推送
- 设备端落盘：`export_timeline`（返回路径，告知用户）。
- 推 Webhook：`push_webhook_summary`（返回成功/失败）。
- 增量上传：先 `list_skills` 看有哪些上传 Skill → `load_skill` 激活 →
  `push_timeline_diff`；用 `get_upload_progress` 确认进度。

### 4.6 周报（按时间线周总结）
1. 确认周范围（默认最近 7 天，含今天；或用户指定起止日期）。
2. 逐日调 `get_timeline`（每天一次，`date` 传 YYYY-MM-DD），或一次
   `get_messages_by_date`（startTime = 周首日 00:00，endTime = 周日
   23:59:59.999）。单日输出可能 ~1MB，注意分段读取。
3. 聚合方式：
   - **按天**：每日一条小结（工作/家人/娱乐/健康），形成周时间轴。
   - **跨天主题**：把同主题事件合并（如"便携机项目本周进展""家人通话频次"）。
   - **数据看板**：统计每天步数/热量（来自桌面快照）、App 使用倾向、
     消息收发趋势。
4. 输出格式：周总览（关键词/健康/工作重心）+ 每日小结表 +
   跨周对比（若数据跨多周，对比本周 vs 上周）。

### 4.8 增量分析（自上次以来的变化）
用途：只看「上次分析之后新增/变化的记录」，避免每次全量重读，适合
每日巡检、持续跟踪某话题或项目进展。

1. **确定基线**：先确认上次分析的截止时间（用户告知，或按上次报表的
   结束时间推算；没有基线则退化为全量分析，并说明口径）。
2. **增量同步（设备侧）**：`list_skills` 看可用上传 Skill → `load_skill`
   激活 → `push_timeline_diff`（默认只发增量，`forceFull` 强制全量）→
   `get_upload_progress` 确认已上传/待上传量。适用于把新记录增量备份到
   Biji/OpenAPI/Webhook 等目标，按 日期+时间 命名防覆盖。
3. **增量读取（分析侧）**：
   - `get_messages_by_date`：`startTime` = 基线时刻毫秒时间戳，
     `endTime` = 现在，只取新增记录；数据大仍要分段读完。
   - 或逐日 `get_timeline` 对比：只处理基线之后那几天。
4. **增量对比与输出**：
   - 新增：新消息/新通知/新联系人（对比 `list_conversations` 基线前后
     的会话集合）、新任务与截止时间。
   - 变化：消息量、App 使用、某话题/联系人的频次升降（对比基线）。
   - 输出格式：分析区间（基线 ~ 现在）/ 新增条数 / 变化要点表 /
     相比基线的新增待办与提醒。


## 5. 注意事项

- **数据量大**：`get_timeline` / `get_messages_by_date` 一次可返回数百 KB，
  单次 curl 会被截断，务必用 `fetch_output` 分段读完再总结，不要凭残缺
  输出下结论。
- **快照噪音多**：大量记录是锁屏/桌面/系统组件的快照文本（电池、天气、
  步数、通知列表），总结时以「通知」「切换」「消息」类记录为主，快照用于
  补充健康/天气信息。
- **隐私**：记录含对话内容，报表只在用户要求范围内呈现，不外传。
- **clear_all_messages 是毁灭性操作**，任何情况下都不调用。
- 汇报结果时说明数据口径（如"全量累计 vs 单日"），避免误导。

## 6. 远程拍照 / 录像 / 录音

### 6.1 权限准备

首次使用拍照/录像/录音功能前，必须确保相机和麦克风权限已授予：

```bash
# 方法1：通过 Shizuku 自动授权（推荐）
python daytrace.py grant

# 方法2：裸 curl
# 先 initialize，再调 grant_camera_mic_permissions
curl -s -X POST "$MCP_URL" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MCP_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"grant_camera_mic_permissions","arguments":{}}}'
```

> 也可在 DayTrace App 首页点击「一键开启所有权限」按钮，会自动通过 Shizuku
> 授予无障碍、通知监听、相机、麦克风四项权限。

### 6.2 拍照

```bash
# 后置摄像头，1080p（默认）
python daytrace.py photo back 1080p

# 前置摄像头，720p，开闪光灯
python daytrace.py photo front 720p on

# 裸 curl
curl -s -X POST "$MCP_URL" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MCP_TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"take_photo","arguments":{"camera":"back","resolution":"1080p"}}}'
```

返回结果包含：
- 拍摄参数：分辨率、摄像头方向、闪光灯模式
- 硬件参数：焦距、ISO、曝光时间、光圈值、白平衡状态
- Base64 编码的 JPEG 图片数据
- 下载 URL：`http://<设备>/captures/photos/<文件名>`

分辨率选项：`720p` / `1080p`(默认) / `4k` / `max` / 自定义 `WxH`(如 `1920x1080`)

### 6.3 录像

```bash
# 后置摄像头，6秒，720p（默认）
python daytrace.py video back 6 720p

# 前置摄像头，10秒，1080p
python daytrace.py video front 10 1080p
```

默认返回下载 URL（视频文件较大，不建议 Base64 内嵌）。
设置 `returnBase64: true` 可获取 Base64 视频数据。

时长：1-60 秒。分辨率：`720p`(默认) / `1080p` / `480p`。

### 6.4 录音

```bash
# 10秒 AAC 格式（默认）
python daytrace.py audio 10 aac

# 30秒 WAV 格式
python daytrace.py audio 30 wav
```

返回 Base64 编码的音频数据 + 下载 URL。
时长：1-300 秒。格式：`aac`(默认, M4A 容器) / `wav`。

### 6.5 文件管理

```bash
# 列出所有拍摄文件
python daytrace.py captures

# 删除指定文件
python daytrace.py delete photo_2026-08-24_14-30-00-000.jpg photos
python daytrace.py delete video_2026-08-24_14-30-00-000.mp4 videos
python daytrace.py delete audio_2026-08-24_14-30-00-000.m4a audio
```

### 6.6 下载文件

拍摄的文件可通过 HTTP GET 下载：

```bash
# 照片
curl -o photo.jpg -H "Authorization: Bearer $MCP_TOKEN" \
  "https://<tunnel-url>.trycloudflare.com/captures/photos/<filename>"

# 视频
curl -o video.mp4 -H "Authorization: Bearer $MCP_TOKEN" \
  "https://<tunnel-url>.trycloudflare.com/captures/videos/<filename>"

# 音频
curl -o audio.m4a -H "Authorization: Bearer $MCP_TOKEN" \
  "https://<tunnel-url>.trycloudflare.com/captures/audio/<filename>"
```
