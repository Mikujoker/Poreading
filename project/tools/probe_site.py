#!/usr/bin/env python3
"""书源站结构探针：抓四类页面并打印候选结构，用于设计/修订书源规则。

用法：
    python3 probe_site.py <search_url|-> <detail_url> <toc_url|-> <content_url|->
正面例子：
    python3 probe_site.py "https://www.bixia.org/book/8227/" "https://www.bixia.org/book/8227/" ...

搜索是 POST 的站点用 `POST:<url>|<body>` 形式传参。
"""
import re
import sys

import requests
from bs4 import BeautifulSoup

UA = ("Mozilla/5.0 (Linux; Android 16; 2201122C) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9",
})

PICKS = {
    "search": [
        ("书名", "div.item dl dt a, .bookbox .bookname a, li .s2 a"),
        ("作者", "div.item dl div.btm, .bookbox .author, li .s4"),
        ("详情链接", "div.item dl dt a, .bookbox .bookname a, li .s2 a"),
        ("封面", "div.item img, .bookbox img, li img"),
    ],
    "detail": [
        ("书名", "h1, .bookinfo h1, #info h1"),
        ("作者", "#info p, .bookinfo p, .btm"),
        ("简介", "#intro, .intro, .bookinfo .intro, meta[name=description]"),
        ("封面", "#fmimg img, .bookinfo img, .cover img, meta[property='og:image']"),
        ("目录链接", "a[href*='list'], a[href*='chapter'], a[href*='mulu']"),
    ],
    "toc": [
        ("章节块", "#list dd a, .listmain dd a, #chapterlist a, ul.chapter a"),
        ("最新章节", ".book_list a, #intro + div a"),
    ],
    "content": [
        ("正文块", "#content, #chaptercontent, .content, .showtxt, #booktext"),
        ("下一章", "a:contains('下一章'), a[href*='next']"),
    ],
}


def fetch(target: str) -> tuple[int, str, str]:
    if target.startswith("POST:"):
        url, _, body = target[5:].partition("|")
        resp = SESSION.post(url, data=dict(kv.split("=", 1) for kv in body.split("&")), timeout=25)
    else:
        resp = SESSION.get(target, timeout=25)
    resp.encoding = resp.apparent_encoding or "utf-8"
    return resp.status_code, resp.text, resp.url


def report(label: str, target: str, text: str, status: int, out: str) -> None:
    open(out, "w", encoding="utf-8").write(text)
    soup = BeautifulSoup(text, "lxml")
    print(f"== {label}: {status} {len(text)}B -> {out}")
    for name, selector in PICKS[label]:
        els = soup.select(selector)
        vals = [re.sub(r"\s+", " ", e.get_text(strip=True))[:36] for e in els[:3]]
        hrefs = [(e.get("href") or e.get("src") or e.get("content") or "")[:60] for e in els[:2]]
        print(f"   {name:8s} {selector[:44]:44s} n={len(els):3d} {vals} {hrefs}")


def main() -> int:
    args = sys.argv[1:]
    if len(args) != 4:
        print(__doc__)
        return 2
    for label, target in zip(("search", "detail", "toc", "content"), args):
        if target == "-":
            continue
        status, text, final = fetch(target)
        report(label, target, text, status, f"/tmp/probe_{label}.html")
        print(f"   final={final}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
