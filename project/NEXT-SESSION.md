# 下一轮会话的启动指令

把下面整段贴给新 agent 即可。

---

## 贴给新 agent 的内容

我在做基于 `legado-with-MD3` 的个人定制版「阅读」，仓库在 WSL 的 `~/work/legado-md3`
（Windows 侧的 read/edit 读不到 WSL 路径，要用 bash 读写；`\\wsl$\Ubuntu-22.04\...` 这种路径
read/replace 是能读的，可用带 anchor 的编辑工具）。

### 先读（都在 `~/work/legado-md3/project/`）
- `HANDOFF.md` —— 环境、构建/装机/截图手法、**踩过的坑清单**（务必先看完）
- `TODO.md` —— 需求清单与当前优先级
- `DECISIONS.md` —— 已定死的决策（含数据层口径）
- `DESIGN.md` —— **硬约束**（暖色、界面不出现句子、不用文字胶囊、动效 ≤300ms ease-out）

### 环境（坑都踩过了，别重走）
- 编译：`~/build3.sh`（增量 16~45 秒；release 首次约 13 分钟）
- 装机：`cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk`
  然后 `adb install -r "C:/Users/mikujoker/legado-work/v.apk"`
  —— **adb 是 Windows 程序，看不懂 WSL 路径**（推文件同理：先 `cp` 到 `/mnt/c/...`）
- 截图：`adb exec-out screencap -p > /mnt/c/...png`，再用 read 工具看图
- 手机上看包名 **`io.legato.kazusa.debug`**（带 D）；`io.legato.kazusa` 是临时装的 release 包，书架空的
- adb 路径：`/mnt/c/Users/mikujoker/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- ⚠️ **已知会烦你**：
  ① `adb kill-server && adb start-server` 常备（daemon 会自己掉）
  ② **`input tap/swipe x y x y 120` 在 Compose 上经常丢**（尤其书架卡片；本轮连点 5 次都没进去）
     → 必须写重试循环，或换方式打开
  ③ 别从截图目测坐标，用 `uiautomator dump` 拿真实 bounds；但**点完立刻 dump 可能拿到旧界面**
     （dump 前 sleep 够，或 dump 两次）
  ④ 判崩溃只看 `grep 'Process: io.legato.kazusa'`（系统应用崩很多，别误判）
  ⑤ 构建/下载一律 `unset http_proxy https_proxy`；外网 curl 走本机代理 `http://127.0.0.1:7890`
  ⑥ 书架分组标签在 y=616（轻小说 158 / 小说 451 / 刘备 719 / 漫画 987 / 全部 1206）
  ⑦ **`/sdcard` 是符号链接，toybox `find` 默认不跟随** → `find /sdcard ...` 什么都搜不到。
     要用 `find /storage/emulated/0` 或 `find -L`。本轮因为这个错判过"文件已丢失"

### 这一轮（2026-10-06 深夜）做到哪了

**PDF 阅读器基本可用**（用户点名要换引擎 + 能缩放 + 白底正确）
- 内核 **MuPDF**：`libmupdf_java.so`（arm64/armv7）取自 F-Droid 官方 MuPDF viewer 的 APK（1.28.5）；
  Java 绑定取自主仓库同 tag 的 `platform/java/src/com/artifex/mupdf/fitz`（62 个文件，已进仓库）。
  Artifex 官方 Maven 不可达、JitPack 只有 metadata 没有构件 → 只能"取 APK 里的 .so + 同 tag 绑定"
- `PdfFile` 重写：渲染串行化 + LRU 位图缓存 + 显式 close（**删掉了 `finalize()` 关渲染器**——
  那是"载入三页一翻又变灰"的元凶）+ 一页一章 + 首页当封面
- **显示改走漫画阅读器**（`LocalMangaLoader` 增 PDF 分支；路由在 `MainNavGraph`）→
  白拿 telephoto 的双指缩放 + 缩放后按锁定尺寸平移；漫画阅读器缩放默认关闭，已对 PDF 强制打开
- PDF 强制 **连续滚动**（`MangaScrollMode.WEBTOON_WITH_GAP`）+ 不为 PDF 产出"换章"整屏 +
  背景由纯黑改成米色底 + 一点粉/蓝/紫的极淡渐变
- 真机已验证：打开、白底黑字正确、连续多页无渲染失败、双击缩放生效、连续滚动无黑层、崩溃 0
- **遗留**：6 本 PDF 的章名仍是旧的 `分段_N`（DB 里旧的 10 页一段的行）；要换新结构得让章节表重建
  （删掉该书的 chapters 行 → 漫画阅读器下次打开会按新结构重建）

**四个过期路径已修**（DB 迁移 + 装机验证：`integrity_check ok`、`foreign_key_check 0`、崩溃 0）
- 麻雀教室 PDF → `Download/Browser/Seventh9先生の麻雀教室改二.pdf`
- 203-228.txt → `Download/legado/novel/203-228.txt`（从 QQ 私有目录拷出来）
- 败犬女主 8.5卷.epub → `Download/`
- 问道第一章 3.2W.txt → `Download/QQBrowser/.../clouddisk/`

**目录规则（D5）已交付**：230 本本地 txt 覆盖 69.1% → 76.5%、内置规则 28 → 13 条、
新增 `TxtTocRule.isFallback`（DB 110）、新增「对所有本地 TXT 重新分章」入口
（详见 HANDOFF「2026-10-06 第二轮」）

### 下一步（用户已排好）
1. **EPUB 无损渲染**（用户原话：equb 要保证效果无损）——
   计划：解析继续用 `me.ag2s.epublib`，渲染改成 **WebView 加载 spine + 注入主题 CSS**（图片/CSS/表格都在）；
   **EPUB 有内嵌封面就用它**，别套内置默认书封
2. PDF 收尾：6 本 PDF 的章名 `分段_N` → 重建章节表；封面（首页当封面已实现，重开生效）
3. 之后按原计划：**OPDS**，再 **AI 爬站修源（D2）**——key 要加密存设置页、不能硬编码

### 硬约束
- `project/DESIGN.md`
- 改数据层前先看 HANDOFF 的「书 URL 迁移」那节（**外键 `ON UPDATE NO ACTION` + 列名不叫 bookUrl**
  两个坑，都踩过）
- 装完机一定自己截图看效果再说话

### 设备与素材
- Android 16（API 36）、arm64-v8a、1264×2780
- 本地书：230 本 txt（语料已拉到 `~/work/corpus/` + `manifest.tsv`）、43 epub、6 pdf
- 可复用脚本：`project/scripts/toc_regression.py`（目录规则离线回归）、`project/artifacts/`（规则 JSON 等）；
  `/tmp/` 里的测量/验证脚本重启会丢，`~/build1x.sh` 历史构建脚本还在
- DB 备份：`/mnt/c/Users/mikujoker/legado-work/predb*/`（含 path 迁移前的原始库）
