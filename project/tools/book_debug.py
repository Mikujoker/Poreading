#!/usr/bin/env python3
"""端上引擎验证：直接驱动 Legado 的 bookSourceDebug WebSocket，自己跑规则、自己读结果。

这样就不必让人一遍遍点「▶」再截图（AI-SOURCE-AGENT.md 里 Step 2 的端上验证入口）。

用法：
  adb forward tcp:1235 tcp:1235                       # 只需一次
  python3 book_debug.py <tag=书源URL> <key=关键词> [--seconds 60] [--raw]
    key：搜索关键词 / 书籍URL / 目录页URL / 章节URL（跟调试页那几个输入框一样）
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

for var in ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
    os.environ.pop(var, None)  # 本地端口别走代理（本机 curl 探它会被代理抢答成 502）

import websocket  # noqa: E402

URL = os.environ.get("BOOK_DEBUG_WS", "ws://127.0.0.1:1122/bookSourceDebug")
KEYS = ("列表大小", "获取成功", "开始", "错误", "Error", "Exception", "退出登录",
        "Attention", "Just a moment", "Not Found", "登录")


def digest(msg: dict, raw: bool) -> str | None:
    text = json.dumps(msg, ensure_ascii=False) if isinstance(msg, dict) else str(msg)
    if raw:
        return text[:400]
    if any(k in text for k in KEYS):
        return text[:600]
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("key")
    parser.add_argument("--seconds", type=float, default=60)
    parser.add_argument("--raw", action="store_true")
    args = parser.parse_args()

    ws = websocket.create_connection(URL, timeout=90, suppress_origin=True)
    payload = {"key": args.key, "tag": args.tag}
    ws.send(json.dumps(payload, ensure_ascii=False))
    print(f"→ 发送 {payload}")
    deadline = time.time() + args.seconds
    seen = 0
    while time.time() < deadline:
        try:
            raw = ws.recv()
        except Exception as exc:
            print(f"[连接结束] {type(exc).__name__}: {exc}")
            break
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            print("<非 JSON>", str(raw)[:300])
            continue
        line = digest(msg, args.raw)
        if line:
            seen += 1
            print(f"[{seen:03d}] {line}")
            if seen > 60:
                print("...（省略）")
                break
    ws.close()
    print(f"=== 结束，共 {seen} 条关键日志 ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
