# 下一轮会话的启动指令（2026-10-06 更新）

把下面「贴给新 agent 的内容」整段复制给它即可。

---

## 贴给新 agent 的内容

我在做基于 `legado-with-MD3` 的个人定制版「阅读」（安卓小说阅读器）。仓库在 **WSL** 的 `~/work/legado-md3`
（Windows 侧 read/edit 读不到 WSL 路径 → 用 bash 读写；`\\wsl$\Ubuntu-22.04\...` 这种 UNC 路径 read/replace 可用）。

### 先读（都在 `project/`）
- `HANDOFF.md` —— 环境、构建/装机/截图手法、**踩过的坑清单**
- `TODO.md` —— 顶部有「状态校准」：**哪些其实早就完成、哪些真没做**（正文里的 `[ ]` 大量是过期标记）
- `DESIGN.md` —— 硬约束：暖色不纯黑、界面不出现句子、动效 ≤300ms ease-out
- `AI-SOURCE-AGENT.md` + `.agents/skills/legado-source-repair/` —— AI 书源工作系统与修源 skill

### 环境与固定手法（别重走）
- 编译 `~/build3.sh`（增量 16~60 秒）；装机：
  `cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk && adb install -r "C:/Users/mikujoker/legado-work/v.apk"`
- adb：`/mnt/c/Users/mikujoker/AppData/Local/Android/Sdk/platform-tools/adb.exe`；包名 **`io.legato.kazusa.debug`**
- ⚠️ 装完机 app 进程会死 → Web 服务要重开（设置里「Web 服务」+ 建议开「启动应用时打开Web服务」）；现成脚本 `/tmp/restart_app.sh`
- ⚠️ **别并发编译+装机**（会互相踩，出现过"BUILD SUCCESSFUL + 装机成功"但其实装的是旧包；校验要看设备 `dumpsys package ... lastUpdateTime`）
- ⚠️ `adb shell am start` 会让 pi 的 bash 工具在该行之后中断 → 设备驱动写成脚本 + `nohup bash <脚本> &`，且该 `nohup` 必须是那次调用的第一条命令
- ⚠️ **验证别截图**：一律读端上 JSON 接口（下节）；截图只在"看 UI 效果"时用
- ⚠️ 书源/本地书数据改动**零编译**（走 Web API 直推）；只有改 app 代码才编译

### 端上 JSON 接口（Web 服务开着即可，端口 1122）
| 接口 | 用途 |
|---|---|
| `POST /debugBookSource {tag,key,timeoutMs}` | 端上真引擎：一次跑完 搜索→详情→目录→正文，回结构化事件 |
| `POST /repairSource` / `POST /generateSource {site,key}` | 单字段修复 / **整源生成**（异步跑，进度写 journal；每组通过即写回） |
| `GET /getRepairJournal` | AI 修源运行状态（`ui` 段是界面、`api` 段是接口跑的） |
| `GET /getCookie?url=` | 三层 cookie：webView / jar / db（判定登录态断在哪层） |
| `POST /fillLoginFields` / `POST /fillAllLoginFields` | 补登录能力字段（loginUrl/loginUi/loginCheckJs/cookieJar） |
| `POST /renameLocalBooks {dryRun,onlyGarbled,cleanupStale}` | 本地书文件名治理（**dryRun 默认 true**） |
| `POST /saveAiProfile` / `GET /getAiProfile` | 配 / 读 AI 对话模型（key 只写不读） |
| `GET /getBookshelf` / `POST /saveBook` / `GET /getChapterList?url=` / `GET /getBookSource` / `POST /saveBookSource` | 书目、章节表、书源读写 |
| `GET /refreshToc?url=` | 刷新目录（**本地 PDF 章节表自愈就靠它**） |

### 本轮（2026-10-06）已完成
1. **AI 修源端上能力**：整源生成、单字段修复、登录能力自动写回、端点变体扫描、预算 3 轮/10 分钟/200k tokens、异步+轮询
2. **wenku8 书源 v12 端上四组全通**；搜索（GET+GBK 预编码+单命中兜底）/封面（唯一）/登录（loginCheckJs）三处真因修掉
3. **登录能力批量补齐**：启用源 614/617、全库 2662/3281（这就是"每次都让重输密码"的根源）
4. **插图灰图修复**：`ImageProvider` 解码失败不再永久缓存（4s TTL + 最多 2 次重试 + 删半截缓存文件）
5. **本地书文件名治理 C1**：141 本乱码书名搬到 `Download/legado/novel/`，路径含乱码 134 → 0，清理重复 136 条（进度保留）
6. **PDF 小活**：`番外篇/能天使` 章节表自愈（3 行 → 24 行）

### 下一轮目标（用户指定顺序）

**① 主要目标：EPUB 无损阅读（方案已定：整本改 WebView）**
- 现状：`me.ag2s.epublib` 只把每章 XHTML 的 body **抽成 HTML 文本**喂给文本阅读器
  （`app/src/main/java/io/legado/app/model/localBook/EpubFile.kt`）→ epub 自带 CSS、分栏、复杂排版全丢
- 目标：本地 EPUB 改由 **WebView 渲染 spine（逐章）**，注入主题 CSS（背景/字色/字体/字号/行距，取现有阅读样式设置），
  **保留目录与进度**；内嵌封面已有（`EpubFile.upBookCover()`）
- 资源取法（`androidx.webkit` **已在依赖里**，`libs.androidx.webkit`）：
  ① `WebViewAssetLoader` + 自定义 `PathHandler` 直接从 epub zip 取（不落地）；或
  ② `WebViewClient.shouldInterceptRequest` 返回 `WebResourceResponse`（zip 条目流）
- 接入点：**只在阅读页内容区**对 `book.isEpub` 分支，别动纯文本 / PDF（漫画阅读器）/ 正常路径；进度用滚动比例写进 `chapterPos`
- 验收：同一本 epub ①原版 CSS 生效 ②主题切换生效（背景/字色/字体/字号/行距）③目录跳转与进度续读正常 ④退出重进不丢进度

**①b OPDS 源支持（用户新增需求）**：添加 OPDS 目录源（Atom/XML 解析 + 可选 Basic Auth）→ 浏览/搜索/下载 epub/txt → 导入本地书

**② 完成后：核对需求清单 → 版本切正式 release**
- 构建：R8 + ABI 拆分（`app/build.gradle.kts` 的 release 已 `isMinifyEnabled = true` + 签名 `myConfig`；另有 `noR8` 变体排障）
- 动机：用户要的是**运行流畅度提升**（顺带确认没有 R8 引发的行为退化）
- ⚠️ **release 是 `io.legato.kazusa`**（与 debug 并存，是另一个 app）：
  ① **数据要迁移**（app 内备份 → release 恢复，或拷 DB），否则新 app 是空的
  ② **没有 `run-as`**（release 不可调试）→ 读 DB/cookie 文件改用 Web 接口（`/getCookie`、`/getBookshelf`）
- 注意：端上爬站能力（`/fetchPage` 等）在 release 里仍然可用 —— **换 release 不影响我帮用户做书源**

### 凭据（都在 `legado-work/`，不入库、不进文档）
`wenku8-secret.txt`（账号密码）、`llm.env`（DeepSeek key）

### 硬约束
- 改数据层前先看 `HANDOFF.md` 的「书 URL 迁移」那节；装完机一定自己验一遍（优先用 JSON 接口，不靠截图）
- 书源改动先过 `source_check.py`（纯 HTTP 站）或端上调试接口（CF 站）；校验器覆盖不到的字段标 SKIP，**不许当 PASS**
- 遇到"我以为"的地方先做一次最小验证再往下走
