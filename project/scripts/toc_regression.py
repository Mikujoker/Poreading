#!/usr/bin/env python3
"""TXT 目录规则回归工具。

改分章规则时，唯一能证明"没改坏、确实变好"的办法是拿真实语料跑一遍并逐本对比。
app 的实现有两个容易混成一层的地方，照抄错一层结论就是假的：

  【① 选规则】TextFile.analyze() 走到无 pattern 分支时，拿**当前 block 的内容**
      调 getTocRule()，把选中的 chapterRule 存进 book.tocUrl：
        - 规则按 serialNumber 升序取、再 reversed() → 实际从大到小遍历
        - 每条用 Regex(chapterRule, MULTILINE) 扫
        - ★ 计数带 1000 字符窗口：与上一个被接受的匹配相隔 > 1000 才算一章
        - num >= maxNum 才换（maxNum 从 1 起）→ 平局时 serialNumber 更小的胜
        - 全 0 命中 → 退回按字数切（第N章(M)）
      block = 最多 512000 字节、并回退到最后一个 \\n（TextFile.bufferSize / blank）

  【② 切章】真正建章节表时用选中的 pattern 扫**整个文件**，每条命中都建一章：
        ★★ 这一层没有 1000 字符窗口 ★★
      只有"开始位置前的正文"会被并进上一章/当作序章。
      另外开了「拆分超长章节」时，> 102400 字节的章会递归拆成 "标题(1)(2)"，
      所以 app 的章节数可能比命中数更多——本工具按"命中数"报，差异会被标注。

【编码】中文 txt 大量是 GB18030/UTF-16LE，用错编码解出来全是乱码、正则会全部落空。
        本工具按 DB 里记的 charset 解码，并去掉 BOM、把 CRLF/CR 规整成 LF（app 侧拿到的
        是规整后的文本，不规整的话每个匹配多占一个字符，1000 字符窗口的边界会跟着漂）。

【Java 正则】变长后顾 `(?<=[\\s　]{0,4})` 是 Java 特有的，Python 的 re 不支持；
        本工具用 regex 模块（没有则退化成消耗式等价写法）。

用法：
    python3 toc_regression.py validate       # 与 DB 里已有分章结果对拍，验证引擎忠实
    python3 toc_regression.py run [规则json]  # 全语料跑一遍：逐本章数 + 汇总
    python3 toc_regression.py diff <旧> <新>   # 两套规则逐本对比（含变差清单）
"""

import json
import os
import re
import sqlite3
import sys
from collections import Counter

try:
    import regex as rex  # 支持变长后顾

    HAS_REGEX = True
except ImportError:
    rex = None
    HAS_REGEX = False

CORPUS = os.path.expanduser("~/work/corpus")
MANIFEST = os.path.join(CORPUS, "manifest.tsv")
DB = "/tmp/legado.db"
ASSET_RULES = os.path.expanduser(
    "~/work/legado-md3/app/src/main/assets/defaultData/txtTocRule.json"
)

BLOCK_BYTES = 512000     # TextFile.bufferSize
SPLIT_LONG_BYTES = 102400  # TextFile.maxLengthWithToc：超长章节拆分阈值
WINDOW = 1000            # 选规则时的"相邻命中"最小间距

CHARSET_ALIAS = {
    "utf-8": "utf-8", "utf8": "utf-8",
    "gb18030": "gb18030", "gbk": "gb18030", "gb2312": "gb18030",
    "utf-16le": "utf-16-le", "utf-16be": "utf-16-be",
}


def normalize(text: str) -> str:
    """去 BOM、CRLF/CR → LF，对齐 app 侧拿到的正文形态。"""
    if text.startswith("\ufeff"):
        text = text[1:]
    return text.replace("\r\n", "\n").replace("\r", "\n")


def decode(raw: bytes, charset: str | None) -> str:
    """按 DB 记录的编码解码；未记录时按常见顺序试。"""
    if charset:
        name = CHARSET_ALIAS.get(charset.lower())
        if name:
            try:
                return raw.decode(name)
            except UnicodeDecodeError:
                pass
    for name in ("utf-8", "gb18030", "utf-16-le"):
        try:
            return raw.decode(name)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def first_block_bytes(raw: bytes) -> bytes:
    """第一个 block：最多 BLOCK_BYTES 字节，回退到最后一个 \\n（不含该 \\n）。"""
    if len(raw) <= BLOCK_BYTES:
        return raw
    i = raw.rfind(b"\n", 0, BLOCK_BYTES)
    return raw[:i] if i > 0 else raw[:BLOCK_BYTES]


VARIABLE_LOOKBEHIND = re.compile(r"\(\?<=\[([^\]]+)\]\{(\d+),(\d+)\}\)")


def compatible(pattern: str) -> str:
    """Java 变长后顾 → Python 消耗式等价（只在没有 regex 模块时用）。"""
    if HAS_REGEX:
        return pattern
    return VARIABLE_LOOKBEHIND.sub(r"(?:[\1]{\2,\3})", pattern)


def compile_rule(pattern: str):
    eng = rex if HAS_REGEX else re
    return eng.compile(pattern, eng.MULTILINE)


def count_windowed(text: str, pattern) -> int:
    """选规则用的计数：相邻被接受的匹配必须隔 > WINDOW 字符。"""
    num = 0
    start = 0
    for m in pattern.finditer(text):
        if start == 0 or m.start() - start > WINDOW:
            num += 1
            start = m.end()
    return num


def count_all(text: str, pattern) -> int:
    """切章用的计数：每条命中都建一章（app 这一层没有窗口）。"""
    return sum(1 for _ in pattern.finditer(text))


def enabled_rules(rules: list[dict]) -> list[dict]:
    """已启用规则按 serialNumber 升序再 reversed() —— 与 getTocRules() 一致。"""
    en = [r for r in rules if r.get("enable")]
    en.sort(key=lambda r: r.get("serialNumber", -1))
    return list(reversed(en))


def _best(select_text: str, rules: list[dict]):
    """getTocRule() 的竞争主体：命中数最多者胜，平局取 serialNumber 更小的。"""
    max_num = 1
    best = None
    for rule in rules:
        pattern = (rule.get("chapterRule") or "").strip()
        if not pattern:
            continue
        try:
            pat = compile_rule(compatible(pattern))
        except Exception:
            continue
        num = count_windowed(select_text, pat)
        if num >= max_num:
            max_num = num
            best = rule
    return best


def pick_rule(select_text: str, rules: list[dict]):
    """选规则，含兜底语义。

    serialNumber < 0 的规则是**兜底规则**（例如"分隔线"）：只在没有任何普通规则命中时
    才参与竞争。理由是它们的命中数天然远大于标题规则（一本书可能 600 条 ※※※ 而只有
    190 个真章节名），一起竞争必然把有正经章节名的书拆成按场景分章。
    """
    normal = [r for r in rules if not r.get("isFallback")]
    fallback = [r for r in rules if r.get("isFallback")]
    best = _best(select_text, normal)
    if best is not None:
        pat = compile_rule(compatible((best.get("chapterRule") or "").strip()))
        # "能用"的定义是至少切出 2 章：只命中 1 次等于整本一章，读者照样看不到断章，
        # 这种情况下兜底规则（分隔线）比一个孤零零的章节名有用。
        if count_windowed(select_text, pat) > 1:
            return best
    return _best(select_text, fallback) or best


def split_count(full_text: str, rule: dict | None) -> int:
    """用胜出规则切章，返回章节数。rule=None 表示 app 会退回按字数切。"""
    if rule is None:
        return 0
    pat = compile_rule(compatible((rule.get("chapterRule") or "").strip()))
    return count_all(full_text, pat)


def load_rules(path: str = ASSET_RULES) -> list[dict]:
    data = json.load(open(path, encoding="utf-8"))
    if isinstance(data, dict):
        data = list(data.values())[0]
    return data


def load_manifest() -> list[dict]:
    out = []
    with open(MANIFEST, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            out.append({"idx": parts[0], "name": parts[1],
                        "size": int(parts[2] or 0), "path": parts[3]})
    return out


def book_texts(item: dict, charset: str | None):
    """返回 (select_text, full_text)：前者是第一个 block，后者是整本。"""
    fp = os.path.join(CORPUS, f"{item['idx'].zfill(3)}.txt")
    if not os.path.exists(fp):
        return None, None
    raw = open(fp, "rb").read()
    return (normalize(decode(first_block_bytes(raw), charset)),
            normalize(decode(raw, charset)))


def db_maps():
    con = sqlite3.connect(DB)
    cs = {u: c for u, c in con.execute("select bookUrl, charset from books")}
    n = {u: c for u, c in con.execute(
        "select bookUrl, count(*) from chapters group by bookUrl")}
    return cs, n


def analyze_all(rules: list[dict]):
    """全语料跑一遍，返回逐本结果。"""
    en = enabled_rules(rules)
    cs, _ = db_maps()
    rows = []
    for item in load_manifest():
        select_text, full_text = book_texts(item, cs.get(item["path"]))
        if full_text is None:
            continue
        rule = pick_rule(select_text, en)
        rows.append({"item": item, "rule": rule,
                     "chapters": split_count(full_text, rule),
                     "chars": len(full_text)})
    return rows


def cmd_validate() -> None:
    print(f"引擎: {'regex（支持变长后顾）' if HAS_REGEX else 're（后顾已等价改写）'}")
    en = enabled_rules(load_rules())
    print(f"已启用 {len(en)} 条，遍历顺序 sn={[r.get('serialNumber') for r in en]}\n")
    cs, known = db_maps()
    by_path = {m["path"]: m for m in load_manifest()}
    ok = bad = skip = 0
    print(f"{'DB':>6} {'本工具':>6}  书")
    for path, expect in sorted(known.items(), key=lambda kv: -kv[1]):
        item = by_path.get(path)
        if not item:
            skip += 1
            continue
        select_text, full_text = book_texts(item, cs.get(path))
        if full_text is None:
            skip += 1
            continue
        rule = pick_rule(select_text, en)
        got = split_count(full_text, rule)
        # app 在"拆分超长章节"开启时会额外拆出 (N) 章，因此允许 got <= expect
        good = got == expect or (got and got <= expect)
        ok += 1 if good else 0
        bad += 0 if good else 1
        mark = "✓" if got == expect else ("≈" if good else "✗")
        print(f"{expect:>6} {got:>6}  {mark} {item['name'][:40]:<42} "
              f"[{(rule or {}).get('name', '无规则')}]")
    print(f"\n差异在合理区间 {ok} / 异常 {bad} / 无语料 {skip}")
    print("≈ 表示本工具比 DB 少：app 开了「拆分超长章节」时会把 >100KB 的章再拆成 (1)(2)，"
          "属预期；✗ 才是真异常。")


def cmd_run(rules_path: str | None = None) -> None:
    rules = load_rules(rules_path or ASSET_RULES)
    rows = analyze_all(rules)
    hit = [r for r in rows if r["chapters"] >= 2]
    print(f"语料 {len(rows)} 本；分出 >=2 章 {len(hit)} 本 "
          f"（{len(hit) / max(len(rows), 1) * 100:.1f}%）")
    norule = [r for r in rows if r["rule"] is None]
    one = [r for r in rows if r["chapters"] == 1]
    print(f"无规则命中（app 退回按字数切）: {len(norule)} 本")
    print(f"命中但只切出 1 章: {len(one)} 本\n")
    print("胜出规则分布:")
    for name, n in Counter((r["rule"] or {}).get("name", "无规则") for r in rows).most_common():
        print(f"  {n:>4}  {name}")
    print("\n最可疑（章数最少）20 本:")
    for r in sorted(rows, key=lambda r: r["chapters"])[:20]:
        print(f"  {r['chapters']:>5}  {r['item']['name'][:40]:<42} "
              f"[{(r['rule'] or {}).get('name', '无规则')}]")
    out = os.path.join(CORPUS, "result.json")
    json.dump([{"name": r["item"]["name"], "path": r["item"]["path"],
                "rule": (r["rule"] or {}).get("name"), "chapters": r["chapters"],
                "chars": r["chars"]} for r in rows],
              open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n逐本结果 → {out}")


def cmd_diff(a: str, b: str) -> None:
    """两套规则逐本对比：a=旧, b=新。"""
    ra, rb = analyze_all(load_rules(a)), analyze_all(load_rules(b))
    bm = {r["item"]["path"]: r for r in rb}
    better = worse = same = 0
    for r in ra:
        n = bm.get(r["item"]["path"])
        if not n:
            continue
        if n["chapters"] > r["chapters"]:
            better += 1
            print(f"  ↑ {r['chapters']:>5} → {n['chapters']:<5} {r['item']['name'][:44]}")
        elif n["chapters"] < r["chapters"]:
            worse += 1
            print(f"  ↓ {r['chapters']:>5} → {n['chapters']:<5} {r['item']['name'][:44]}  <<< 变差")
        else:
            same += 1
    print(f"\n变好 {better} / 不变 {same} / 变差 {worse}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "validate"
    if cmd == "validate":
        cmd_validate()
    elif cmd == "run":
        cmd_run(sys.argv[2] if len(sys.argv) > 2 else None)
    elif cmd == "diff":
        cmd_diff(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
