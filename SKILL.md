---
name: daytrace-timeline
version: 2.0.0
description: >
  Query phone activity and message data via DayTrace MCP server.
  Use when the user asks about their phone activity, timeline, app usage,
  messages in specific apps, conversation history, or message statistics.
  Triggers: 今天做了什么, 时间线, 聊天记录, 手机使用记录, 在XX里聊了什么,
  消息统计, 用了什么app, conversation history, phone activity, timeline.
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
tools/call search_messages {"keyword":"产品需求","limit":50}
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
