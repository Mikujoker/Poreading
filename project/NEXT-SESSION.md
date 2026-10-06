# 下一轮会话的启动指令（2026-10-07 更新）

把下面整段贴给新 agent 即可。

---

## 贴给新 agent 的内容

我在做基于 `legado-with-MD3` 的个人定制版「阅读」，仓库在 WSL 的 `~/work/legado-md3`
（Windows 侧的 read/edit 读不到 WSL 路径，要用 bash 读写；`\\wsl$\Ubuntu-22.04\...` 这种路径 read/replace 可用）。

### 先读（都在 `~/work/legado-md3/project/`）
- `HANDOFF.md` —— 环境、构建/装机/截图手法、**踩过的坑清单**（务必看完）
- `TODO.md` / `DECISIONS.md` / `DESIGN.md`（**硬约束**：暖色不纯黑、界面不出现句子、动效 ≤300ms ease-out）
- `AI-SOURCE-AGENT.md` —— **AI 书源工作系统设计**（五层架构、Cloudflare L-1~L6、LLM 单字段 patch 契约、坑清单）
- `project/book-sources/` —— `biquge365.net.json`（样板，四类规则全 PASS）、`wenku8.net.webview.json`（草案 **v10**，端上四类规则全过：搜索 9/20 本、详情全字段、目录 213 章、正文非空）

### 环境与固定手法（别重走）
- 编译 `~/build3.sh`（增量 16~45 秒）；装机
  `cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk && adb install -r "C:/Users/mikujoker/legado-work/v.apk"`
- ⚠️ **别反复 build**：书源/规则类改动一律走 `POST http://127.0.0.1:1122/saveBookSource` 直推（**零编译、零装机**）；
  只有改 app 代码才编译，且**一次改完再编**：先 `:app:compileAppDebugKotlin` 过一遍语法（~50s），通过了再 assembleAppDebug 装机。
- adb：`/mnt/c/Users/mikujoker/AppData/Local/Android/Sdk/platform-tools/adb.exe`；包名 **`io.legato.kazusa.debug`**
- 截图 `adb exec-out screencap -p > /mnt/c/...png` 再用 read 看图；`adb pull` 只能用 **Windows 路径**（`C:/...`）
- ⚠️ `adb shell am start` 会让 pi 的 bash 工具在那一行之后中断 → 设备驱动写成脚本 +
  `nohup bash <脚本> &`，且**该 nohup 必须是那次调用的第一条命令**
- ⚠️ **驱动 UI 一律先用 `uiautomator dump` 取真实 bounds，禁止猜坐标**（本轮猜错两次，其中一次直接点出页面）
- ⚠️ `/sdcard` 是符号链接，toybox `find` 默认不跟随 → 用 `find /storage/emulated/0` 或 `find -L`
- 判崩溃只看 `grep -c 'Process: io.legato.kazusa'`

### 书源侧的两个"密钥"通道（本轮摸出来的，极有用）
1. **直推书源进库（绕开导入判重）**
   ```bash
   adb forward tcp:1122 tcp:1122        # 1122 是这个 fork 的 web 服务端口（api.md 里的 1234 是上游示例，1235 的 WS 实测 404/被断）
   POST http://127.0.0.1:1122/saveBookSource   # body = 书源 JSON；同 url 会覆盖
   GET  http://127.0.0.1:1122/getBookSource?url=<urlencode(bookSourceUrl)>   # 回读确认
   ```
2. **直达调试页**
   ```bash
   am start -n io.legato.kazusa.debug/io.legado.app.ui.main.MainActivity \
     --es startRoute source/book/debug --es sourceUrl "https://www.wenku8.net/index.php"
   ```
   （`MainRouteConst.ROUTE_BOOK_SOURCE_DEBUG = "source/book/debug"`）
   注意：**调试页不会热加载书源**——改了源要重新打开调试页才生效（本轮在此浪费过一轮）。

### 本轮（wenku8 轻小说文库）已确认的事实
- **Cloudflare 拦的是出口 IP**：走梯子（日本机房 `38.207.136.135`）→ 403 硬拦；直连（家庭宽带 `1.194.68.229`）+ 真 Chrome → 200 真站。**L-1：先用 `cloudflare.com/cdn-cgi/trace` 自检出口 IP。**
- **`cf_clearance` 复用无效**：浏览器导出的 11 条 cookie + 完全一致 UA + Client Hints 交给纯 HTTP，仍 403 → CF 认的是浏览器指纹。
  ⇒ **wenku8 的书源必须走 WebView 取页**（纯 OkHttp 不可能工作）；本地 `source_check.py` 也验不了这类站，只能端上验。
- **app 里的登录其实成功了**：`app_webview/Default/Cookies` 里有 `www.wenku8.net: PHPSESSID(32)`、`jieqiUserInfo(367)`；
  但 Legado 自己的 `cookies` 表里 `wenku8.net` 是空串 → jar 空，这就是"反复要登录"的机制。
- **v5 的搜索规则已验证通过**：调试页读数为 `◇ 书籍总数:1` / `≈搜索页解析完成`（webView 取页 8 秒，含 3 秒延迟）。
- URL 形态（含移动/桌面 UA 布局差异这个大坑）：
  详情 `/book/<bid>.htm`；目录 `/novel/<a>/<bid>/index.htm`（详情页里"小说目录"链接）；
  章节 `/novel/<a>/<bid>/<cid>.htm`；封面 `img.wenku8.com/image/<a>/<bid>/<bid>s.jpg`；
  搜索表单 `POST /so.php`（`searchtype`/`searchkey`/`charset=gbk`）。
  **桌面 UA 下目录链接是相对路径 `NNNN.htm`，容器 `td.ccss a`**；结果页在桌面 UA 下是 `div[style*=float]` 网格，
  所以 v5 的 `bookList` 改成不依赖布局：`a[href^='/book/'][href$='.htm']:not(:has(img))`。

### 本轮踩过的 Legado 语义坑（照抄别再踩）
1. **`loginCheckJs`：`result` 绑定的是响应对象**（`WebBook.kt:73` 用 `evalJS(checkJs, res)`），
   而且 **`body` 是方法不是属性** → 正确写法是 `result.body().contains('退出登录')`（或干脆别加这个字段）。
   我连错两次（`result.contains` / `result.body.contains`），每次都让**整次搜索抛 ScriptException 直接挂掉**。
2. **`webView` 是"每个 URL 的选项"**，没有源级开关。`searchUrl` 可以直接写在选项里；
   `bookUrl`/`tocUrl`/`chapterUrl` 这种由规则产出的 URL 要拼上去，得用
   `href@js:result + ',{"webView":true,"webViewDelayTime":3000}'`（延迟是给挑战解完留时间）。
3. **导入按 `bookSourceUrl` 判重且不提供覆盖** → 用上面的 API 直推，或把 `bookSourceUrl` 改一个等价变体
   （规则若全是根相对路径，基址带 `/index.php` 不影响解析）。
4. **定选择器必须用目标端 UA 的 HTML**：app 的 WebView 是移动 UA，桌面 UA 抓的 DOM 可能对不上。

### 待办（下一轮从这里开始）
1. ✅ **端上验目录/正文已通过**（走 HTTP 调试接口，不再截图）：搜索 **9 本** / 详情全字段（名·作者·分类·简介·封面·目录链）/ 目录 **18 章** / 正文非空。
   命令见下方「工具」。
2. ✅ **`bookList` 不是"过窄"**：`书籍总数:1` 的真因是 —— POST 搜索 `AnalyzeUrl` 会**先用 OkHttp 发一次**，
   本机指纹必被 CF 发挑战页（`请稍候…`），挑战页 HTML 被当详情页解析 → `tocUrl` 变成 `http://localhost/` → `TocEmptyException`。
   已改 **GET**（走 WebView，CF 不拦）。
3. ✅ **关键词 GBK 预编码**：站点无视 `charset` 参数总按 GBK 解，而 `webView` 取页时 WebView 强制把 URL 里的中文按 UTF-8 编，
   `charset` 选项只对 OkHttp 生效 → 搜索页回 `鏂囧∨皯濂`（空结果）。改用 `{{...}}` 内嵌 JS 把关键词编成 GBK 百分号转义。
4. ✅ **`loginCheckJs` 已补 + 登录页校验落地**（app 侧改动，见 AI-SOURCE-AGENT 的验证接口一节）：
   `result.body().indexOf('退出登录') >= 0 ? result : false`。
   ⚠️ 同一个 JS 要同时跑在 Rhino（书源规则）与 WebView 页面里，只能用两边都有的 **`indexOf`**
   （`.contains` 只有 Java String 有，`.includes` 只有 JS String 有）。
5. ✅ **登录页校验已真机验证**：冷启动带 `startRoute=source/login` 打开登录页 → 自动判定命中 → 弹「已登录」→ 登录页自动退出。
   （实现是 `SourceLoginViewModel.verifyWebLogin()`：用 `AnalyzeUrl` 真跑一次 `loginCheckJs`，与阅读同一路径；页面内 JS 探针那条路已废弃删除）
6. ⏳ 下一件：**EPUB 无损渲染**（WebView 加载 spine + 注入主题 CSS，有内嵌封面就用内嵌的）。
7. ⏳ 顺带小活：`番外篇/能天使` 打开一次让 PDF 章节表自愈（`&` 文件名的坑见 HANDOFF）。

### 工具（都已跑通，可直接复用）
- `project/tools/probe_site.py` —— 结构探针（抓四类页面打印候选容器）
- `project/tools/source_check.py` —— 确定性校验器（CSS 子集 + 逐项规则语义 + POST 选项 + cookie/UA 注入；覆盖不到的标 SKIP）
- `project/tools/cdp_fetch.py` —— L2 会话层：真 Chrome + CDP 取渲染后 HTML / 导 cookie / 取 UA
- `project/tools/cdp_login.py` —— CDP 登录器（账号读本地不入库文件，不进命令行）
- `project/tools/book_debug.py` —— 端上调试**HTTP** 客户端：`POST /debugBookSource`（一次跑完搜索→详情→目录→正文）＋ `GET /getCookie`（看三层 cookie）。
  用法 `python3 book_debug.py --tag <源URL> --key <关键词|URL|++目录URL|--章节URL> [--json] [--cookie <url>]`；
  ⚠️ WS 版 `/bookSourceDebug` 在 **webPort+1 = 1123**（连 1122/1235 都是 404）
- `project/tools/backfill_covers.py` —— **批量补封面**：给书架里 `coverUrl` 为空的书，用它自己的源重跑一次详情页，把站点封面写回。
  `python3 backfill_covers.py [--dry-run] [--limit N] [--name 子串] [--source 源URL]`；
  原则：**只补空的、只在站点确实给出封面时才写**（解析不到就保持默认封面，绝不写空值/覆盖已有封面）。
  2026-10-07 实跑 19 本空封面书：补到 0（12 本源已消失：`origin` 指向已下线的 `44yydstxt234` 书库域名；7 本源在但站点解析不到，其中 `jishuge.vip` 那条源根本没 `coverUrl` 规则）
- `project/tools/ai_repair_source.py` —— **AI 修源循环**：出口自检 → 取页分级 → 机械探针 → LLM **单字段** patch →
  端上真引擎断言 → 回滚/写回；预算 3 轮 / 10 分钟 / 200k tokens。
  ⚠️ `--check-only` 必须先校准判据（拿已知好值/坏值各跑一次），坏判据会让 AI 围着假失败空转。
- 新增 app 侧接口（都在 1122 端口）：`POST /repairSource`（单字段/端点修复循环，回完整 JSON 报告）、
  `POST /generateSource`（**整源生成**：搜索→详情→目录→正文，每组通过即写回）、
  `POST /fillLoginFields`（只补登录能力字段：loginUrl/loginUi/loginCheckJs/cookieJar）、
  `GET /getRepairJournal`（读 app 内「AI 修复」运行状态）、
  `POST /saveAiProfile` / `GET /getAiProfile`（配 / 读 AI 对话模型，**key 只写不读**）
- `.agents/skills/legado-source-repair/` —— **书源修复 skill**（流程、harness 接口、判据表、坑表 +
  `references/wenku8-v11-retro.md` 实战复盘）。做书源诊断/修复前先读它；已登记进 `AGENTS.md` 技能路由。
- app 侧新增两个原语（AI 修源用）：`GET /fetchPage`（手机 WebView 抓页，能过 CF）、
  `GET /verifyLogin`（拉起内置浏览器人工过验证/登录，完成后 cookie 落库）
- `legado-work/drive_dbg.sh`、`drive_toc.sh` —— adb 驱动调试页的脚本（坐标待用 uiautomator dump 校正）

### 凭据（都在 `legado-work/`，不入库、不进文档）
- `wenku8-secret.txt`（账号密码）、`llm.env`（DeepSeek key，供 LLM 单字段 patch 通道用）

### 硬约束
- 改数据层前先看 HANDOFF 的「书 URL 迁移」那节；装完机一定自己截图看效果再说话
- 书源改动先过 `source_check.py`（纯 HTTP 站）或端上调试页（CF 硬站）；校验器覆盖不到的字段标 SKIP，**不许当 PASS**
- 遇到"我以为"的地方先做一次最小验证再往下走——本轮两次翻车都源于我在 JS/选择器语义上想当然
