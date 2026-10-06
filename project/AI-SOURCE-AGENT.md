# AI 书源工作系统（设计）

> 目标：给「读」这个 app **自动产出与修订书源**。输入 = 站点 URL（＋可选账号密码）；
> 输出 = **经过验证**的书源 JSON ＋ 一份可复核的证据。人在回路（人审后才写回）。

---

## 0. 今晚已经跑通的部分（可运行，不是纸面）

| 文件 | 作用 | 状态 |
|---|---|---|
| `project/tools/probe_site.py` | 结构化探针：抓四类页面，打印候选容器/字段 | 跑通（bixia / biquge365 / gutenberg） |
| `project/tools/source_check.py` | **确定性校验器**：拿真实页面验每条规则，逐字段 PASS/FAIL/SKIP | 跑通 |
| `project/book-sources/biquge365.net.json` | 机制样板书源（可直连站点） | **四类规则全 PASS** |
| `legado-work/` 下的驱动脚本 | 装机/截图/日志（见 HANDOFF 第三轮） | 跑通 |

样板的校验输出（复现：`cd project/tools && python3 source_check.py ../book-sources/biquge365.net.json --keyword 大梦主`）：

```
搜索页  PASS bookList(5行) PASS name PASS author PASS bookUrl
详情页  PASS name PASS intro PASS coverUrl
目录页  PASS chapterList(21项) PASS chapterName PASS chapterUrl
正文页  PASS content（非空）
```

**wenku8 的实测结论**：`www.wenku8.cc` → `www.wenku8.net`，curl（含移动 Chrome UA）与 WSL/Windows
headless Chrome **一律 403 + Cloudflare「Sorry, you have been blocked」** —— 这是 WAF 的**硬拦**
（error 1020 类），不是可解的 JS 挑战。对照：`hetushu.com`/`69shuba.com` 是 403「Just a moment...」
（**托管挑战**，真浏览器可过）；`69shu.pro` 是自定义 JS 壳；`bixia.org` 的 `/book/` 已是域名停放页。
→ 结论：**这类站点必须靠真实浏览器/端上 WebView 拿会话**，纯 HTTP 学多少姿势都没用。

**2026-10-07 补充（关键反转：是出口 IP，不是浏览器）**

同一天稍后复测，**真实 Chrome + 绕过系统代理（直连中国家庭宽带）→ 直接过 CF**，
拿到真站首页并被跳到登录页：

| 出口 | `cloudflare.com/cdn-cgi/trace` | 真实 Chrome 访问 wenku8 |
|---|---|---|
| 本地梯子 `127.0.0.1:7890` | `ip=38.207.136.135 loc=JP colo=NRT`（日本机房） | 403 拦截页 |
| **直连（不走代理）** | `ip=1.194.68.229 loc=CN colo=LAX`（家庭宽带） | **200 真站 + 跳登录页** |

所以先前"WAF 硬拦、纯 HTTP 无解"的结论要修正为：
**先看出口 IP，再谈指纹。** 机房/代理 IP 在 CF 的 bot score 上先被判死，
此时 TLS 指模（`curl_cffi`）、headless、甚至真浏览器都过不去；
换到住宅/移动 IP 之后，真浏览器一把就过。

落地到工程：
- **L1 之前先加一步 L-1：出口 IP 自检**（`cdn-cgi/trace` 看 `loc`/`colo`，
  数据中心 IP 直接换出口，别浪费后面所有姿势）；
- 端上同理：**手机上如果挂着梯子，book source 会被 CF 拦**，
  这也很可能就是"老是得反复登录"的真凶之一（IP 一变，旧 `cf_clearance` 与风控信任一起失效）。

工具：`project/tools/cdp_fetch.py`（L2 会话层，真 Chrome + CDP；独立 profile，不动用户的窗口）：
```bash
CDP_PORT=9223 CDP_EXTRA_ARGS="--no-proxy-server" \
  python3 cdp_fetch.py fetch "https://www.wenku8.net/index.php" --wait 14 --out wk.html
python3 cdp_fetch.py cookies "https://www.wenku8.net/index.php"   # 导出 cookie 供 HTTP 复用
python3 cdp_fetch.py ua                                           # 写进书源 header 用
```

---

## 1. 边界与铁律

1. **只改失败的那一段。** 一个字段一条 patch，禁止整份重写、禁止顺手"优化"别的字段。
2. **验证通过才写回。** 校验器给不出 PASS 的规则不许进库；XPath/`@js:` 这类校验器覆盖不到的，
   必须走端上 `bookSourceDebug` 通道验，**不许"看起来通过"**（目录规则那轮已经付过一次学费）。
3. **不硬编码密钥。** LLM key 与站点账号密码存设置页加密区；仓库里只放 `.env.example`。
4. **每一次改动都留证据**：命中的选择器、抓到的样本值、验证命令的输出、以及"用的是哪一级会话手段"。
5. **登录类动作要用户明示授权**：账号密码只在用户明确给出后使用，且只用于他自己的账号。

---

## 2. 对象模型：四类页面 × 四类规则

Legado 书源的规则是**按页面类型**分组的，这是整个系统的骨架：

| 页面 | URL 来源 | 规则组 | 需要抽出的字段 |
|---|---|---|---|
| 搜索 | `searchUrl`（可 GET/POST，`{{key}}`） | `ruleSearch` | `bookList`（行）、`name`、`author`、`bookUrl`、`coverUrl`、`intro`、`kind`、`lastChapter` |
| 详情 | 搜索结果给的 bookUrl | `ruleBookInfo` | `name`、`author`、`intro`、`coverUrl`、`kind`、`lastChapter`、`tocUrl`（目录在别的页时） |
| 目录 | 详情页或 tocUrl | `ruleToc` | `chapterList`、`chapterName`、`chapterUrl`（后两个是**逐项**规则） |
| 正文 | 目录里的 chapterUrl | `ruleContent` | `content`、`nextContentUrl`、`webJs`、`sourceRegex` |

工程上要注意的语义细节（今晚踩到的，写进系统里当常识）：
- `chapterName`/`chapterUrl` 是**作用在 chapterList 每个元素上**的规则，不是整页规则；
- `text`/`href`/`html` 是取值后缀；`@css`/`@XPath`/`@json`/`@js:` 是另一套取值通道；
- CSS 属性选择器**要带引号**（Jsoup 与校验器的 soupsieve 都要求）；
- 站点编码要判：GBK 站点搜索关键词得按站点编码发（bixia 是 GBK，UTF-8 发过去是空结果）；
- 笔趣阁一类站点**详情页就是目录页**，别硬找第二个 URL。

---

## 3. 五层架构

```
输入(站点URL/账号) → ①会话层 → ②结构层 → ③生成层 → ④验证层 → ⑤交付层 → 人审 → 写回
                        ↑                                        │
                        └──────────── 失败证据回喂（最多 N 次）────┘
```

**① 会话层（拿页面）—— 唯一需要"对抗"的一层**
按代价从低到高逐级尝试，**每次记录"哪一级生效"**：
`L0` 纯 HTTP（真 UA/Referer/Client-Hints/Cookie）→ `L1` TLS 指模（`curl_cffi` `impersonate="chrome"`）
→ `L2` 真浏览器（Playwright+stealth / camoufox / nodriver）→ `L3` 复用 `cf_clearance`
（绑 IP+UA+TTL）→ `L4` 挑战求解（Turnstile 打码换 token）→ `L5` 住宅/移动代理
→ `L6` **端上 WebView**（Legado 自带：`loginUrl`+`loginUi` 登录，规则里可声明 `webView` 取源，
系统 WebView 过挑战后 cookie 进 app 共享 cookie jar 供 OkHttp 用）。
端上这条是**最实际**的一条——用户手上就有一台真机与真 cookie。

**② 结构层（探针）**
不做选择器猜谜：先机械地找"最有信息量的容器"——最多中文字符的块（正文）、
含最多同类链接的容器（目录）、结构化重复块（搜索结果行）。
落地就是 `probe_site.py` 的做法，输出候选而不是结论。

**③ 生成层（LLM）**
- provider：DeepSeek 为主，可切 OpenAI/Claude/智谱（OpenAI 兼容接口即可）；
- 输入：**失败字段的语义**（"这是搜索页的封面，应为绝对 URL"）＋ 裁剪后的 HTML（只给相关区域，
  控制 token）＋ 当前规则 ＋ 校验器的失败输出；
- 输出：**单字段 patch**（JSON）+ 置信度 + 它凭什么这么改（命中的选择器/样本值）；
- 硬约束写在 prompt 里：不得改其他字段、不得编造 URL、不得改 `searchUrl` 的域名、
  选择器不许带 `:contains` 之类 Jsoup 不支持的东西。

**④ 验证层（确定性，不许含糊）**
- 本地：`source_check.py`（CSS 子集 + 逐项语义 + `searchUrl` 的 POST 选项 + 站点 header）；
  覆盖不到的一律 **SKIP**，不许当成 PASS；
- 端上（权威）：`ws://127.0.0.1:1235/bookSourceDebug` 通道跑真引擎（XPath/`@js:`/`{{}}` 只有它能判），
  再用一次"真搜索 + 真取正文"的冒烟：书名能搜到、详情名作者非空、目录 ≥ N 章、
  正文非空且**不含站点 UI 词**（"关灯/字号/章节报错/上一章"这类，它们是选错容器最典型的症状）。

**⑤ 交付层（写回）**
只写 patch 命中的字段 → 生成可导入 JSON → 人审 diff → 应用（app 内导入，或 Web API `/saveBookSources`）
→ 记录证据（before/after + 验证输出）。

---

## 4. Cloudflare / 反爬：实测与手段

**今晚实测（同一台机器、同一 IP）**

| 目标 | 结果 | 判定 |
|---|---|---|
| wenku8.cc → .net | curl(移动 UA) / WSL headless Chrome / Windows headless 全 403 + "Sorry, you have been blocked" | **WAF 硬拦**（不是挑战） |
| hetushu.com、69shuba.com | 403 "Just a moment..." | **托管挑战**（真浏览器可过） |
| 69shu.pro | 200 但内容是 JS 混淆壳 + `searchhubfinder` 跳转 | **自定义反爬** |
| biquge365.net、txtnovel.com、gutenberg.org | 200 真内容 | 可直连（样板用） |

**手段清单（按代价排序，系统里逐级试）**

1. **L0 头指纹一致性**：UA / `Accept-Language` / `Sec-CH-UA*` 必须互相自洽；先访问首页拿 cookie 再请求。
2. **L1 TLS/JA3-JA4 指模**：`curl_cffi`（`impersonate="chrome"`）或 `tls-client`。许多站只认指模，
   这一级就能白拿很多页面，**且比浏览器快一个数量级**，应该永远先试。
3. **L2 真浏览器**：Playwright(+stealth) / **camoufox**（Firefox 反指纹） / nodriver / undetected-chromedriver。
   注意：**headless 也会被拦**（今晚实测），需要真显示（WSLg）或 xvfb + 更像人的输入节奏。
4. **L3 `cf_clearance` 复用**：cookie 与 `(IP, UA)` 绑定且有 TTL，必须同 IP 同 UA 复用；
   最稳的来源是"用户自己的浏览器/WebView 会话导出"。
5. **L4 挑战求解**：Turnstile/hCaptcha 用打码服务换 token；成本高，只在 L2 失败时上。
6. **L5 网络层**：住宅/移动代理。数据中心 IP 在 CF 的 bot score 上天然吃亏。
7. **L6 端上 WebView（Legado 特有，最实际）**：`loginUrl` + `loginUi` 让用户在 WebView 里登录/过挑战，
   cookie 进 app 的共享 cookie jar；规则里再声明 `webView` 取源。**不依赖任何"破解"**，
   走的是用户自己的真会话——合规风险也最低。

**2026-10-07 再补：`cf_clearance` 复用对 wenku8 无效 —— 必须 webView 取源**

实测（同一台机器、同一出口 IP、真浏览器已登录）：把浏览器导出的 **11 条 cookie**（含
`cf_clearance`）＋**完全一致的 UA** ＋Client Hints 交给 `requests` 取详情/目录页 →
**仍然 403 "Attention Required"**。也就是 Cloudflare 对本站的判定吃的是**浏览器指纹
（TLS/JS）而不是 cookie**。

推论（对书源设计的硬约束）：
1. **纯 HTTP（OkHttp）在 wenku8 上不可能工作** —— 不管 cookie 多真、UA 多像。
   本地的 `source_check.py`（requests）因此**无法验证这类站**，验证必须搬到端上真引擎
   （`bookSourceDebug` 通道，或真机点一次搜索/阅读）。
2. 这类站的书源只能走 **`webView` 取源**（`AnalyzeUrl` 的 URL 选项里有 `webView`，
   已核对代码）；`loginUrl` 的 WebView 负责登录，规则的取页也走 WebView 才能过 CF。
   代价：慢、且每个页面都要过一次 WebView。
3. 分工要写进流程：**本地校验器只负责"纯 HTTP 可达"的站**（如 biquge365 样板）；
   CF 硬站直接进端上验证队列，别在本地白跑。
4. 端上验证的最小闭环：导入书源 → 搜索一个已知书名 → 打开详情 → 目录 ≥ N 章 →
   正文非空且不含站点 UI 词（"关灯/字号/章节报错"）。

**风险与边界（必须写进系统提示里）**
- 绕反爬可能违反站点条款；只对用户自己有权访问的内容使用，不批量抓取、不绕过付费墙；
- 账号密码只用于用户自己的账号，且只走加密设置区，不进仓库、不进日志；
- 系统要能"承认打不过"：L0–L6 全失败就如实报告并交给用户手动登录，不许假装成功。

---

## 5. 任务数据与循环

```
Task(site, page_type, field, current_rule, status, attempts, evidence)
Evidence(level_used, request_headers?, selector, sample_value, verify_output)
```

循环（失败项驱动）：
1. 选一个 FAIL 字段 → 取该页（会话层逐级上）→ 结构层给候选；
2. 生成层出单字段 patch；
3. 验证层跑：本地校验 → （覆盖不到就）端上 bookSourceDebug；
4. 不过 → 把"失败输出 + 这次的选择器"回喂，最多 N=3 轮（超过就标 `needs_human`）；
5. 过了 → 人审 → 写回 → **跑一遍全书回归**（搜一个已知书名 → 详情 → 前 3 章正文），
   回归退化就整条回滚（保留 before 值）。

---

## 6. 密钥与配置

- `WKS_LLM_PROVIDER` / `WKS_LLM_KEY` / `WKS_LLM_MODEL`（本地脚本读环境变量；仓库只放 `.env.example`）；
- app 内：沿用已有的「AI 对话」设置入口做加密存储（D1 的要求：key 不进 APK）；
- 站点账号密码：**只存用户设备**（设置页加密区或 `legado-work/` 下不入库的文件）；
  系统日志里对密码做 `***` 遮盖。

---

## 7. 路线图

- **Step 1（今晚完成）**：探针 + 确定性校验器 + 一个四类规则全 PASS 的样板书源 + wenku8 的实测结论。
- **Step 2（下一轮）**：LLM 单字段 patch 通道（OpenAI 兼容，DeepSeek 优先）＋ 证据回喂 ＋ N 次重试；
  接端上 `bookSourceDebug` 做权威验；把"正文不含站点 UI 词"做成回归断言。
- **Step 3**：会话层升级（L1 `curl_cffi` → L2 真浏览器/CDP → L3 `cf_clearance` 导入）；
  端上 `loginUrl`/`loginUi` 登录流程 + 规则 `webView` 取源；写回走 Web API `/saveBookSources`。

---

## 8. 今晚踩到的坑（照抄别再踩）

1. `requests` 传**字符串** body 时不会自动加 `Content-Type: application/x-www-form-urlencoded`
   → 站点读到空参数（curl 会自动加，所以 curl 能过而脚本不能）；要用 dict 让 requests 编码。
2. Legado/Jsoup 的 CSS **属性选择器要引号**：`a[href^='/chapter/']`，不是 `a[href^=/chapter/]`。
3. 逐项规则语义：`chapterName`/`chapterUrl` 作用于 `chapterList` 的元素，**不是整页**。
4. 站点编码：GBK 站点的搜索关键词要按站点编码发。
5. **别以为"上了浏览器就过了"**：headless Chrome 也被 CF 拦；要么真显示，要么走端上 WebView。
6. `ls $D/*/` 这种 glob 在 `adb shell`/`run-as` 里会被外层 shell 展开而失败——用 `find <绝对路径>`。
