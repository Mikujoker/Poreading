#!/usr/bin/env python3
"""端上引擎验证：直接打 Legado Web 服务的 /debugBookSource，自己跑规则、自己读结构化结果。

这样就不必让人一遍遍点「▶」再截图（AI-SOURCE-AGENT.md 里 Step 2 的端上验证入口）。
WS 版的 bookSourceDebug 在 webPort+1（默认 1123），本脚本走 HTTP 版，一次拿到全部事件。

用法：
  adb forward tcp:1122 tcp:1122                       # 只需一次（Web 服务开着）
  python3 book_debug.py --tag <书源URL> --key <关键词|URL|++目录URL|--章节URL>
  python3 book_debug.py --tag <书源URL> --key 文学少女 --json
  python3 book_debug.py --cookie https://www.wenku8.net/index.php   # 查登录态存在哪一层
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# 本地端口别走代理（否则被本机代理抢答成 502）
for var in ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
    os.environ.pop(var, None)

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
NOISE = ("︾", "︽", "◇", "≈", "≡")


def call(base: str, path: str, payload: dict | None = None, params: dict | None = None) -> dict:
    url = f"{base}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with OPENER.open(req, timeout=float(os.environ.get("BOOK_DEBUG_HTTP_TIMEOUT", 300))) as res:
            return json.loads(res.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        return {"isSuccess": False, "errorMsg": f"HTTP {exc.code}: {exc.read()[:200]!r}"}
    except Exception as exc:  # noqa: BLE001
        return {"isSuccess": False, "errorMsg": f"{type(exc).__name__}: {exc}"}


def show_events(data: dict, show_all: bool) -> None:
    events = data.get("events") or []
    for index, event in enumerate(events, 1):
        message = str(event.get("message", "")).replace("\n", "\\n")
        if not show_all and not any(k in message for k in NOISE) and len(message) > 160:
            continue
        if len(message) > 400:
            message = message[:400] + "..."
        print(f"[{index:03d}] {event.get('elapsedMs', 0):>6}ms {event.get('kind'):<13} {message}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="书源 URL")
    parser.add_argument("--key", help="搜索关键词 / 书籍URL / ++目录URL / --章节URL")
    parser.add_argument("--cookie", help="只查这个 URL 的登录态（WebView / jar / DB 三层）")
    parser.add_argument("--port", type=int, default=int(os.environ.get("LEGADO_WEB_PORT", 1122)))
    parser.add_argument("--timeout", type=float, default=120, help="服务端调试超时（毫秒入参用秒表示）")
    parser.add_argument("--json", action="store_true", help="原样打印响应 JSON")
    parser.add_argument("--all", action="store_true", help="连噪音事件一起打印")
    args = parser.parse_args()

    base = os.environ.get("LEGADO_WEB_BASE", f"http://127.0.0.1:{args.port}")

    if args.cookie:
        res = call(base, "/getCookie", params={"url": args.cookie})
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0 if res.get("isSuccess") else 1

    if not args.tag or not args.key:
        parser.error("需要 --tag 与 --key（或用 --cookie）")

    payload = {"tag": args.tag, "key": args.key, "timeoutMs": int(args.timeout * 1000)}
    print(f"→ POST {base}/debugBookSource {payload}")
    res = call(base, "/debugBookSource", payload=payload)
    if args.json:
        print(json.dumps(res, ensure_ascii=False, indent=2))
    if not res.get("isSuccess"):
        print(f"✗ 请求失败: {res.get('errorMsg')}")
        return 1
    data = res.get("data") or {}
    show_events(data, args.all or args.json)
    status = "OK" if data.get("ok") else ("TIMEOUT" if data.get("timeout") else "FAIL")
    print(
        f"=== {status} key={data.get('key')} 事件={data.get('eventCount')} "
        f"耗时={data.get('elapsedMs')}ms"
    )
    if data.get("error"):
        print(f"--- error: {data['error']}")
    return 0 if data.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
