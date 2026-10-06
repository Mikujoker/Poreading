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
| `POST /repairSource {source,key,field?,page?,rounds?}` | 单字段/端点修复循环，**直接回完整 JSON 报告**（不用截图） |
| `POST /generateSource {site,key,name?,attempts?}` | **整源生成/修复**：搜索→详情→目录→正文，**每组通过即写回** |
| `POST /fillLoginFields {source,loginUrl?}` | 只补登录能力字段（loginUrl/loginUi/loginCheckJs/cookieJar），不动规则 |
| `GET /getRepairJournal` | 读 app 内「AI 修复」最近一次运行状态（步骤/判据/结论） |
| `POST /saveAiProfile` / `GET /getAiProfile` | 配 / 读 AI 对话模型（key 只写不读） |
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

## app 内流程（手机全流程，2026-10-07 落地）

入口：书源管理 → 行菜单「**AI 修复**」→ 目的地 `source/book/repair`。实现都在
`app/src/main/java/io/legado/app/ui/book/source/repair/`：
`SourceRepairViewModel`（循环/预算）、`SourceRepairEngine`（判据/探针/提示词/语法预检）、`SourceRepairScreen`（界面）。
它复用 app 里现成的三件东西：`Debug`（端上真引擎）、`AnalyzeUrl`（WebView 取页）、`SourceVerificationHelp`（内置浏览器人工验证）、
`AiTextGateway` + `AiProfileGateway`（LLM，key 存设备加密区，不出手机）。

**流程**（与 PC 侧脚本同一套判据）

0. 出口自检：抓 `cloudflare.com/cdn-cgi/trace` 看 `loc`/`colo`（机房 IP 直接判死）。
1. **取页分级**：WebView 抓 → 拉长延迟重试 → 仍不行就**自动弹出内置浏览器**让人工登录。
   需要人工的判定 = 挑战页标记 / **登录墙**（有 `type="password"` 或「请先登录」话术）/ **空壳页**（< 400 字节）。
   弹窗优先用源的 `loginUrl`；关键词路径也会弹（非 http 输入一律用站点首页兜底，绝不把关键词当网址打开）；
   登录产生的 cookie 落 jar，之后自动重试。
2. **全链自检**：跑一次 `Debug`，按自动挑顺序（搜索 → 详情 → 目录 → 正文）取出**第一个失败字段**。
3. **自主换路**：目标页拿不到 → 改抓**站点首页**当探针，目标字段切成 `searchUrl`，让 LLM 反推真实搜索端点
   （探针会列 `<form action/method/字段>`、含 `search` 的 URL 片段、站内搜索链接）。
4. **循环**（默认 3 轮 / 10 分钟 / 200k tokens）：机械探针 → LLM 单字段 patch（提示词含规则语法速查 + 本地语法预检）
   → **只在端上真引擎断言通过后才写回**；失败写入 history 换策略重试；连续两次空提案 → 判定"页面没有该元素"，保持现状。

**可修字段**：`searchUrl`、`ruleSearch.{bookList,name,bookUrl,coverUrl}`、
`ruleBookInfo.{name,author,intro,coverUrl,tocUrl}`、`ruleToc.{chapterList,chapterUrl,chapterName}`、`ruleContent.content`。

**前置与坑**
- app 里必须先配好 AI 对话模型（「我的 → AI 设置」）；没配时流程会在结论里明确提示，不会假装在修。
- 端上引擎是**全局单会话**：跑修源会顶掉 app 内调试页正在跑的会话。
- 实测过的自主性边界（2026-10-07，bilinovel）：自动取页拿到真实搜索页 71 KB、自动挑出 `ruleSearch.bookList` ✓；
  该站搜索是 JS/POST 形态（GET `/search/<key>_<page>.html` 返回 39 字节空壳），正是 `searchUrl` 这条自主换路的目标。

## 整源生成 + 登录能力（2026-10-07，app 内）

**整源生成** `POST /generateSource {site,key}`（`SourceRepairRunner.generate()`）：
给站点 URL + 一个关键词，按 `首页/搜索页 → 搜索组 → 详情组 → 目录组 → 正文组` 逐组生成规则，
**每组一通过就立刻写回**（不是"全通才写"），每组最多 2 次 LLM 修正。

- 搜索组：先从页面机械抠搜索表单（`searchForm()`：action / method / 关键词字段 / 隐藏字段）→ 直接拼 `searchUrl` 交给引擎验；
  不通过才问 LLM；仍不通过则走**端点变体扫描**（`searchUrlVariants()`：`search_guard=css` 降级、POST↔GET、
  页面里出现的 `/search/x_{{page}}.html` 形态）——这条是确定性的，专治 JS 守卫站的降级入口。
- 详情/目录/正文组：机械探针 → LLM 出**一整组 JSON** → 端上真引擎断言 → 通过写回；失败把「引擎日志片段」回喂给下一轮。
- **登录能力**：任何取页遇到挑战页 / 登录墙（有 `password` 框或「请先登录」）/ 空壳页（<400 字节），
  就弹内置浏览器；你登录完**自动**补齐并写回 `loginUrl`（若空）+ `loginUi`（账号/密码）+
  `loginCheckJs`（`退出登录`/`logout`/`个人中心` 检测）+ `enabledCookieJar=true` —— 对齐 wenku8 的做法：
  **登录一次、长期复用**。已经有会话只想补字段时用 `POST /fillLoginFields`。

**判据修正（重点）**：`bookList`/`searchUrl` 不能只看 `列表大小>0`——
站点把**唯一命中** 302 到详情页时列表本就为空，Legado 会用详情页兜底解析出 1 本；
所以判据是「`列表大小>0` **或** `书籍总数≥1`」。判错会把正常行为判成失败，逼 LLM 反复改一个没坏的选择器（已踩过）。

**报告直达**：`/repairSource`、`/generateSource` 都返回 `steps[]`（每步 ok + 证据）与 `log`，
`/getRepairJournal` 读界面运行状态 —— **验证一律走 JSON，不截图**。

## 登录类问题速查（最常见的三种，2026-10-07 实测）

**先分清两种"被挡"**：人机验证（CF，看 IP/指纹）≠ 登录（看账号/cookie）。前者靠真人过验证，后者靠 cookie 落库 + 能判定登录态。

| 症状 | 根因 | 修法 |
|---|---|---|
| 每次点「登录」都要重输密码；登录页永远出现；登录完 app 仍认不出"已登录" | 源里**有 `loginUrl` 但缺 `loginUi`（登录表单）和 `loginCheckJs`（登录态检测）** —— 库里有大量这种源（实测 m.wenkuchina / m.linovelib / www.linovelib …） | `POST /fillLoginFields {source}` 一次补齐三件套：`loginUrl` + `loginUi`（账号/密码）+ `loginCheckJs`（`退出登录`/`logout`/`个人中心` 检测）；运行中若要你登录，登录成功后**也会自动写回** |
| 登录了，但读正文/目录仍提示要登录 | `enabledCookieJar=false`（登录 cookie 根本不参与请求），或 jar 里 cookie 是**空串**（历史 bug：读不到时写空覆盖） | 打开 cookieJar；用 `GET /getCookie` 做三层对照：`webViewCookie` 有、`jarCookie`/`dbCookie` 空 = 没落库；空串覆盖已修（空值不写 + 主线程读取） |
| 过了 CF 验证/登录，过一会儿又不行 | `cf_clearance` 绑 **IP + UA** 且有 TTL；换网络或过期即失效 | 重新点一次（一次人工、长期复用）；别挂梯子；JS 计算的搜索见下一节 |
| 站点根本不出现登录页，但内容要登录 | 源没配 `loginUrl` | 用 `SourceRepairEngine.loginLinks()` 从页面链接里找登录页（href/text 含 login/登录）；或让流程遇到登录墙时弹内置浏览器，登录后自动写回 |

**机械识别"这站需要登录"**（判据，别靠感觉）：
1. 页面里有 `type="password"` 或「请先登录 / 登录后可见 / 用户登录」→ **登录墙**；
2. 内容页返回的正文里出现登录提示、或 `书籍总数:0` 且页面是登录表单 → 需要登录；
3. 首页/任意页里有 href 或文本含 `login`/`登录` 的链接 → 那就是 `loginUrl` 候选。

**处理顺序**（照做即可）：
1. 取页 → 命中上面 1/2 → 弹内置浏览器（`SourceVerificationHelp`，优先打开 `loginUrl`，非 http 输入用站点首页兜底）；
2. 你登录完 → 自动写回 `loginUi` + `loginCheckJs` + `enabledCookieJar=true`；
3. 立刻重试取页并**明确回报**：`已拿到可用页面｜登录 cookie N 字节` 或 `仍然拿不到｜登录 cookie 0 字节（需要再登一次）`；
4. 要独立确认用 `GET /getCookie?url=`（三层）与 `/fillLoginFields`（补配）。

## JS 计算的搜索结果（jieqi / search_guard 类，2026-10-07 实测 bilinovel）

**症状**：源里 `searchUrl` 是 `<js>` 分支（第 1 页 POST `/search.html`），引擎日志里能看到
`≡获取成功:/search.html,{"body":"searchkey=…","method":"POST"}`，紧接着 `bookList` 的 JS 打出
`jieqiSearchCss=…/jieqiSearchJs=…` 然后 `└列表为空,按详情页解析` → `◇书籍总数:0` → `︽未获取到书籍`。

**根因**：站点把搜索结果交给**前端 JS 计算**（`search_guard=js` 守卫），服务端返回的是壳页面。
所以换选择器、换 `search_guard=css/js`、换 GET/POST 全都拿不到列表——**这不是规则能修的类别**。

**正确处置**（按代价递增）：
1. **认出来并认输**：报告 `needs_human` + 归类「JS 计算」，不要浪费 N 轮去改选择器；
   判据侧加了机械提示：页面含 `search_guard` / `jieqiSearch` / `__cf` / `challenge-platform` → 探针直接告警。
2. **webJs 路线**：`searchUrl` 用真实页面 + `"webJs":"<在 WebView 里执行站点自己的搜索并等结果渲染>"`，
   让 WebView 替我们跑站点的 JS（拿渲染后的 DOM），规则再针对渲染后结构写。
3. **模仿 XHR**：从站点 JS 里找出它真正的搜索接口（`ajax`/`fetch` 目标 + 参数 + 签名），
   写成 `@js:` 规则直接调；这需要读站点 JS，属于"agent/MCP 实操"范畴。
4. 详情/目录/正文通常**不受影响**（只有搜索走 JS）——可以先靠"添加网址"把书加进来读，
   把搜索留作已知缺陷，别为它把整源改坏。
