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
  python daytrace.py progress [date]              # 上传进度
  python daytrace.py skills                       # 上传 Skill 列表
  python daytrace.py tools                        # 工具清单

环境变量: DAYTRACE_URL / DAYTRACE_TOKEN 可覆盖默认值。
"""
import sys
import json
import re
import datetime
import urllib.request
import urllib.error
import os

# 默认连接信息(可从环境变量覆盖)
MCP_URL = os.environ.get("DAYTRACE_URL", "https://changes-nested-procedures-solved.trycloudflare.com/mcp")
MCP_TOKEN = os.environ.get("DAYTRACE_TOKEN", "xueminghui")


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
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print("HTTP 错误:", e.code, e.read().decode("utf-8", "replace"), file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print("连接失败(检查 URL/网络):", e.reason, file=sys.stderr)
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

    行格式: `微信 · 大胡宝贝 (com.tencent.mm): 18 messages`
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
    """从 `微信 · 大胡宝贝` 中取出联系人部分(最后一段 ` · ` 之后)。"""
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
    """ISO 时间(2026-08-24 或 2026-08-24 15:00:00)或毫秒时间戳 -> 毫秒。"""
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


def cmd_progress(args):
    date = args[0] if args else ""
    print(text_of(call_tool("get_upload_progress", {"date": date})))


def cmd_skills(_args):
    print(text_of(call_tool("list_skills")))


def cmd_tools(_args):
    print(text_of(call_tool("tools/list", {}, rid=0)))


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
    "progress": cmd_progress,
    "skills": cmd_skills,
    "tools": cmd_tools,
}


def main():
    reconfigure_stdout()
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    COMMANDS[sys.argv[1]](sys.argv[2:])


if __name__ == "__main__":
    main()
