# 下一轮会话的启动指令

把下面整段贴给新 agent 即可。

---

## 贴给新 agent 的内容

我在做基于 `legado-with-MD3` 的个人定制版「阅读」，仓库在 WSL 的 `~/work/legado-md3`
（Windows 侧的 read/edit 读不到 WSL 路径，要用 bash 读写；`\\wsl$\Ubuntu-22.04\...` 这种路径
read/replace 是能读的，可用带 anchor 的编辑工具）。

### 先读（都在 `~/work/legado-md3/project/`）
- `HANDOFF.md` —— 环境、构建/装机/截图手法、**踩过的坑清单**（务必先看完；第三轮新增了 adb 驱动的坑）
- `TODO.md` —— 需求清单与当前优先级
- `DECISIONS.md` —— 已定死的决策（含数据层口径）
- `DESIGN.md` —— **硬约束**（暖色、界面不出现句子、不用文字胶囊、动效 ≤300ms ease-out）
- `AI-SOURCE-AGENT.md` —— **AI 书源工作系统的设计**（含今晚的实测结论与「照抄别再踩」清单）

### 环境（坑都踩过了，别重走）
- 编译：`~/build3.sh`（增量 16~45 秒）
- 装机：`cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk`
  然后 `adb install -r "C:/Users/mikujoker/legado-work/v.apk"`
- 截图：`adb exec-out screencap -p > /mnt/c/...png`，再用 read 工具看图
- 手机上看包名 **`io.legato.kazusa.debug`**
- adb 路径：`/mnt/c/Users/mikujoker/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- ⚠️ **新增**：`adb shell am start` 会让 pi 的 bash 工具在那一行之后中断 → 设备驱动要写成脚本 +
  `nohup bash <脚本> &`，且**该 nohup 必须是那次 call 的第一条命令**（详见 HANDOFF 第三轮）
- ⚠️ `ReadMangaActivity` 是 `exported=false`，shell 起不来；用
  `am start -n <pkg>/...MainActivity --es startRoute book/read/manga [--es bookUrl <路径>]`

### 这一轮（2026-10-06 深夜）做到哪了

**PDF 阅读器：整本一章 + 一路滑到底不停顿**（提交 `7ee2dfb`，装机验证）
- 一页一章 + 会话窗口 ±1 章 → 列表里永远只有 3 页内容，滑到页底必撞墙 → 改成**整本 = 一章**
- 页图改**惰性地址** `pdf-page-image://<书md5>/<页号>`，由 Coil `PdfPageMapper` 在真正取图
  （含预取）时按需光栅化；`PdfPageFiles` 顺带预渲染后面 8 页
- PDF 不插换章卡片、关淡入、强制**无间隔条漫** `WEBTOON`
- 条漫当前页改判「视口阅读线所在页」（旧规则要求当前章整章滚出视口，一页一章时永不成立）
- 进度口径：会话章号恒 0，但 `durChapterIndex` 仍存**页号**（书架百分比才算得对）
- 自愈：PDF 章节表行数 ≠ 页数就按页重建（旧 `分段_N` 自动换掉）
- 真机证据：`第94页 · 页码 94/163 · 章节 1/1 · 进度 57.7%`，一次快滑跨多页、崩溃 0

**AI 书源工作系统**（Step 1 完成，未提交前见 `AI-SOURCE-AGENT.md`）
- 已跑通：`project/tools/probe_site.py`（结构探针）、`project/tools/source_check.py`（**确定性校验器**）、
  `project/book-sources/biquge365.net.json`（样板书源，**四类规则全 PASS**）
- 复现：`cd project/tools && python3 source_check.py ../book-sources/biquge365.net.json --keyword 大梦主`
- **wenku8 实测**：Cloudflare WAF 硬拦（curl/headless 全 403 "Sorry, you have been blocked"），
  纯 HTTP 过不去 → 必须真浏览器/端上 WebView 拿会话。凭据在
  `/mnt/c/Users/mikujoker/legado-work/wenku8-secret.txt`（**不入库**）

### 下一步（用户已排好）
1. **AI 书源工作系统 Step 2**：LLM 单字段 patch 通道（DeepSeek 优先，OpenAI 兼容）+ 证据回喂 +
   N 次重试；接端上 `ws://127.0.0.1:1235/bookSourceDebug` 做权威验证；把"正文不含站点 UI 词"
   做成回归断言。用户会提供 DS/GPT 的 API key（**加密存设置页，不许硬编码**）。
2. 会话层升级：`curl_cffi` 指模 → 真浏览器/CDP → `cf_clearance` 导入；端上 `loginUrl`/`loginUi` +
   规则 `webView` 取源，然后才产 wenku8 的书源（**未验证的规则不许进库**）。
3. **EPUB 无损渲染**：解析继续用 `me.ag2s.epublib`，渲染改成 WebView 加载 spine + 注入主题 CSS；
   EPUB 有内嵌封面就用内嵌的，别套内置默认书封。
4. 之后按原计划：PDF 收尾（番外篇/能天使打开一次让章节表自愈）、OPDS。

### 硬约束
- `project/DESIGN.md`；改数据层前先看 HANDOFF 的「书 URL 迁移」那节
- **装完机一定自己截图看效果再说话**；书源改动一定先过 `source_check.py`（或端上 debug 通道），
  校验器覆盖不到的字段标 SKIP，**不许当 PASS**
- 设备交互要点：浮层读数（页码/章节/进度）、`find <绝对路径> -name '*.jpg' | wc -l`、
  崩溃只看 `grep -c 'Process: <pkg>'`

### 设备与素材
- Android 16（API 36）、arm64-v8a、1264×2780
- 本地书：230 本 txt、43 epub、6 pdf；语料在 `~/work/corpus/` + `manifest.tsv`
- 可复用脚本：`project/scripts/toc_regression.py`、`project/tools/{probe_site,source_check}.py`、
  `project/artifacts/`、`legado-work/drive*.sh`
- DB 备份：`/mnt/c/Users/mikujoker/legado-work/predb*/`
