#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DayTrace MCP 命令行助手 —— 配合 daytrace-mcp SKILL 使用。

修复两个实测问题:
  1. get_conversation 的 contactOrGroup 必须与 list_conversations 返回的
     名称精确一致(含 @ 后缀), 模糊名会返回 0 条 —— 本脚本自动匹配。
  2. shell 中传中文参数易出现编码问题 —— 本脚本统一用 UTF-8 JSON。

用法:
  python daytrace.py call <tool> [json_args]
  python daytrace.py timeline [date]              # 时间线
  python daytrace.py stats                        # 消息统计
  python daytrace.py apps                         # App 列表
  python daytrace.py conversations                # 会话列表
  python daytrace.py conv <app> <name> [limit]    # 会话详情(自动匹配名称)
  python daytrace.py search <keyword> [limit]     # 关键词搜索
  python daytrace.py recent [limit] [direction] [app]
  python daytrace.py messages <start> <end>       # 日期区间(ISO 或毫秒)
  python daytrace.py export [date]                # 导出时间线
  python daytrace.py tools                        # 工具清单
  # 远程拍照/录像/录音
  python daytrace.py grant                        # 通过 Shizuku 授予相机/麦克风权限
  python daytrace.py photo [camera] [resolution] [flash]  # 拍照
  python daytrace.py video [camera] [duration] [resolution]  # 录像
  python daytrace.py audio [duration] [format]     # 录音
  python daytrace.py captures                     # 列出所有拍摄文件
  python daytrace.py delete <filename> [category]  # 删除拍摄文件

环境变量(必填): DAYTRACE_URL / DAYTRACE_TOKEN 用于连接 DayTrace MCP 端点。
"""
import sys
import json
import re
import datetime
import urllib.request
import urllib.error
import os

# 连接信息: 仅从环境变量读取, 不内置任何默认值, 避免泄露端点与凭据
MCP_URL = os.environ.get("DAYTRACE_URL", "")
MCP_TOKEN = os.environ.get("DAYTRACE_TOKEN", "")


def check_env():
    """校验必需的环境变量, 缺失时给出清晰提示。"""
    missing = [name for name, val in
               (("DAYTRACE_URL", MCP_URL), ("DAYTRACE_TOKEN", MCP_TOKEN))
               if not val]
    if missing:
        print("缺少环境变量: " + ", ".join(missing), file=sys.stderr)
        print("示例:", file=sys.stderr)
        print("  DAYTRACE_URL=https://<mcp-endpoint>/mcp DAYTRACE_TOKEN=<token>"
              " python daytrace.py stats", file=sys.stderr)
        sys.exit(1)


def reconfigure_stdout():
    """Windows 下确保 stdout 用 UTF-8, 避免中文乱码。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass


def call_tool(name, arguments=None, rid=1):
    """调用 MCP tools/call, 返回解析后的 JSON。"""
    if arguments is None:
        arguments = {}
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": rid,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }).encode("utf-8")
    req = urllib.request.Request(MCP_URL, data=payload, headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + MCP_TOKEN,
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            print("服务器返回了非 JSON 内容(可能隧道异常或 HTML 错误页):\n"
                  + raw[:800], file=sys.stderr)
            sys.exit(1)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        print("HTTP 错误:", e.code, body[:800], file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print("连接失败(检查 URL/网络):", e.reason, file=sys.stderr)
        sys.exit(1)
    except TimeoutError:
        print("请求超时(60s), 服务器可能繁忙或隧道异常", file=sys.stderr)
        sys.exit(1)


def text_of(result):
    """提取 MCP 结果中的纯文本内容。"""
    try:
        parts = result["result"]["content"]
        return "\n".join(p.get("text", "") for p in parts if p.get("type") == "text")
    except (KeyError, TypeError):
        return json.dumps(result, ensure_ascii=False, indent=2)


def parse_conversations(raw):
    """解析 list_conversations 的文本输出为 [(label, app, count), ...]。

    行格式: `微信 · 联系人甲 (com.tencent.mm): 18 messages`
    或纯 App: `tv.danmaku.bili (tv.danmaku.bili): 3050 messages`
    """
    entries = []
    pattern = re.compile(r"^(.*) \((.+)\): (\d+) messages$")
    for line in raw.splitlines():
        line = line.strip()
        m = pattern.match(line)
        if m:
            label, app, count = m.group(1).strip(), m.group(2).strip(), int(m.group(3))
            entries.append((label, app, count))
    return entries


def contact_part(label):
    """从 `微信 · 联系人甲` 中取出联系人部分(最后一段 ` · ` 之后)。"""
    if " · " in label:
        return label.split(" · ")[-1].strip()
    return label.strip()


def find_best_contact(query_app, query_name, entries):
    """在会话列表里找最匹配的联系人, 返回 (app, contact_name) 或 None。

    匹配规则: 先按 App 过滤(包名相等或互相包含), 再对联系人名打分:
      精确相等 > 查询名是候选名的子串 > 候选名是查询名的子串。
    """
    app_matched = []
    for label, app, count in entries:
        if app == query_app or query_app in app or app in query_app:
            app_matched.append((label, app, count, contact_part(label)))

    if not app_matched:
        return None, "未找到 App 匹配的会话: " + query_app

    scored = []
    for label, app, count, cname in app_matched:
        if cname == query_name:
            score = 3
        elif query_name in cname:
            score = 2
        elif cname in query_name:
            score = 1
        else:
            continue
        scored.append((score, count, label, app, cname))

    if not scored:
        hints = ", ".join(cname for _, _, _, _, cname in app_matched[:8])
        return None, "该 App 下没有名称接近的联系人。现有: " + (hints or "(空)")

    scored.sort(key=lambda x: (-x[0], -x[1]))
    return (scored[0][3], scored[0][4]), None


# ---------------- 子命令 ----------------

def cmd_call(args):
    if not args:
        print("用法: call <tool> [json_args]", file=sys.stderr)
        sys.exit(1)
    tool = args[0]
    arguments = {}
    if len(args) > 1:
        try:
            arguments = json.loads(args[1])
        except json.JSONDecodeError:
            print("json_args 不是合法 JSON", file=sys.stderr)
            sys.exit(1)
    print(text_of(call_tool(tool, arguments)))


def cmd_timeline(args):
    date = args[0] if args else ""
    print(text_of(call_tool("get_timeline", {"date": date})))


def cmd_stats(_args):
    print(text_of(call_tool("get_message_stats")))


def cmd_apps(_args):
    print(text_of(call_tool("get_app_list")))


def cmd_conversations(_args):
    print(text_of(call_tool("list_conversations")))


def cmd_conv(args):
    if len(args) < 2:
        print("用法: conv <app> <name> [limit]", file=sys.stderr)
        sys.exit(1)
    app, name = args[0], args[1]
    limit = int(args[2]) if len(args) > 2 else 200

    raw = text_of(call_tool("list_conversations"))
    entries = parse_conversations(raw)
    if not entries:
        print("list_conversations 解析失败, 原始输出:\n" + raw[:500], file=sys.stderr)
        sys.exit(1)

    (best_app, best_name), err = find_best_contact(app, name, entries)
    if err:
        print("自动匹配失败:", err, file=sys.stderr)
        print("可先运行 `python daytrace.py conversations` 查看完整名称。", file=sys.stderr)
        sys.exit(1)

    print(f"自动匹配: {app} / {name} -> {best_app} / {best_name}")
    print(text_of(call_tool("get_conversation", {
        "appPackage": best_app, "contactOrGroup": best_name, "limit": limit,
    })))


def cmd_search(args):
    if not args:
        print("用法: search <keyword> [limit]", file=sys.stderr)
        sys.exit(1)
    kw = args[0]
    limit = int(args[1]) if len(args) > 1 else 50
    print(text_of(call_tool("search_messages", {"keyword": kw, "limit": limit})))


def cmd_recent(args):
    limit = int(args[0]) if args and args[0].isdigit() else 50
    direction = args[1] if len(args) > 1 else "all"
    app = args[2] if len(args) > 2 else ""
    print(text_of(call_tool("get_recent_messages", {
        "limit": limit, "direction": direction, "appPackage": app,
    })))


def to_ms(value):
    """ISO 时间(YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS)或毫秒时间戳 -> 毫秒。"""
    v = str(value).strip()
    if v.isdigit():
        return int(v)
    try:
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
            try:
                return int(datetime.datetime.strptime(v, fmt).timestamp() * 1000)
            except ValueError:
                continue
    except Exception:
        pass
    raise ValueError("无法解析时间: " + v)


def cmd_messages(args):
    if len(args) < 2:
        print("用法: messages <start> <end>  (ISO 或毫秒)", file=sys.stderr)
        sys.exit(1)
    try:
        start = to_ms(args[0])
        end = to_ms(args[1])
    except ValueError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
    print(text_of(call_tool("get_messages_by_date", {
        "startTime": start, "endTime": end,
    })))


def cmd_export(args):
    date = args[0] if args else ""
    print(text_of(call_tool("export_timeline", {"date": date})))


def cmd_tools(_args):
    print(text_of(call_tool("tools/list", {}, rid=0)))


# ---------------- 远程拍照/录像/录音 ----------------

def cmd_grant(_args):
    """通过 Shizuku 授予相机和麦克风权限。"""
    print(text_of(call_tool("grant_camera_mic_permissions")))


def cmd_photo(args):
    """拍照: photo [camera] [resolution] [flash]
    camera: back (默认) / front
    resolution: 1080p (默认) / 720p / 4k / max / WxH
    flash: off (默认) / on / auto
    """
    camera = args[0] if args else "back"
    resolution = args[1] if len(args) > 1 else "1080p"
    flash = args[2] if len(args) > 2 else "off"
    print(text_of(call_tool("take_photo", {
        "camera": camera,
        "resolution": resolution,
        "flash": flash,
    })))


def cmd_video(args):
    """录像: video [camera] [duration] [resolution]
    camera: back (默认) / front
    duration: 6 (默认, 1-60秒)
    resolution: 720p (默认) / 1080p / 480p
    """
    camera = args[0] if args else "back"
    duration = int(args[1]) if len(args) > 1 and args[1].isdigit() else 6
    resolution = args[2] if len(args) > 2 else "720p"
    print(text_of(call_tool("record_video", {
        "camera": camera,
        "duration": duration,
        "resolution": resolution,
    })))


def cmd_audio(args):
    """录音: audio [duration] [format]
    duration: 10 (默认, 1-300秒)
    format: aac (默认, M4A) / wav
    """
    duration = int(args[0]) if args and args[0].isdigit() else 10
    fmt = args[1] if len(args) > 1 else "aac"
    print(text_of(call_tool("record_audio", {
        "duration": duration,
        "format": fmt,
    })))


def cmd_captures(_args):
    """列出所有拍摄文件。"""
    print(text_of(call_tool("list_captures")))


def cmd_delete(args):
    """删除拍摄文件: delete <filename> [category]
    category: photos (默认) / videos / audio / screenshots
    """
    if not args:
        print("用法: delete <filename> [category]", file=sys.stderr)
        sys.exit(1)
    filename = args[0]
    category = args[1] if len(args) > 1 else "photos"
    print(text_of(call_tool("delete_capture", {
        "filename": filename,
        "category": category,
    })))


def cmd_screenshot(args):
    """截屏: screenshot [format] [quality]
    format: png (默认, 无损) / jpeg (压缩)
    quality: 80 (默认, 1-100, 仅 jpeg)
    """
    fmt = args[0] if args else "png"
    quality = int(args[1]) if len(args) > 1 and args[1].isdigit() else 80
    print(text_of(call_tool("take_screenshot", {
        "format": fmt,
        "quality": quality,
    })))


def cmd_screen_text(_args):
    """提取当前屏幕文字。"""
    print(text_of(call_tool("get_screen_text")))


def cmd_device_info(_args):
    """获取设备信息。"""
    print(text_of(call_tool("get_device_info")))


def cmd_usage_stats(_args):
    """获取今日 App 使用时长统计。"""
    print(text_of(call_tool("get_app_usage_stats")))


def cmd_installed_apps(args):
    """列出已安装应用: installed_apps [include_system]
    include_system: true/false (默认 false)
    """
    inc_sys = args[0].lower() == "true" if args else False
    print(text_of(call_tool("get_installed_apps", {"includeSystem": inc_sys})))


def cmd_battery(_args):
    """获取电池信息。"""
    print(text_of(call_tool("get_battery_history")))


def cmd_clipboard(_args):
    """读取剪贴板。"""
    print(text_of(call_tool("get_clipboard")))


def cmd_set_clipboard(args):
    """写入剪贴板: set_clipboard <text>"""
    if not args:
        print("用法: set_clipboard <text>", file=sys.stderr)
        sys.exit(1)
    print(text_of(call_tool("set_clipboard", {"text": " ".join(args)})))


def cmd_notify(args):
    """发送通知: notify <title> <message> [priority]
    priority: low/default/high (默认 default)
    """
    if len(args) < 2:
        print("用法: notify <title> <message> [priority]", file=sys.stderr)
        sys.exit(1)
    title = args[0]
    message = args[1]
    priority = args[2] if len(args) > 2 else "default"
    print(text_of(call_tool("send_notification", {
        "title": title,
        "message": message,
        "priority": priority,
    })))


def cmd_notif_log(args):
    """获取通知历史: notif_log [limit]"""
    limit = int(args[0]) if args and args[0].isdigit() else 50
    print(text_of(call_tool("get_notification_log", {"limit": limit})))


def cmd_sms(args):
    """发送短信: sms <phone_number> <message>"""
    if len(args) < 2:
        print("用法: sms <phone_number> <message>", file=sys.stderr)
        sys.exit(1)
    phone = args[0]
    message = " ".join(args[1:])
    print(text_of(call_tool("send_text_message", {
        "phoneNumber": phone,
        "message": message,
    })))


def cmd_tap(args):
    """点击屏幕: tap <x> <y>"""
    if len(args) < 2:
        print("用法: tap <x> <y>", file=sys.stderr)
        sys.exit(1)
    print(text_of(call_tool("tap_screen", {
        "x": int(args[0]),
        "y": int(args[1]),
    })))


def cmd_input_text(args):
    """输入文字: input_text <text>"""
    if not args:
        print("用法: input_text <text>", file=sys.stderr)
        sys.exit(1)
    print(text_of(call_tool("input_text", {"text": " ".join(args)})))


def cmd_key(args):
    """按键: key <key_name>
    可用: home/back/recent/menu/power/volume_up/volume_down等
    """
    if not args:
        print("用法: key <key_name>", file=sys.stderr)
        sys.exit(1)
    print(text_of(call_tool("press_key", {"key": args[0]})))


def cmd_swipe(args):
    """滑动: swipe <startX> <startY> <endX> <endY> [duration_ms]"""
    if len(args) < 4:
        print("用法: swipe <startX> <startY> <endX> <endY> [duration_ms]", file=sys.stderr)
        sys.exit(1)
    duration = int(args[4]) if len(args) > 4 and args[4].isdigit() else 300
    print(text_of(call_tool("swipe", {
        "startX": int(args[0]),
        "startY": int(args[1]),
        "endX": int(args[2]),
        "endY": int(args[3]),
        "durationMs": duration,
    })))


def cmd_open_app(args):
    """打开 App: open_app <package_name> [activity]"""
    if not args:
        print("用法: open_app <package_name> [activity]", file=sys.stderr)
        sys.exit(1)
    pkg = args[0]
    activity = args[1] if len(args) > 1 else ""
    print(text_of(call_tool("open_app", {
        "packageName": pkg,
        "activity": activity,
    })))


def cmd_interactive(_args):
    """提取屏幕可交互元素。"""
    print(text_of(call_tool("get_screen_interactive_elements")))


COMMANDS = {
    "call": cmd_call,
    "timeline": cmd_timeline,
    "stats": cmd_stats,
    "apps": cmd_apps,
    "conversations": cmd_conversations,
    "conv": cmd_conv,
    "search": cmd_search,
    "recent": cmd_recent,
    "messages": cmd_messages,
    "export": cmd_export,
    "tools": cmd_tools,
    # 远程拍照/录像/录音
    "grant": cmd_grant,
    "photo": cmd_photo,
    "video": cmd_video,
    "audio": cmd_audio,
    "captures": cmd_captures,
    "delete": cmd_delete,
    # 屏幕截图/文字
    "screenshot": cmd_screenshot,
    "screen_text": cmd_screen_text,
    # 设备信息/系统操作
    "device": cmd_device_info,
    "usage": cmd_usage_stats,
    "installed": cmd_installed_apps,
    "battery": cmd_battery,
    "clipboard": cmd_clipboard,
    "set_clipboard": cmd_set_clipboard,
    "notify": cmd_notify,
    "notif_log": cmd_notif_log,
    "sms": cmd_sms,
    "tap": cmd_tap,
    "input_text": cmd_input_text,
    "key": cmd_key,
    "swipe": cmd_swipe,
    "open_app": cmd_open_app,
    "interactive": cmd_interactive,
}


def main():
    reconfigure_stdout()
    check_env()
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    COMMANDS[sys.argv[1]](sys.argv[2:])


if __name__ == "__main__":
    main()
