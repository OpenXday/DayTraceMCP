---
name: daytrace-timeline
version: 0.4.0
description: >
  Query phone activity and message data via DayTrace MCP server.
  Use when the user asks about their phone activity, timeline, app usage,
  messages in specific apps, conversation history, or message statistics.
  Also supports remote camera capture (photo/video) and audio recording.
  Triggers: what did I do today, timeline, chat history, phone usage,
  what was discussed in XX, message stats, which apps were used,
  take a photo, record video, record audio, front/back camera,
  conversation history, phone activity, timeline.
---

# DayTrace Timeline

Query phone activity and message data via DayTrace MCP server.

## Connection

DayTrace app runs an embedded MCP server on the phone, exposed via Cloudflare tunnel:

- **URL**: `https://<tunnel-url>.trycloudflare.com/mcp`
- **Auth** (if enabled): `Authorization: Bearer <token>` header

The tunnel URL is shown in the DayTrace notification when MCP + tunnel are running.
It changes on each restart.

## Available Tools

### Timeline & Export

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `get_timeline` | Get a day's timeline as Markdown (chronological summary of all phone activity) | `date` (YYYY-MM-DD, empty = today) |
| `export_timeline` | Export timeline as a Markdown file on the device, returns file path | `date` (YYYY-MM-DD, empty = today) |
| `push_webhook_summary` | Push the day's summary to the configured Webhook (e.g. Chanify) | `date` (YYYY-MM-DD, empty = today) |

### Message Query

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `get_recent_messages` | Get recent messages from all IM apps | `limit` (1-500, default 50), `direction` (outgoing/incoming/all), `appPackage` (filter by app) |
| `search_messages` | Search messages by keyword | `keyword` (required), `limit` (1-500, default 50) |
| `get_messages_by_date` | Get messages within a time range (epoch ms) | `startTime` (ms), `endTime` (ms) |
| `get_conversation` | Get full conversation for an app + optional contact, chronological order | `appPackage` (required), `contactOrGroup` (empty = all), `limit` (1-1000, default 200) |
| `list_conversations` | List all conversations (app + contact/group pairs) | none |
| `get_message_stats` | Get message statistics (total count, by app, by direction) | none |
| `get_app_list` | List all apps that have captured messages with counts | none |

### Data Management

| Tool | Description | Warning |
|------|-------------|---------|
| `clear_all_messages` | Delete ALL captured messages | **Irreversible! Use with caution.** |

### Remote Camera & Microphone

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `take_photo` | Remote capture a photo. Returns base64 JPEG + capture parameters (ISO, exposure, aperture, etc.) | `camera` (back/front), `resolution` (720p/1080p/4k/max/WxH), `flash` (off/on/auto) |
| `record_video` | Record a short video (default 6s 720p MP4 with audio). Returns download URL or base64. | `camera` (back/front), `duration` (1-60s), `resolution` (720p/1080p/480p), `returnBase64` (bool) |
| `record_audio` | Record audio from microphone (default 10s AAC/M4A). Returns base64 audio. | `duration` (1-300s), `format` (aac/wav) |
| `list_captures` | List all captured photos, videos, and audio files on device | none |
| `delete_capture` | Delete a captured file by filename | `filename` (required), `category` (photos/videos/audio/screenshots) |
| `grant_camera_mic_permissions` | Grant CAMERA and RECORD_AUDIO via Shizuku (shell pm grant) | none |

### Screen Capture & Text Extraction

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `take_screenshot` | Capture a screenshot of the current screen via Shizuku screencap. Returns base64 image + screen metadata. | `format` (png/jpeg), `quality` (1-100), `returnBase64` (bool, default true) |
| `get_screen_text` | Extract all visible text from the current screen via accessibility service. Text is sorted top-to-bottom by Y position. | `includeBounds` (bool, default false) |

**Requirements**:
- `take_screenshot` requires Shizuku running and authorized (for shell screencap command)
- `get_screen_text` requires the accessibility service enabled (for view hierarchy traversal)

### Device Info & System Control (17)

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `get_device_info` | Get device info: model, Android version, battery, memory, storage, screen, network | none |
| `get_app_usage_stats` | Get today's app usage stats (foreground time per app) | none |
| `get_installed_apps` | List all installed apps with package name, version, system flag | `includeSystem` (bool) |
| `get_battery_history` | Get battery details: level, charging, health, temp, voltage | none |
| `get_clipboard` | Read system clipboard content | none |
| `set_clipboard` | Write text to system clipboard | `text` (required) |
| `send_notification` | Send a local notification to the phone | `title`, `message` (required), `priority` |
| `get_notification_log` | Get recent notification history (in-memory cache, last 100) | `limit` (1-100) |
| `send_text_message` | Send SMS to a phone number | `phoneNumber`, `message` (required) |
| `take_screenshot_region` | Capture a screenshot of a specific screen region | `left`, `top`, `right`, `bottom` (required), `format`, `quality` |
| `get_screen_interactive_elements` | Extract all clickable buttons/inputs with coordinates | none |
| `tap_screen` | Simulate a tap at screen coordinates | `x`, `y` (required) |
| `input_text` | Type text into focused input field (ASCII direct, CJK via clipboard paste) | `text` (required) |
| `press_key` | Simulate key press (home, back, recent, volume, etc.) | `key` (required) |
| `swipe` | Simulate swipe gesture | `startX`, `startY`, `endX`, `endY` (required), `durationMs` |
| `open_app` | Launch an app by package name | `packageName` (required), `activity` |

**Requirements**:
- `take_screenshot_region` / `tap_screen` / `input_text` / `press_key` / `swipe` / `open_app` require Shizuku
- `get_screen_interactive_elements` requires accessibility service
- `send_text_message` requires SEND_SMS permission (grant via Shizuku `pm grant`)
- `get_app_usage_stats` requires PACKAGE_USAGE_STATS permission

**Available keys for `press_key`**: home, back, recent, menu, power, volume_up, volume_down, volume_mute, delete, enter, escape, tab, space, search, camera, call, end_call, notification, screenshot

**Permissions**: Camera/mic permissions must be granted before using capture tools.
Call `grant_camera_mic_permissions` first (requires Shizuku running), or grant manually in Android Settings.

**File download**: Captured files can be downloaded via HTTP:
- Photos: `GET /captures/photos/<filename>`
- Videos: `GET /captures/videos/<filename>`
- Audio: `GET /captures/audio/<filename>`
- Screenshots: `GET /captures/screenshots/<filename>`

## App Package Names

Common IM app package names for use with `appPackage` parameter:

| App | Package |
|-----|--------|
| WeChat | `com.tencent.mm` |
| QQ | `com.tencent.mobileqq` |
| Feishu / Lark | `com.ss.android.lark` |
| DingTalk | `com.alibaba.android.rimet` |
| Telegram | `org.telegram.messenger` |
| WhatsApp | `com.whatsapp` |
| Discord | `com.discord` |
| Slack | `com.slack` |
| LINE | `jp.naver.line.android` |
| Bilibili | `com.bilibili.app.in` |
| Weibo | `com.sina.weibo` |
| Douyin | `com.ss.android.ugc.aweme` |

## Common Patterns

### Remote capture a photo (back camera, 1080p)

```
tools/call take_photo {"camera":"back","resolution":"1080p"}
```

Returns base64 JPEG data + capture parameters (ISO, focal length, aperture, exposure time, white balance).
Use `camera: "front"` for front-facing camera. Flash: `off` (default) / `on` / `auto`.

### Record a short video (6s, 720p)

```
tools/call record_video {"camera":"back","duration":6,"resolution":"720p"}
```

Returns a download URL by default. Set `returnBase64: true` to get inline base64 video data.
Duration: 1-60 seconds. Resolution: 720p (default) / 1080p / 480p.

### Record audio (10s, AAC)

```
tools/call record_audio {"duration":10,"format":"aac"}
```

Returns base64-encoded audio data. Duration: 1-300 seconds. Format: `aac` (M4A, default) / `wav`.

### Grant camera/mic permissions via Shizuku

```
tools/call grant_camera_mic_permissions {}
```

Requires Shizuku running and authorized. Call this before first use of capture tools.

### Download a captured file

Files are downloadable via HTTP GET:
```
GET /captures/photos/<filename>
GET /captures/videos/<filename>
GET /captures/audio/<filename>
```

Use `list_captures` to list all files with their filenames.

### Get today's timeline (most common)

```
tools/call get_timeline {"date":""}
```

### Ask "what did I do today"

Call `get_timeline` with empty date (defaults to today). The Markdown output includes
app switches, input captures, screen snapshots, and notifications — a complete
chronological view of phone activity.

### Search messages by keyword

```
tools/call search_messages {"keyword":"product requirements","limit":50}
```

### Get conversation for a specific app

```
tools/call get_conversation {"appPackage":"com.ss.android.lark","contactOrGroup":"","limit":200}
```

If `contactOrGroup` is empty, returns all messages for that app.
Set it to a contact name to filter to a specific conversation.

### Get messages for a time range

Timestamps are in milliseconds since epoch. To query today from 08:00 to now:

```
tools/call get_messages_by_date {"startTime":1724131200000,"endTime":1724174400000}
```

### Get message statistics

```
tools/call get_message_stats {}
```

Returns total count, outgoing/incoming breakdown, and per-app counts.

## Notes

- **Date format**: `YYYY-MM-DD` for timeline tools; empty string = today
- **Timestamps**: milliseconds since epoch for date range tools
- **Large results**: output may be truncated; reduce `limit` or narrow the time range
- **Data is local**: all data is stored on the phone; no cloud sync
- **Direction**: `outgoing` = sent by you (captured via accessibility), `incoming` = received (captured via notifications)
- **Camera/Mic permissions**: Must be granted before using `take_photo`/`record_video`/`record_audio`. Call `grant_camera_mic_permissions` first (requires Shizuku), or grant manually in Android Settings. The app's one-click grant button also grants these.
- **Photo resolution**: `720p` / `1080p` (default) / `4k` / `max` / custom `WxH` (e.g. `1920x1080`). The tool picks the closest supported size.
- **Video resolution**: `720p` (default) / `1080p` / `480p`. The tool picks the closest supported size ≤ target.
- **Base64 data**: Photo and audio results include base64-encoded file data inline. Video returns a download URL by default; set `returnBase64: true` for inline data (may be large).
- **Capture files location**: Files are stored in `Android/data/com.daytrace/files/captures/{photos,videos,audio}/` on the device.
