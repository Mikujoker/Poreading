#!/usr/bin/env python3
"""书源规则的确定性校验：拿真实页面验 `bookSource.json` 的每一条规则。

设计原则（这条最重要）：
  只覆盖 Legado 规则的**常用子集**并如实报告；XPath / `@js:` / `{{}}` 里的 JS
  一律标 SKIP 并指向端上的 `bookSourceDebug` 通道去验。
  宁可报 SKIP，也不要"看起来通过"——抄一层语义抄错的代价，在目录规则那轮已经付过一次。

支持的子集：
  * CSS 选择器（含 `,` 并集、`:not(.cls)`、尾部 `!索引`）
  * 取值后缀 `@text`（默认）/`@html`/`@href`/`@src`/`@content`/`@<任意属性>`
  * `searchUrl` 的 `,{...}` 选项：`method`/`body`（body 里可写 `{{key}}`）
用法：
  python3 source_check.py <source.json> --keyword 大梦主
  python3 source_check.py <source.json> --book-url URL --chapter-url URL   # 跳过搜索
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

UA_MOBILE = ("Mozilla/5.0 (Linux; Android 16; 2201122C) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
SESSION = requests.Session()
SOURCE_HEADERS: dict = {}
EXTRA_HEADERS: dict = {}


def log(ok: str, field: str, sample: str = "") -> None:
    print(f"   {ok:4s} {field:34s} {sample[:60]}")


def fetch(url: str, extra: dict | None = None) -> BeautifulSoup:
    headers = {"User-Agent": UA_MOBILE, "Accept-Language": "zh-CN,zh;q=0.9"}
    headers.update(SOURCE_HEADERS)
    headers.update(EXTRA_HEADERS)
    options = dict(extra or {})
    method = options.pop("method", "GET")
    resp = SESSION.request(method, url, headers=headers, timeout=25, **options)
    resp.encoding = resp.apparent_encoding or "utf-8"
    return BeautifulSoup(resp.text, "lxml")


def resolve_url(source: dict, path: str, key: str = "") -> tuple[str, dict]:
    """把 `searchUrl`/`ruleContent.url` 的 `url,{...}` 选项拆开；返回 (url, 请求参数)。"""
    url, _, raw = path.partition(",")
    url = url.replace("{{key}}", requests.utils.quote(key))
    options: dict = {}
    if raw.strip().startswith("{"):
        try:
            opt = json.loads(raw)
        except json.JSONDecodeError:
            print(f"   SKIP 选项解析失败：{raw[:40]}")
            return urljoin(source["bookSourceUrl"], url), options
        method = str(opt.get("method", "GET")).upper()
        body = str(opt.get("body", "")).replace("{{key}}", key)
        if method == "POST":
            options["method"] = "POST"
            # 必须交给 requests 表单编码：字符串 body 不会自动带
            # Content-Type: application/x-www-form-urlencoded，站点就收不到参数
            options["data"] = dict(kv.split("=", 1) for kv in body.split("&") if "=" in kv)
        elif body:
            options["params"] = dict(kv.split("=", 1) for kv in body.split("&") if "=" in kv)
    return urljoin(source["bookSourceUrl"], url), options


def select(soup_or_el, selector: str) -> list:
    index = None
    if "!" in selector:
        selector, _, raw = selector.rpartition("!")
        index = int(raw)
    try:
        found = soup_or_el.select(selector)
    except Exception as exc:  # 选择器语法不支持（如 :contains）→ 交给端上验
        raise NotImplementedError(selector) from exc
    return [found[index]] if index is not None and -len(found) <= index < len(found) else (
        found if index is None else [])


def apply_rule(rule: str, soup, default_op: str = "text") -> str:
    if not rule:
        return ""
    selector, _, op = rule.partition("@")
    op = op or default_op
    els = select(soup, selector.strip())
    if not els:
        return ""
    el = els[0]
    if op in ("text", "ownText"):
        return el.get_text(strip=True)
    if op == "html":
        return str(el)
    if op in ("href", "src", "content"):
        return el.get(op) or ""
    return el.get(op) or ""


def apply_item_rule(rule: str, el) -> str:
    """Legado 的逐项规则：`text`/`href`/`html` 这类直接作用于当前元素，而不是再选一层。"""
    if "@" not in rule:
        op = rule.split(".")[0]
        if op in ("text", "ownText"):
            return el.get_text(strip=True)
        if op == "html":
            return str(el)
        if op in ("href", "src", "content"):
            return el.get(op) or ""
    return apply_rule(rule, el)


def apply_list(rule: str, soup) -> list:
    selector, _, op = rule.partition("@")
    els = select(soup, selector.strip())
    if op == "text":
        els = [e.find(string=True) for e in els]
        return [e.strip() for e in els if e and e.strip()]
    return els


def check_search(source: dict, key: str) -> str:
    print(f"== 搜索页（keyword={key}）")
    rule = source.get("searchUrl") or ""
    if not rule:
        log("SKIP", "searchUrl", "未配置")
        return ""
    url, options = resolve_url(source, rule, key)
    soup = fetch(url, options)
    rules = source.get("ruleSearch") or {}
    rows = select(soup, (rules.get("bookList") or "").strip())
    log("PASS" if rows else "FAIL", "bookList", f"{len(rows)} 行")
    first_url = ""
    for field, default in (("name", "text"), ("author", "text"), ("bookUrl", "href"),
                           ("coverUrl", "src")):
        pattern = (rules.get(field) or "").strip()
        if not pattern:
            log("--", field, "未配置")
            continue
        value = apply_rule(pattern, rows[0], default) if rows else ""
        log("PASS" if value else "FAIL", field, value)
        if field == "bookUrl" and value:
            first_url = urljoin(url, value)
    for field in rules:
        if field not in ("bookList", "name", "author", "bookUrl", "coverUrl", "intro",
                         "kind", "lastChapter", "wordCount"):
            log("SKIP", field, "不在校验子集内")
    return first_url


def check_pages(source: dict, book_url: str, toc_url: str, chapter_url: str) -> None:
    for label, page_url, rule_key, fields in (
        ("详情页", book_url, "ruleBookInfo",
         (("name", "text"), ("author", "text"), ("intro", "text"), ("coverUrl", "src"))),
        ("目录页", toc_url, "ruleToc",
         (("chapterList", "element"), ("chapterName", "text"), ("chapterUrl", "href"))),
        ("正文页", chapter_url, "ruleContent", (("content", "html"),)),
    ):
        if not page_url:
            print(f"== {label}（跳过：没有 URL）")
            continue
        print(f"== {label} {page_url}")
        soup = fetch(page_url)
        rules = source.get(rule_key) or {}
        first_item = None
        for field, default in fields:
            pattern = (rules.get(field) or "").strip()
            if not pattern:
                log("--", field, "未配置")
                continue
            if field.endswith("List"):
                try:
                    raw = select(soup, pattern.split("@")[0].strip())
                    items = apply_list(pattern, soup)
                    first_item = raw[0] if raw else None
                except NotImplementedError as exc:
                    log("SKIP", field, f"选择器不在子集内: {exc}")
                    continue
                sample = str(items[:2])[:60]
                log("PASS" if items else "FAIL", field, f"{len(items)} 项 {sample}")
                continue
            try:
                target = first_item if first_item is not None else soup
                if first_item is not None:
                    # 目录页的 chapterName/chapterUrl 是逐项规则：作用于 chapterList 的每个元素
                    value = apply_item_rule(pattern, first_item)
                else:
                    value = apply_rule(pattern, target, default)
            except NotImplementedError as exc:
                log("SKIP", field, f"选择器不在子集内: {exc}")
                continue
            text = re.sub(r"<[^>]+>", "", value)
            log("PASS" if text.strip() else "FAIL", field, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("--keyword", default="")
    parser.add_argument("--book-url", default="")
    parser.add_argument("--chapter-url", default="")
    parser.add_argument("--toc-url", default="")
    parser.add_argument("--cookies", default="", help="CDP 导出的 cookie JSON（绕过 CF/带登录态用）")
    parser.add_argument("--ua", default="", help="必须与取得 cookie 时的 UA 一致")
    args = parser.parse_args()
    source = json.loads(open(args.source, encoding="utf-8").read())
    if isinstance(source, list):
        source = source[0]
    global SOURCE_HEADERS
    if args.cookies:
        jar = json.load(open(args.cookies, encoding="utf-8"))
        EXTRA_HEADERS["Cookie"] = "; ".join(f"{c['name']}={c['value']}" for c in jar)
        print(f"   带 {len(jar)} 条 cookie 取页")
    if args.ua:
        EXTRA_HEADERS["User-Agent"] = args.ua
    try:
        SOURCE_HEADERS = json.loads(source.get("header") or "{}")
    except json.JSONDecodeError:
        print("   SKIP header 不是合法 JSON，按默认 UA 取页")
    print(f"# {source.get('bookSourceName')} <{source.get('bookSourceUrl')}>")
    book_url = args.book_url
    if args.keyword and not book_url:
        book_url = check_search(source, args.keyword)
    chapter_url = args.chapter_url
    if not chapter_url and book_url:
        try:
            soup = fetch(book_url)
            toc_rule = (source.get("ruleToc") or {}).get("chapterList", "")
            first = select(soup, toc_rule.strip())[:1]
            if first:
                href = first[0].get("href") or ""
                chapter_url = urljoin(book_url, href)
                print(f"   （目录第一项 -> {chapter_url}）")
        except Exception as exc:
            print(f"   取目录首项失败：{exc}")
    check_pages(source, book_url, args.toc_url or book_url, chapter_url)
    return 0


if __name__ == "__main__":
    sys.exit(main())
