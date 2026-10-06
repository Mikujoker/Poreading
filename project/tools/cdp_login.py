#!/usr/bin/env python3
"""CDP 登录器：在真实 Chrome 里把站点登进去，会话留在浏览器里供后续抓取/导 cookie。

账号密码从本地不入库的文件读，不经过命令行（免得进 shell history / 日志）。
用法：
  CDP_PORT=9223 python3 cdp_login.py <login_url> [--secret <file>] [--after <url>] [--check 文本]
"""
from __future__ import annotations

import argparse
import re

import cdp_fetch as C

FILL_JS = r"""
(() => {
  const forms = [...document.querySelectorAll('form')];
  const form = forms.find(f => f.querySelector('input[type=password]'));
  if (!form) return 'NO_FORM';
  const set = (el, v) => {
    if (!el) return false;
    el.focus();
    el.value = v;
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return true;
  };
  const inputs = [...form.querySelectorAll('input')].filter(
      i => !['hidden', 'submit', 'checkbox', 'radio', 'button'].includes(i.type));
  const userEl = inputs.find(i => /user|name|account|账号|用户/i.test(
      (i.name || '') + (i.id || '') + (i.placeholder || '')));
  const passEl = form.querySelector('input[type=password]');
  const okUser = set(userEl, window.__wkUser || '');
  const okPass = set(passEl, window.__wkPass || '');
  if (!okUser || !okPass) return 'FIELDS?';
  const btn = form.querySelector('input[type=submit], button[type=submit], button');
  if (btn) { btn.click(); return 'CLICKED:' + (btn.value || btn.textContent || ''); }
  form.submit();
  return 'SUBMITTED';
})()
"""


def read_secret(path: str) -> tuple[str, str]:
    text = open(path, encoding="utf-8").read()
    user = re.search(r"^user:\s*(\S+)", text, re.M)
    pwd = re.search(r"^pass:\s*(\S+)", text, re.M)
    if not user or not pwd:
        raise SystemExit(f"{path} 里没找到 user:/pass: 两行")
    return user.group(1), pwd.group(1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("login_url")
    parser.add_argument("--secret", default="/mnt/c/Users/mikujoker/legado-work/wenku8-secret.txt")
    parser.add_argument("--after", default="")
    parser.add_argument("--check", default="退出")
    args = parser.parse_args()

    user, pwd = read_secret(args.secret)
    C.launch()
    info = C.tab("about:blank")
    cdp = C.Cdp(info["webSocketDebuggerUrl"])
    cdp.goto(args.login_url, 4)
    print("登录页:", cdp.eval("document.title"), "|", cdp.eval("location.href"))

    cdp.eval(f"window.__wkUser={user!r}; window.__wkPass={pwd!r};")
    print("填表:", cdp.eval(FILL_JS))
    cdp.goto(args.after or args.login_url, 8)
    title = cdp.eval("document.title")
    url = cdp.eval("location.href")
    body = cdp.eval("document.body.innerText.slice(0, 400)") or ""
    logged = args.check in body
    print(f"结果: {title} | {url}")
    print("已登录标志（含 '%s'）: %s" % (args.check, logged))
    if args.after:
        cdp.goto(args.after, 6)
        print("跳转后:", cdp.eval("document.title"), "|", cdp.eval("location.href"))
    cookies = cdp.call("Network.getCookies").get("cookies", [])
    print("cookie:", ", ".join(f"{c['name']}" for c in cookies))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
