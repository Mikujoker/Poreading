#!/usr/bin/env python3
"""L2 会话层：用**真实（非 headless）Chrome** 取页面 —— 过 Cloudflare/JS 挑战、导出 cookie。

为什么必须非 headless：实测 headless Chrome 一样被 CF 拦（见 project/AI-SOURCE-AGENT.md）。
为什么用独立 profile：不碰用户正在用的窗口/标签页。

用法：
  python3 cdp_fetch.py --launch                     # 起一个带调试口的 Chrome（幂等）
  python3 cdp_fetch.py fetch <url> [--wait 8] [--out f.html]
  python3 cdp_fetch.py cookies [domain]             # 导出 cookie（cf_clearance 复用要同 IP+同 UA）
  python3 cdp_fetch.py ua                           # 打印该浏览器的 UA（写进书源 header 用）

注意：本机（WSL mirrored 网络）里 127.0.0.1:9222 就是 Windows 的 127.0.0.1:9222；
用 curl 探它要加 --noproxy '*'，否则会被本地代理抢答成 502。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

import websocket

CHROME = "/mnt/c/Program Files/Google/Chrome/Application/chrome.exe"
PORT = int(os.environ.get("CDP_PORT", "9222"))
EXTRA_ARGS = os.environ.get("CDP_EXTRA_ARGS", "").split()
PROFILE = rf"C:\Users\mikujoker\legado-work\cdp-prof-{PORT}"
BASE = f"http://127.0.0.1:{PORT}"


def http(path: str, method: str = "GET") -> dict | list:
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode("utf-8", "replace")
    return json.loads(body) if body.strip() else {}


def launch() -> None:
    try:
        ver = http("/json/version")
        print(f"已有调试口：{ver.get('Browser')}")
        return
    except Exception:
        pass
    subprocess.Popen(
        [CHROME, f"--remote-debugging-port={PORT}", f"--user-data-dir={PROFILE}",
         "--no-first-run", "--no-default-browser-check", "--disable-popup-blocking",
         *EXTRA_ARGS, "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(30):
        time.sleep(1)
        try:
            ver = http("/json/version")
            print(f"已启动：{ver.get('Browser')}")
            return
        except Exception:
            continue
    raise SystemExit("Chrome 起来了但调试口连不上（首次可能需要你点一下 Allow debugging 弹窗）")


def tab(url: str = "about:blank") -> dict:
    tabs = http(f"/json/new?{url}", method="PUT")
    if isinstance(tabs, list):
        return tabs[0]
    return tabs


class Cdp:
    def __init__(self, ws_url: str) -> None:
        self.ws = websocket.create_connection(ws_url, timeout=90, suppress_origin=True)
        self.mid = 0

    def call(self, method: str, params: dict | None = None) -> dict:
        self.mid += 1
        self.ws.send(json.dumps({"id": self.mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def goto(self, url: str, wait: float) -> None:
        self.call("Page.enable")
        self.call("Page.navigate", {"url": url})
        deadline = time.time() + wait
        while time.time() < deadline:
            state = self.call("Runtime.evaluate",
                              {"expression": "document.readyState", "returnByValue": True})
            if state.get("result", {}).get("value") == "complete":
                break
            time.sleep(0.4)
        time.sleep(wait)  # 给 JS 挑战/跳转留时间

    def html(self) -> str:
        res = self.call("Runtime.evaluate", {
            "expression": "document.documentElement.outerHTML", "returnByValue": True})
        return res.get("result", {}).get("value") or ""

    def eval(self, expression: str) -> object:
        res = self.call("Runtime.evaluate", {"expression": expression, "returnByValue": True})
        return res.get("result", {}).get("value")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("launch", "fetch", "cookies", "ua"))
    parser.add_argument("target", nargs="?", default="")
    parser.add_argument("--wait", type=float, default=8.0)
    parser.add_argument("--out", default="")
    parser.add_argument("--tab", default="", help="复用已有标签页的 id（默认新开一个）")
    args = parser.parse_args()

    if args.action == "launch":
        launch()
        return 0

    launch()
    if args.tab:
        tabs = [t for t in http("/json/list") if t.get("id", "").startswith(args.tab)]
        if not tabs:
            raise SystemExit(f"找不到标签页 {args.tab}")
        info = tabs[0]
    else:
        info = tab(args.target or "about:blank")

    cdp = Cdp(info["webSocketDebuggerUrl"])
    if args.action == "ua":
        print(cdp.eval("navigator.userAgent"))
        return 0
    if args.action == "fetch":
        cdp.goto(args.target, args.wait)
        html = cdp.html()
        title = cdp.eval("document.title")
        block = "blocked" if "Attention Required" in html else ""
        print(f"{len(html)}B title={str(title)[:50]} {block} url={cdp.eval('location.href')}")
        if args.out:
            open(args.out, "w", encoding="utf-8").write(html)
            print(f"-> {args.out}")
        return 0
    if args.action == "cookies":
        cdp.goto(args.target or "about:blank", 1)
        cookies = cdp.call("Network.getCookies").get("cookies", [])
        for c in cookies:
            print(f"{c['name']}={c['value'][:18]}… domain={c['domain']} "
                  f"expires={c.get('expires')} samesite={c.get('sameSite')}")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
