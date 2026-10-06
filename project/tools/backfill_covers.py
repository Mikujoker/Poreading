#!/usr/bin/env python3
"""批量补封面：给书架上 coverUrl 为空的书，用它自己来源的书源重跑一次详情页，把站点封面写回。

原则：只补空的，且只在**站点确实给出封面 URL** 时才写回；解析不到就保持默认封面（绝不写空值、
不覆盖已有封面）。走的是 app 自己的 Web 接口，不需要编译、不需要登录 PC 侧任何服务。

前置：
  adb forward tcp:1122 tcp:1122        # app 的 Web 服务要开着
用法：
  python3 backfill_covers.py --dry-run                 # 只看会补哪些，不写库
  python3 backfill_covers.py                           # 补全部空封面的书
  python3 backfill_covers.py --limit 5 --name 剑舞       # 只处理书名含「剑舞」的最多 5 本
  python3 backfill_covers.py --source https://a.com/   # 只处理某个来源的书
输出：
  每本一行 ✓补到 / –源没给封面 / ✗源不可用，末尾给汇总。退出码恒为 0（失败项只是报告）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

# 本地端口别走代理（否则被本机代理抢答成 502）
for var in ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
    os.environ.pop(var, None)

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TIME_PREFIX = re.compile(r"^\[\d\d:\d\d\.\d+\] ")


def call(base: str, path: str, payload: dict | None = None, timeout: float = 240) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    request = urllib.request.Request(base + path, data=data, headers={"Content-Type": "application/json"})
    try:
        with OPENER.open(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        return {"isSuccess": False, "errorMsg": f"HTTP {exc.code}"}
    except Exception as exc:  # noqa: BLE001
        return {"isSuccess": False, "errorMsg": f"{type(exc).__name__}: {exc}"}


def cover_of(events: list[dict]) -> str:
    """调试事件按「┌获取封面链接」+ 下一条 └值 成对出现；值可能多行（多张封面），取第一个 http。"""
    previous = ""
    for event in events:
        message = TIME_PREFIX.sub("", event.get("message", ""))
        if "获取封面链接" in previous:
            for line in message.lstrip("└").splitlines():
                if line.strip().lower().startswith("http"):
                    return line.strip()
            return ""
        previous = message
    return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=int(os.environ.get("LEGADO_WEB_PORT", 1122)))
    parser.add_argument("--dry-run", action="store_true", help="只报告，不写回")
    parser.add_argument("--limit", type=int, default=0, help="最多处理几本（0=全部）")
    parser.add_argument("--name", default="", help="只处理书名包含该子串的书")
    parser.add_argument("--source", default="", help="只处理 origin 等于该值的书")
    parser.add_argument("--timeout", type=float, default=60, help="每本书的调试超时（秒）")
    args = parser.parse_args()
    base = os.environ.get("LEGADO_WEB_BASE", f"http://127.0.0.1:{args.port}")

    shelf = call(base, "/getBookshelf", timeout=90)
    if not shelf.get("isSuccess"):
        print(f"✗ 读书架失败：{shelf.get('errorMsg')}（Web 服务开了吗？adb forward 做了吗？）")
        return 1
    books = shelf.get("data") or []
    todo = [b for b in books if not (b.get("coverUrl") or "").strip()]
    if args.name:
        todo = [b for b in todo if args.name in (b.get("name") or "")]
    if args.source:
        todo = [b for b in todo if (b.get("origin") or "") == args.source]
    if args.limit > 0:
        todo = todo[:args.limit]

    print(f"书架 {len(books)} 本，空封面 {len([b for b in books if not (b.get('coverUrl') or '').strip()])} 本，"
          f"本次处理 {len(todo)} 本{'（dry-run）' if args.dry_run else ''}")
    filled = nocover = dead = 0
    for book in todo:
        name = (book.get("name") or "")[:26]
        book_url = book.get("bookUrl") or ""
        if not book_url:
            print(f"  ✗ {name:28} 没有 bookUrl，跳过")
            dead += 1
            continue
        result = call(base, "/debugBookSource", {
            "tag": book.get("origin") or "",
            "key": book_url,
            "timeoutMs": int(args.timeout * 1000),
        }, timeout=args.timeout + 120)
        if not result.get("isSuccess"):
            print(f"  ✗ {name:28} 源不可用：{str(result.get('errorMsg'))[:48]}")
            dead += 1
            continue
        cover = cover_of((result.get("data") or {}).get("events") or [])
        if not cover:
            print(f"  – {name:28} 源没解析出封面（保持默认封面）")
            nocover += 1
            continue
        if args.dry_run:
            print(f"  · {name:28} 可补 → {cover[:64]}")
        else:
            book["coverUrl"] = cover
            saved = call(base, "/saveBook", book, timeout=60)
            print(f"  {'✓' if saved.get('isSuccess') else '✗'} {name:28} → {cover[:64]}")
        filled += 1
    print(f"=== 补到 {filled} / 源没给封面 {nocover} / 源不可用 {dead}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
