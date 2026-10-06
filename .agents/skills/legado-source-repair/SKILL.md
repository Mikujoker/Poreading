---
name: legado-source-repair
description: 用端上 harness 诊断并修复 Legado 书源（搜索/详情/目录/正文/封面/登录/反爬）。当书源搜不到、解析为空、书名是"请稍候…"、封面缺失、被 Cloudflare 拦、反复要求登录，或需要新增/修订书源规则时使用。判定一律以端上真引擎为准，禁止凭"看起来对"下结论。
---

# Legado 书源修复（端上 harness）

## Purpose

把「爬真页面 → 定位失败字段 → 单字段 patch → 端上真引擎验证 → 写回/回滚」做成可复现的流程。
关键不是让 LLM 直接写规则（它不知道这些坑），而是**让 agent 拿着 harness 走流程**。

## 硬约束

1. **只有端上真引擎能判 PASS**：`POST /debugBookSource`。本地 `source_check.py`/PC 侧 HTTP 只能用来取证据；
   CF 站用 PC 侧 HTTP 测出的 403 不代表端上不行，反过来 PC 侧 200 也不代表端上能过。
2. **单字段 patch**：一次只改一个字段；写回前记 before 值；验证退化就回滚。
3. **判据必须先校准**（今天踩过）：拿「已知好值」跑判据必须 ✓、拿「已知坏值」必须 ✗，否则不许用它驱动 AI。
   坏判据比没有判据更糟——AI 会围着假失败空转。
4. **先自检出口 IP**，手机别挂梯子：机房 IP 会被 CF 直接判死，后面所有步骤都白做。
5. 不许"抄库里旧源"当结论；必须爬真页面。

## 前置（每台机器一次）

```bash
adb forward tcp:1122 tcp:1122      # app 的「Web 服务」必须开着（建议顺手开「启动应用时打开Web服务」）
```

- HTTP 端口 **1122**；WS 版 `/bookSourceDebug` 在 `webPort+1` = **1123**（`api.md` 里的 1234/1235 是上游示例）
- 装机/编译：`~/build3.sh` → `adb install -r`；**书源是数据，改动零编译**（走 `/saveBookSource` 直推）
- `adb shell am start` 会让 pi 的 bash 工具在该行之后中断 → 驱动写成脚本 + `nohup bash <脚本> &`

## Harness 清单

app 侧 HTTP 原语（`http://127.0.0.1:1122`）：

| 接口 | 用途 |
|---|---|
| `GET /fetchPage?url=&source=&delay=&webView=0\|1&maxChars=` | **用手机 WebView 抓页面**（带书源 header/cookie，能过 CF） |
| `GET /verifyLogin?source=&url=&title=` | 拉起**内置浏览器**做人工验证/登录（阻塞到用户完成；右上角 ✓ 提交、返回=取消），完成后 cookie 落库 |
| `POST /debugBookSource {tag,key,timeoutMs}` | **端上真引擎**：一次跑完 搜索→详情→目录→正文，回结构化事件 |
| `GET /getCookie?url=` | 登录态三层对照：`webViewCookie`/`jarCookie`/`dbCookie` |
| `GET /getBookSource?url=` / `POST /saveBookSource` | 读写书源（写回/回滚） |
| `GET /getBookshelf` / `POST /saveBook` / `GET /image?url=<bookUrl>&path=<img src>` | 读写书目、取图 |

`project/tools/` 脚本：

| 脚本 | 用途 |
|---|---|
| `book_debug.py` | 跑端上真引擎；`--key` 以 `--`/`++` 开头要写 `--key=--https://…`；`--cookie <url>` 看登录态 |
| `ai_repair_source.py` | 单字段修复循环（出口自检→取页分级→探针→LLM patch→端上验证→回滚/写回；预算 3 轮/10 分钟/200k tokens）；`--check-only` 校准判据、`--dry-run` 只出提案 |
| `backfill_covers.py` | 批量补空封面（只补空的、只在站点真给出封面时写） |
| `probe_site.py` / `source_check.py` | 纯 HTTP 站的本地探针/校验（**CF 站无效**） |

## 流程

**阶段 0 出口自检**：`/fetchPage` 抓 `cloudflare.com/cdn-cgi/trace` 看 `loc`/`colo`。机房 IP → 换出口再谈。

**阶段 1 取页（分级升级）**：`/fetchPage(webView=1)` → 命中挑战页标记
（`Just a moment` / `请稍候` / `Attention Required`）→ 延迟拉长重试 → 仍被挡 → `/verifyLogin` 人工过验证（一次通过长期有效）。

**阶段 2 定位失败字段**：读 `debugBookSource` 事件的 `┌标签` / `└值` 对：

| 事件标签 | 字段 | | 事件标签 | 字段 |
|---|---|---|---|---|
| 获取书籍列表 | `ruleSearch.bookList` | | 获取目录列表 / 目录总数 | `ruleToc.chapterList` |
| 获取书名 / 作者 / 分类 / 简介 | `ruleBookInfo.*` | | 首章信息 / 章节链接 | `ruleToc.chapterName/chapterUrl` |
| 获取封面链接 | `ruleBookInfo.coverUrl` | | 获取正文内容 | `ruleContent.content` |
| 获取详情页链接 | `ruleSearch.bookUrl` | | 获取目录链接 | `ruleBookInfo.tocUrl` |

**阶段 3 探针（结构层，别猜）**：机械列候选——`<img>` 属性与数量、含内联样式的容器、链接密度最高的父节点、
中文字数最大的块；再据此选最小选择器。

**阶段 4 单字段 patch**：语法必须合法（见下），默认与当前值同族、最小改动。

**阶段 5 端上验证**：写入 → `debugBookSource` 跑对应页面 → 按「判据表」断言 → 不过就回滚。

**阶段 6 写回 + 回归**：搜索/详情/目录/正文各跑一次；任一退化回滚。

## 规则语法（两次翻车点）

- **CSS**：`选择器` + 取值后缀 `@text`/`@href`/`@src`/`@html`/`@ownText`/`@属性名`；
  属性选择器**必须带引号**：`a[href^='/book/']`；支持 `:contains()`/`:has()`/`:not()`（Jsoup）
- **XPath**：以 `//` 开头，**属性写在路径里**：`//td[@width='20%']/img/@src`。
  ⚠️ 末尾不能接 `@src`（`//a@href` 是错的，应为 `//a/@href`）
- **JS**：`@js:脚本`，可用 `result`/`baseUrl`/`key`/`java`/`Packages.*`
- `webView` 是「每个 URL 的选项」，不是源级开关：`href@js:result + ',{"webView":true,"webViewDelayTime":3000}'`
- `{{...}}` 是内联 JS（可用于 URL 里做预编码）；`{{page}}` 取当前页
- `loginCheckJs` 会同时跑在 Rhino（书源规则）和 WebView 页面里 → 只能用双端都有的 `indexOf`
  （`.contains` 只有 Java String 有；`.includes` 只有 JS String 有）

## 判据表

| 字段 | 判据 |
|---|---|
| `bookList` | 事件 `列表大小 > 0` |
| `name`/`author`/`intro` | `获取书名/作者/简介` 非空 |
| `coverUrl` | `获取封面链接` **恰好 1 个** http URL，且图片能取到（多个 = 多张封面拼成多行，是无效 URL） |
| `chapterList` | `目录总数 > 0` |
| `content` | 正文中文字数 > 200 且**不含站点 UI 词**（关灯/字号/章节报错/上一章/加入书架…） |
| `tocUrl`/`chapterUrl` | 能取到下一页并解析成功 |

全局前置：文本里出现挑战页标记 → 直接判 ✗（否则 `书名="Just a moment..."` 会被当成"解析成功"）。

## 坑表（全部实测）

1. **POST searchUrl 会先被 OkHttp 发一次** → CF 站必吃挑战页，且挑战页会被当详情页解析
   （症状：`书籍总数:1`、书名"请稍候…"、`tocUrl=http://localhost/`）。能 GET 就别 POST。
2. **GBK 站 + webView**：WebView 强制按 UTF-8 编 URL，`charset` 选项只对 OkHttp 生效 →
   关键词要在 URL 里预编成 GBK 转义（`{{...}}` 内联 JS），否则站点解出 `鏂囧∨皯濂` → 空结果。
3. **「唯一命中」会 302 到详情页**：宽选择器会把详情页的"同类推荐"当搜索结果（症状：搜出 9 本却都不是你要的）→
   `bookList` 只匹配结果表格；列表为空时 Legado 会**按详情页解析兜底**，正好得到那一本。
4. **搜索需要登录**：未登录会 302 到 `login.php?jumpurl=<搜索URL>`，拿到的是登录表单页。
5. **详情页多张封面**：`img[src*=…]@src` 会命中"同类推荐"封面 → 必须限定在本书信息块内。
6. **cookie 空值覆盖**：读写 cookie 处都要挡空值，否则一次读不到就把有效会话写成空串 →
   表现为"老大不小反复要登录"（本轮真凶）。
7. **bookUrl 尾部选项必须是合法 JSON**：`{"webView":true}` 而不是 `{'webView': true}`，否则引擎解析不到选项。
8. **判正文别看日志首行**：正文是 `└\n<正文>`（跨行），只取首行会误判成空。
9. **WS 端口**：`/bookSourceDebug` 在 `webPort+1`（默认 1123）；连 1122/1235 都是 404。
10. **装机后 Web 服务随进程停止**（除非开了自启开关）。

## 反例（不许这样做）

- 拿本地 `source_check.py` 给 CF 站判 PASS（PC 侧 requests 必被拦）
- 抄库里旧书源当结论、或看截图猜结论
- 让 LLM 直接产出规则却不做端上验证、也不校准判据
- 把「挑战页/登录页」的解析结果当好结果（必须先挡标记）

## References

- `references/wenku8-v11-retro.md` —— 实战复盘：wenku8 v5→v11 每个症状→根因→修法，含最终可用源
- `../../AI-SOURCE-AGENT.md` —— 五层架构、CF L-1~L6、LLM 单字段 patch 契约
