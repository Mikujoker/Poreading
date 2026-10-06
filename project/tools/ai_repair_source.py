#!/usr/bin/env python3
"""AI 修源 v0：把「取页 → 探针 → LLM 单字段 patch → 端上验证 → 写回/回滚」跑成一个循环。

设计要点（对应 AI-SOURCE-AGENT.md）：
- 取页永远走**手机**：`GET /fetchPage`（app 的 WebView，带会话、能过 CF）；命中挑战页就自动升级
  （延迟拉长 → 仍失败则 `GET /verifyLogin` 拉起内置浏览器让人工过验证，验证一次 cookie 长期保留）。
- 验证走端上真引擎：`POST /debugBookSource`（XPath/@js:/{{}} 只有它能判），按字段类型断言。
- 「不修好不停止」= 在预算内（默认 3 轮 / 10 分钟 / 200k tokens）自动换证据重试；
  到上限才升人工，并输出 needs_human（含每一轮试过的选择器与失败输出）。
- 每轮都留 before 值：验证不过就回滚，绝不留半成品。

用法：
  python3 ai_repair_source.py --source https://jishuge.vip --field ruleBookInfo.coverUrl --key <书籍URL>
  python3 ai_repair_source.py --source X --field ruleSearch.bookList --key 文学少女 --page search
  ... [--rounds 3] [--minutes 10] [--tokens 200000] [--dry-run] [--log-out /tmp/repair.json]

前置：app 的 Web 服务开着（`adb forward tcp:1122 tcp:1122`）；LLM key 在 legado-work/llm.env。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

for var in ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
    os.environ.pop(var, None)
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
WEB = os.environ.get("LEGADO_WEB_BASE", "http://127.0.0.1:1122")
CHALLENGE_MARKS = ("Just a moment", "请稍候", "Attention Required", "cf-browser-verification", "_cf_chl_opt")
UI_WORDS = ("关灯", "字号", "章节报错", "上一章", "下一章", "加入书架", "推荐本书", "本站", "版权")
ENV_FILE = "/mnt/c/Users/mikujoker/legado-work/llm.env"
ENV_FILE = "/mnt/c/Users/mikujoker/legado-work/llm.env"


def call(path: str, payload: dict | None = None, params: dict | None = None, timeout: float = 240) -> dict:
    url = WEB + path + (("?" + urllib.parse.urlencode(params)) if params else "")
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with OPENER.open(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except Exception as exc:  # noqa: BLE001
        return {"isSuccess": False, "errorMsg": f"{type(exc).__name__}: {exc}"}


# ---------------------------------------------------------------- 阶段 0/1：取页
def egress_check() -> str:
    result = call("/fetchPage", params={"url": "https://cloudflare.com/cdn-cgi/trace", "delay": 0, "maxChars": 2000})
    if not result.get("isSuccess"):
        return f"自检失败：{result.get('errorMsg')}"
    body = result["data"].get("html", "")
    loc = re.search(r"^loc=(\S+)", body, re.M)
    colo = re.search(r"^colo=(\S+)", body, re.M)
    ip = re.search(r"^ip=(\S+)", body, re.M)
    return f"出口 ip={ip.group(1) if ip else '?'} loc={loc.group(1) if loc else '?'} colo={colo.group(1) if colo else '?'}"


def is_challenge(html: str) -> bool:
    return any(mark in html for mark in CHALLENGE_MARKS)


def fetch(url: str, source: str, delay: int) -> tuple[str, str]:
    """取页：返回 (html, 说明)。挑战页会升级到人工验证。"""
    result = call("/fetchPage", params={"url": url, "source": source, "delay": delay, "maxChars": 400000})
    if not result.get("isSuccess"):
        return "", f"取页失败：{result.get('errorMsg')}"
    html = result["data"].get("html", "")
    final = result["data"].get("finalUrl", url)
    if not is_challenge(html):
        return html, f"OK（{len(html)} 字符，最终地址 {final}）"
    # 挑战页 → 拉长延迟再试一次
    result = call("/fetchPage", params={"url": url, "source": source, "delay": max(delay, 6000), "maxChars": 400000})
    html = (result.get("data") or {}).get("html", "")
    if result.get("isSuccess") and not is_challenge(html):
        return html, f"OK（拉长延迟后过挑战，{len(html)} 字符）"
    # 仍被挡 → 人工验证回路
    print("  ⚠ 命中人机验证 → 拉起手机内置浏览器，请在其中完成验证（右上角 ✓ 提交，返回=取消）…", flush=True)
    verified = call("/verifyLogin", params={"source": source, "url": url, "maxChars": 400000}, timeout=900)
    if not verified.get("isSuccess"):
        return "", f"人工验证未完成：{verified.get('errorMsg')}"
    result = call("/fetchPage", params={"url": url, "source": source, "delay": delay, "maxChars": 400000})
    html = (result.get("data") or {}).get("html", "")
    if is_challenge(html):
        return "", "人工验证后仍被拦"
    return html, f"OK（人工验证后，{len(html)} 字符，cookie {len((verified.get('data') or {}).get('cookie',''))} 字节）"


# ---------------------------------------------------------------- 阶段 2：机械探针
def probe(html: str, field: str) -> list[str]:
    """给 LLM 的候选（不猜结论，只列结构）。"""
    out: list[str] = []
    if "cover" in field or "img" in field:
        imgs = re.findall(r"<img[^>]*>", html)
        out.append(f"页面共 {len(imgs)} 个 <img>；前 8 个的属性：")
        out += [f"  {tag[:160]}" for tag in imgs[:8]]
        boxes = re.findall(r"<div[^>]*style=\"[^\"]{0,120}\"[^>]*>", html)[:8]
        out.append("含内联样式的 <div>（前 8 个，可用于定位容器）：")
        out += [f"  {b[:140]}" for b in boxes]
    if "bookList" in field or "search" in field.lower():
        parents: dict[str, int] = {}
        for parent, child in re.findall(r"<(\w+)[^>]*>\s*(?:<a\b)", html):
            parents[parent] = parents.get(parent, 0) + 1
        out.append(f"链接密度：{sorted(parents.items(), key=lambda kv: -kv[1])[:5]}")
        anchors = re.findall(r"<a[^>]*href=\"[^\"]{1,120}\"[^>]*>", html)[:10]
        out.append("前 10 个 <a>：")
        out += [f"  {a[:140]}" for a in anchors]
    if "chapterList" in field or "toc" in field:
        hrefs = re.findall(r"<a[^>]*href=\"([^\"]{1,80})\"[^>]*>([^<]{0,30})</a>", html)[:10]
        out.append("前 10 个带文本的链接（目录候选）：")
        out += [f"  href={h} text={t[:24]}" for h, t in hrefs]
    if "content" in field:
        blocks = sorted(re.findall(r"<div[^>]*>([\s\S]{200,4000}?)</div>", html),
                        key=lambda block: -len(re.findall(r"[\u4e00-\u9fff]", block)))[:3]
        out.append("中文字数最多的 3 个 <div> 片段（前 120 字）：")
        out += [f"  {re.sub(r'<[^>]+>', '', b)[:120]}" for b in blocks]
    if not out:
        out.append("（无专用探针）")
    return out


def trim(html: str, field: str, limit: int = 24000) -> str:
    """裁剪给 LLM 的 HTML：字段相关区域优先，避免整页 token 爆炸。"""
    if "cover" in field or "img" in field:
        chunks = []
        for match in re.finditer(r"<img[^>]*>", html):
            start = max(0, match.start() - 400)
            chunks.append(html[start:match.end() + 120])
        return "\n----\n".join(chunks[:12])[:limit]
    if "content" in field:
        blocks = sorted(re.findall(r"<div[^>]*>[\s\S]{200,6000}?</div>", html),
                        key=lambda block: -len(re.findall(r"[\u4e00-\u9fff]", block)))
        return "\n----\n".join(blocks[:2])[:limit]
    return html[:limit]


# ---------------------------------------------------------------- 阶段 3：LLM 单字段 patch
def llm_env() -> dict:
    env: dict[str, str] = {}
    if os.path.exists(ENV_FILE):
        for line in open(ENV_FILE, encoding="utf-8"):
            if "=" in line and not line.strip().startswith("#"):
                key, _, value = line.strip().partition("=")
                env[key.strip()] = value.strip().strip('"')
    for key in ("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL", "DEEPSEEK_MODEL"):
        env[key] = os.environ.get(key, env.get(key, ""))
    return env


def ask_llm(env: dict, field: str, current: str, evidence: list[str], html_excerpt: str,
            history: list[str]) -> tuple[dict, int]:
    base = (env.get("DEEPSEEK_BASE_URL") or "https://api.deepseek.com").rstrip("/")
    model = env.get("DEEPSEEK_MODEL") or "deepseek-chat"
    system = (
        "你是 Legado（阅读）书源规则修复器。只输出一个 JSON 对象，字段固定为 "
        '{"field": "...", "value": "...", "reason": "...", "evidence": "..."}。\n'
        "硬约束：只改指定的那一个字段，不得改动任何其他字段；不得编造 URL；"
        "**默认保持与当前值同一语法族、只做最小改动**（当前是 CSS 就改 CSS，当前是 XPath 就改 XPath）。\n"
        "Legado 规则语法（value 必须是下面三种之一）：\n"
        "1) CSS：选择器 + 可选取值后缀 @text/@href/@src/@html/@ownText/@属性名；"
        "如 `#content div[style*='99%'] img@src`、`a[href^='/book/']@href`（属性选择器要带引号；支持 :contains()/:has()/:not()）\n"
        "2) XPath：以 // 开头，**属性直接写在路径里**，如 `//td[@width='20%']/img/@src`、`//div[@id='content']//text()`；"
        "⚠️ 绝不能在末尾再接 @src/@href/@text（`//a@href` 是错的，应为 `//a/@href`）\n"
        "3) JS：`@js:脚本`，脚本里可用 result/baseUrl/key 等绑定\n"
    )
    user = (
        f"要修的字段：{field}\n当前值：{current or '(空)'}\n\n"
        f"机械探针给出的候选：\n" + "\n".join(evidence) + "\n\n"
        f"此前几轮已试过（不要重复）：\n" + ("\n".join(history) if history else "(无)") + "\n\n"
        f"页面 HTML 片段（已裁剪）：\n```html\n{html_excerpt}\n```\n\n"
        "请给出这一字段的新值。"
    )
    payload = {
        "model": model,
        "temperature": 0.2,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
    }
    request = urllib.request.Request(
        f"{base}/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {env.get('DEEPSEEK_API_KEY','')}"},
    )
    try:
        with OPENER.open(request, timeout=180) as response:
            body = json.loads(response.read().decode("utf-8", "replace"))
    except Exception as exc:  # noqa: BLE001
        return {"error": f"{type(exc).__name__}: {exc}"}, 0
    text = (body.get("choices") or [{}])[0].get("message", {}).get("content", "")
    tokens = int(body.get("usage", {}).get("total_tokens") or 0) or (len(system + user + text) // 4)
    match = re.search(r"\{[\s\S]*\}", text)
    if not match:
        return {"error": f"LLM 没给出 JSON：{text[:200]}"}, tokens
    try:
        return json.loads(match.group(0)), tokens
    except json.JSONDecodeError as exc:
        return {"error": f"JSON 解析失败：{exc}"}, tokens


# ---------------------------------------------------------------- 阶段 4：端上验证 + 断言
def dbg_key(page: str, key: str) -> str:
    return {"search": key, "detail": key, "toc": f"++{key}", "content": f"--{key}"}.get(page, key)


def strip_ts(message: str) -> str:
    return re.sub(r"^\[\d\d:\d\d\.\d+\] ", "", message)



def syntax_hint(value: str) -> str:
    """本地语法预检：能把常见规则语法错误挡在设备验证之前（省一轮来得慢的端上验证）。"""
    if value.startswith("//") and re.search(r"@(src|href|text|html|ownText)$", value):
        return ("XPath 的属性要写在路径里，末尾不能接 @ 后缀："
                "`//a@href` → `//a/@href`，`//img@src` → `//img/@src`")
    if value.count("(") != value.count(")"):
        return "圆括号不配对"
    if value.count("[") != value.count("]"):
        return "方括号不配对"
    return ""


def image_ok(cover: str, book_url: str) -> tuple[bool, str]:
    """封面 URL 是否真能取到图。PC 直连优先；PC 取不到就 SKIP（不算失败，避免误判让 AI 追鬼）。"""
    try:
        request = urllib.request.Request(cover, headers={"User-Agent": "Mozilla/5.0"})
        with OPENER.open(request, timeout=20) as response:
            ctype = response.headers.get("Content-Type", "")
            if response.status == 200 and ctype.startswith("image"):
                return True, f"图片可下载（{ctype}）"
    except Exception:  # noqa: BLE001
        pass
    result = call("/image", params={"url": book_url, "path": cover}, timeout=60)
    if result.get("isSuccess"):
        return True, "app 侧取图成功"
    return True, "SKIP：PC/app 都取不到图，判据只校验唯一性"

def _debug_once(source_url: str, key: str, timeout: float) -> tuple[str, dict]:
    result = call("/debugBookSource", {"tag": source_url, "key": key, "timeoutMs": int(timeout * 1000)},
                  timeout=timeout + 120)
    if not result.get("isSuccess"):
        return "", {"ok": False, "error": result.get("errorMsg", "")}
    data = result.get("data") or {}
    text = "\n".join(strip_ts(e.get("message", "")) for e in (data.get("events") or []))
    return text, data


def debug_source(source_url: str, key: str, timeout: float = 90) -> tuple[str, dict]:
    """跑端上调试引擎。引擎取到 CF 挑战页时会自动带 webView 选项重试一次（OkHttp 过不了 CF）。"""
    text, data = _debug_once(source_url, key, timeout)
    if any(mark in text for mark in CHALLENGE_MARKS) and ",{" not in key:
        print("  ⚠ 引擎拿到人机验证页 → 改带 webView 选项重试一次")
        text2, data2 = _debug_once(source_url, key + ',{"webView":true,"webViewDelayTime":3000}', timeout)
        if text2:
            return text2, data2
    return text, data

def value_after(text: str, label: str) -> str:
    match = re.search(re.escape(label) + r"\s*└([^\n]*)", text)

def value_block(text: str, label: str) -> str:
    """取某个 ┌标签 之后到下一个 ┌ 之前的整段（值可能多行）。"""
    match = re.search(re.escape(label) + r"([\s\S]*?)(?=\n┌|\n\[\d\d:\d\d|\Z)", text)
    return match.group(1) if match else ""
    return match.group(1).strip() if match else ""


def assert_field(field: str, page: str, text: str, data: dict, book_key: str = "") -> tuple[bool, str]:
    """按字段语义断言（确定性，不打折扣）。先挡掉人机验证页，避免把挑战页当解析成功。"""
    if any(mark in text for mark in CHALLENGE_MARKS):
        return False, "引擎拿到的是人机验证页（Just a moment/请稍候），不是真页面"
    if not data.get("ok") and not text:
        return False, f"调试失败：{data.get('error','')[:160]}"
    leaf = field.split(".")[-1]
    if leaf == "coverUrl":
        block = value_block(text, "获取封面链接")
        urls = [line.strip().lstrip("└").strip() for line in block.splitlines()]
        urls = [url for url in urls if url.lower().startswith("http")]
        if len(urls) != 1:
            # 0 个 = 没解析到；>1 个 = 多张封面拼成多行（无效 URL，封面会加载失败）
            return False, f"封面链接必须唯一，实际 {len(urls)} 个：{urls[:3]}"
        cover = urls[0]
        ok_image, why_image = image_ok(cover, book_key)
        if not ok_image:
            return False, why_image
        return True, f"封面 OK（唯一）：{cover[:90]}；{why_image}"
    if leaf == "bookList":
        size = value_after(text, "列表大小")
        return (size.isdigit() and int(size) > 0), f"列表大小={size or '空'}"
    if leaf == "chapterList":
        total = value_after(text, "目录总数") or value_after(text, "列表大小")
        return (total.isdigit() and int(total) > 0), f"目录总数={total or '空'}"
    if leaf == "content":
        body = text.split("获取正文内容")[-1]
        cjk = len(re.findall(r"[\u4e00-\u9fff]", body))
        junk = [w for w in UI_WORDS if w in body]
        return (cjk > 200 and not junk), f"正文中文字数≈{cjk}，含站点 UI 词={junk[:3]}"
    if leaf in ("name", "author", "intro", "bookUrl", "tocUrl"):
        value = value_after(text, {"name": "获取书名", "author": "获取作者", "intro": "获取简介",
                                   "bookUrl": "获取详情页链接", "tocUrl": "获取目录链接"}[leaf])
        return bool(value), f"{leaf}={value[:80]!r}"
    if data.get("ok"):
        return True, "调试整体通过"
    return False, f"调试未通过：{str(data.get('error',''))[:160]}"


# ---------------------------------------------------------------- 主循环
def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="书源 URL（必须是库里已存在的源）")
    parser.add_argument("--field", required=True, help="要修的字段，如 ruleBookInfo.coverUrl")
    parser.add_argument("--key", required=True, help="页面/关键词：detail 传书籍URL，search 传关键词")
    parser.add_argument("--page", default="detail", choices=["search", "detail", "toc", "content"])
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--minutes", type=float, default=10)
    parser.add_argument("--tokens", type=int, default=200_000)
    parser.add_argument("--delay", type=int, default=3000)
    parser.add_argument("--dry-run", action="store_true", help="只出 patch，不写回库")
    parser.add_argument("--check-only", action="store_true",
                        help="只用端上验证跑一遍当前值（校准判据用），不调 LLM、不写库")
    parser.add_argument("--log-out", default="")
    args = parser.parse_args()

    started = time.time()
    spent_tokens = 0
    history: list[str] = []
    empty_streak = 0
    log: list[dict] = []

    env = llm_env()
    if not env.get("DEEPSEEK_API_KEY"):
        print("✗ 没找到 DEEPSEEK_API_KEY（legado-work/llm.env）"); return 1

    print(f"阶段0 出口自检：{egress_check()}")
    before_resp = call("/getBookSource", params={"url": args.source}, timeout=60)
    if not before_resp.get("isSuccess"):
        print(f"✗ 库里没有这个源：{args.source}（{before_resp.get('errorMsg')}）"); return 1
    source_obj = before_resp["data"]
    page_obj, leaf = (source_obj.get(args.field.split(".")[0]) or {}), args.field.split(".")[-1]
    current = page_obj.get(leaf) or ""

    if args.check_only:
        text, data = debug_source(args.source, dbg_key(args.page, args.key))
        passed, why = assert_field(args.field, args.page, text, data, args.key)
        print(f"{'✓' if passed else '✗'} {args.field} 当前值：{current[:90]!r}\n  断言：{why}")
        return 0 if passed else 2
    print(f"阶段1 取页（手机 WebView）：{args.key}")
    html, note = fetch(args.key, args.source, args.delay)
    print(f"  {note}")
    if not html:
        print(f"✗ 取页失败，转人工：{note}"); return 1

    page_type = "search" if args.page == "search" else args.page
    for round_no in range(1, args.rounds + 1):
        if time.time() - started > args.minutes * 60 or spent_tokens > args.tokens:
            print(f"⚠ 预算用尽（{round_no - 1} 轮 / {spent_tokens} tokens）→ needs_human"); break
        print(f"\n--- 第 {round_no}/{args.rounds} 轮")
        evidence = probe(html, args.field)
        patch, tokens = ask_llm(env, args.field, current, evidence, trim(html, args.field), history)
        spent_tokens += tokens
        if patch.get("error"):
            print(f"  ✗ LLM 出错：{patch['error']}"); history.append(f"第{round_no}轮 LLM 出错"); continue
        value = str(patch.get("value") or "")
        print(f"  提案：{value[:150]}\n  理由：{str(patch.get('reason'))[:150]}")
        if not value:
            reason = str(patch.get("reason") or "")
            print(f"  ⚠ 空提案（{reason[:120]}）")
            empty_streak += 1
            history.append(f"第{round_no}轮 空提案：{reason[:80]}")
            if empty_streak >= 2:
                print("\n✗ 结论：该页面没有这个字段对应的元素（LLM 连续两轮给不出值），保持现状")
                if args.log_out:
                    json.dump({"source": args.source, "field": args.field, "verdict": "field_not_on_page",
                               "history": history}, open(args.log_out, "w", encoding="utf-8"),
                              ensure_ascii=False, indent=2)
                return 3
            continue
        empty_streak = 0
        hint = syntax_hint(value)
        if hint:
            print(f"  ⚠ 本地语法预检不通过：{hint}")
            history.append(f"第{round_no}轮 提案 {value!r} 语法错误：{hint}")
            continue

        patched = json.loads(json.dumps(source_obj))
        patched.setdefault(args.field.split(".")[0], {})[leaf] = value
        if args.dry_run:
            print("  （dry-run，不写回）")
            log.append({"round": round_no, "patch": patch, "applied": False})
            break
        saved = call("/saveBookSource", patched, timeout=60)
        if not saved.get("isSuccess"):
            print(f"  ✗ 写回失败：{saved.get('errorMsg')}"); continue

        text, data = debug_source(args.source, dbg_key(page_type, args.key))
        passed, why = assert_field(args.field, page_type, text, data, args.key)
        print(f"  端上验证：{'✓' if passed else '✗'} {why}")
        log.append({"round": round_no, "patch": patch, "verify": why, "passed": passed})
        if passed:
            print(f"\n✅ 修好了：{args.field} = {value}")
            if args.log_out:
                json.dump({"source": args.source, "field": args.field, "before": current,
                           "after": value, "rounds": log}, open(args.log_out, "w", encoding="utf-8"),
                          ensure_ascii=False, indent=2)
            return 0
        call("/saveBookSource", source_obj, timeout=60)  # 回滚
        print("  ↩ 已回滚到 before 值")
        history.append(f"第{round_no}轮 试过 {value!r} → 失败：{why}")
        html, note = fetch(args.key, args.source, args.delay)  # 换策略前重新取页

    print(f"\n⚠ needs_human（{len(history)} 轮未修好，{spent_tokens} tokens，{int(time.time()-started)}s）")
    for item in history:
        print(f"  - {item}")
    if args.log_out:
        json.dump({"source": args.source, "field": args.field, "needsHuman": True, "history": history},
                  open(args.log_out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    return 2


if __name__ == "__main__":
    sys.exit(main())
