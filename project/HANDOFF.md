# 交接：下个会话从这里开始

## 现状一句话

A1「书源管理」按用户最终口径重写完成并**装机验证**：三个标签（常用/失效/全部）+ 行上星标 +
「失效」按需刷新（只校验常用）+ AI 修复入口；五级健康度、统计条、多选与全部批量操作已删除。

## 用户口径（别再猜错）

> 「启用是启用，常用是常用，常用和收藏差不多，占一个栏位」
> 「我没有选中成为常用的就不会测试失效」

- **常用 = 独立的一列 `book_sources.isFavorite`**（≈收藏），与 `enabled` 无关
- **失效 = 常用里被校验判失败的那批** → 失效永远是常用的子集；关掉星标立刻退出失效
- **全部 = 全部**
- **刷新只扫常用**（没打星标的源永远不校验）

## 本轮已装机验证的成果

| 项 | 证据 |
|---|---|
| 三标签 + 顶栏下拉折叠 | 截图：常用 / 失效 / 全部 |
| 星标写入 + 跨进程持久 | 打星后重启 app，「常用」里仍有那一条 |
| 刷新只扫常用 | 常用 1 条 → 点刷新 → 只校验 1 条，snackbar「校验完成：成功 0，失败 1」 |
| 失败结果留内存 | 失效列表出现该条，附原因「发现失效, 搜索失效」 |
| 取消星标即退出失效 | 点掉星标 → 失效列表立刻空 |
| 空态三态 | 常用无源→「无常用」；失效未扫→「未扫描」+刷新；扫完无坏源→「无失效」+刷新 |
| 无常用时点刷新 | snackbar「没有常用书源」 |
| 校验设置入口 | ⋮ 菜单第一项 → 超时 + 搜索/发现/详情/目录/正文 |
| 本包崩溃 | `grep 'Process: io.legato.kazusa'` 计数 0 |

## 数据库变更（重要，别再踩）

- `book_sources` 新增 `isFavorite INTEGER NOT NULL DEFAULT 0`；DB 版本 **107 → 108**
- 迁移 `migration_107_108`（`DatabaseMigrations.kt`）：`ALTER TABLE` + **重建 `book_sources_part` 视图**
- ⚠️ **视图 SQL 必须与 `@DatabaseView` 注解逐字一致**（含行尾空格与 4 空格缩进）：
  Room 在迁移后会拿 `sqlite_master` 里的原文跟注解比对，差一个空格就抛
  「Migration didn't properly handle」。比对方法：读 `app/schemas/.../108.json` 里
  `views[].createSql`（把 `${VIEW_NAME}` 换成视图名），与迁移里拼出的字符串做 `==`
- 迁移前后数据核对（已做）：书源 24528、启用 6429、书籍 385、user_version 107→108

## 性能真相（更新）

- 旧实现每次进页面要跑两次全表：`book_sources_part` 视图 5.2s + 规则快照查询 4.1s
- 本轮删掉健康度索引 → **省掉那 4.1s**；列表本身那次 `flowAll()` 还在（体量决定）
- 三处针对「刷新要跑几千条」的防护：
  1. 校验状态 500ms 采样后才喂 UI（否则每条结果都重算整张列表）
  2. **校验进行中暂停订阅全表快照**（否则 Room 每写一条就让 119 MB 查询重跑一遍）
  3. `setEnabled` 复用共享快照，不再单独开一次全表查询
- 想让页面**真正秒开**只有两条路：分页（筛选/排序下推到 SQL），或派生约 100 KB 小表（需迁移）

## 环境与验证手法（这轮的坑）

- 编译 `~/build3.sh`（增量 27~70s）；装机 `adb install -r "C:/Users/mikujoker/legado-work/v.apk"`
- **`adb shell input tap` 在 Compose 上会偶发丢点击** → 复测用 `input swipe x y x y 120`
- **别从截图目测坐标**：目测会系统性偏差 50~80px。用
  `adb shell uiautomator dump /sdcard/ui.xml` 拿真实 bounds 再点
- adb daemon 会自己掉（报 `cannot connect to daemon`）→ `adb kill-server && adb start-server`
- 手机休眠让 input 失效 → 先 `input keyevent KEYCODE_WAKEUP`
- 判崩溃只看自己包名：`grep 'Process: io.legato.kazusa'`
- 设备无 `sqlite3` → 查库要么 `adb exec-out run-as io.legato.kazusa.debug cat databases/legado.db > ...`
  拉下来用 python3 sqlite3 查，要么走 app 内 Web API
- 构建/下载一律 `unset http_proxy https_proxy`

## 备份位置

- 迁移前：`/mnt/c/Users/mikujoker/legado-work/predb/`（legado.db + wal + shm，user_version 107）
- 迁移后：`/mnt/c/Users/mikujoker/legado-work/postdb/`（user_version 108）

## 已拍板（2026-10-05）

- **默认标签 = 常用**（用户确认保持现状）
- 校验会写库（`respondTime` + 失败源加「网站失效/搜索失效」等分组 + 错误注释），这是 app 原有
   「校验书源」的行为，本轮**没有**改成只读。要纯只读得给 `BookSourceCheckRepository` 加 `persist` 开关。

## 下一步（用户已定优先级，2026-10-05 晚）

**P0 只有两件：**
1. **B1/B6 阅读界面背景与字体**（用户：「ui 背景 字体」）—— 配色 token + 外挂字体
   （DESIGN.md 已定：明色米纸 / 深色暖褐或深蓝，不用纯黑；字体候选见 TODO B3）
2. **D2 AI 爬网站修订书源** —— 流程与铁律见 TODO：只改失败的那一段、回归验证通过才写回；
   地基是上游自带的 `ws://127.0.0.1:1235/bookSourceDebug` 调试通道；
   注意 D1 的要求：API Key 存设置页加密，不硬编码进 APK（这个 fork 自带「AI 对话」入口可复用）

其余全排在后面。

## 上一轮之后的遗留（不优先）

- 「全部」标签的分页只覆盖默认的手动排序；名称/更新时间/响应时间排序与「按域名分组显示」
  仍旧整表读（和分页前一样）。要让它们也快，得再给这些排序列各建索引（又一次迁移）
- 去重后的 12680 条入库方式未定
- 分页的翻页哨兵按「已加载条数」做 key，停在底部会连续续页；滚开被回收即停

## 更早的候选

- **批量标「常用」**（用户新提）：单条点星标几十上百条太累，想要批量收藏/移除。
  「按分类查看」已经能用（顶栏「分组」图标）。待定批量口子做多宽
- A2 应用书源体检方案（等用户拍板）
- 去重后的 12680 条入库（99 MB JSON 在 `/mnt/c/Users/mikujoker/legado-work/bookSource.deduped.json`，
  入库方式未定：导入 or Web API `/saveBookSources`）
- 列表分页（唯一能让这页真正秒开的路）
- B1/B3 阅读界面配色与字体

---

# 阅读页（B1/B3）现状与坑

## 字体

- 外挂字体目录 `/sdcard/legado/fonts`（B3 的约定）。`help/FontLoader.kt` 现在优先用它，
  **不需要先在设置里授权 SAF 目录**；授权过的目录仍然优先
- ⚠️ **修过的 bug**：`feature/reader/platform/AndroidReaderTextShaper.loadTypeface` 以前只认
  `content://` 和裸路径，而字体选择器存的是 **`file://` URI** → `File("file:///…").isFile` 恒为 false
  → 静默回退系统字体（用户「选了没反应」就是这个）。现已处理 `file://`
- 已放好的字体：`/sdcard/legado/fonts/LXGWWenKaiScreen.ttf`（霞鹜文楷 Screen，v1.522，25.6 MB）
- 正文字体写进了「米纸」系预设的 `textFont`，换预设不会掉

## 排版配置（改之前先看这段）

- 生效的那份：`ReadBookConfig.config = if (shareLayout) shareConfig else durConfig`
  —— **共用布局开着时用的是 `files/shareReadConfig.json`**，改预设文件没用
- 预设列表：优先读 `files/readConfig.json`，没有才用 `assets/defaultData/readConfig.json`
- ⚠️ **app 会写回这个文件**：任何样式改动都会把**内存里的列表**落盘 → 我从外面 push 进去的改动
  会被下一次样式改动覆盖。**别再走 push 这条路**
- 要新增内置预设 / 改配色：**改 `assets/defaultData/readConfig.json` + 重新编译**；
  要让设备生效，先备份再删掉 `files/readConfig.json`（app 就回落到 assets 列表），
  或让用户在预设列表里点一下
- 背景语义：`bgType` 0=纯色 / 1=`assets/bg/<bgStr>` / 2=`externalFiles/bg/<bgStr>` 或绝对路径

## 我们的「米纸」系预设（index 6-9）

| # | 名字 | 白天背景 | 夜间背景 |
|---|---|---|---|
| 6 | 米纸·纸页 | `kazusa-paper-page.jpg` | 暖褐 |
| 7 | 米纸·纤维 | `kazusa-paper-fiber.jpg` | 暖褐 |
| 8 | 米纸·棉絮 | `kazusa-paper-cotton.jpg` | 暖褐 |
| 9 | 米纸·深蓝 | `kazusa-paper-fiber.jpg` | `kazusa-night-blue.jpg` |

- 纸纹由 `scripts/gen-paper-texture.py` 生成（改参数重跑即可，输出直接写进 `assets/bg/`）
- 配方 = 受光渐变 + 帘纹 + 纤维 + 棉絮云纹 + 颗粒，**1264x2780**（用户手机真实分辨率，
  颗粒必须 1:1，放大就糊成一片灰）
- ❌ 别再照 prototype 的 CSS 径向色斑做：那是给浏览器大屏的，到了手机上就是两坨黄渍（用户原话「很丑」）
- 排版：行距 12 / 段距 2 / 字距 0（用户要求「紧回去」）；字色 `#2B241C`；夜间字色 `#EFE6D9`；
  夜间背景暖褐 `#16120E` / 深蓝 `#0B1420`

## 书 URL 迁移（改名连源文件 / 换源都踩这个）

`books.bookUrl` 是主键，`chapters.bookUrl` 与 **`exact_chapter_page_counts.bookId`**
都有外键指向它，且都是 `ON UPDATE NO ACTION`。所以：

- **改 bookUrl 必然违反外键约束**（实测 `SQLiteConstraintException: FOREIGN KEY constraint failed`）
  → 迁移必须在 `PRAGMA foreign_keys = OFF` 下做，搬完用 `PRAGMA foreign_key_check` 自证没有悬空引用
  （注意 pragma 在事务里改是无效的，必须在 `beginTransaction` 之前关）
- **别只用「列名是 bookUrl」来扫表**：`exact_chapter_page_counts` 的列叫 `bookId`。
  正确做法是同时按外键元数据找引用表：`pragma foreign_key_list(<表>)` 里
  `table='books' and to='bookUrl'`，然后更新其 `from` 列 —— 列名不叫 bookUrl 也不会漏
- 还有按「书名 + 作者」聚合的表（`bookmarks`、`readRecordSession`、`readRecordDetail`），
  改名后要同步改，否则书签与阅读时长会脱钩
- 实现见 `help/book/LocalBookRename.kt`：文件改名 → 库搬迁 → 失败把文件改回（不留「文件在 A、库指向 B」）；
  rename 在共享存储（FUSE）上偶发失败，已退化为「拷贝 + 删原文件」

### 改名迁移的第三个坑（已被真机验证抓到）

迁移把 `books.originName` 改成了新文件名，但**紧接着 `bookRepository.update(book)` 会用内存里那份旧对象
把整行写回**，于是旧文件名（乱码）又被盖回去 —— 表现就是「文件与 URL 都改了，书籍详情里还是乱码」。

规则：凡是「先用 SQL 改了某行、再用 `@Update` 写同一个实体」，都要把内存对象里被 SQL 改过的字段同步过来。
本次在 ViewModel 里补 `book.originName = File(newUrl).name`。

真机验证结果（`我的姐姐是大明星 无绿`）：磁盘文件改名成功、`bookUrl` 迁移成功、
**814 个章节跟着新 URL 走**、书签/阅读会话无残留。

---

# 2026-10-06 收尾状态

**交接与启动指令见 `NEXT-SESSION.md`**（可直接贴给新 agent）。

## B2 动效：动画部分已做完

- `ui/theme/MotionSpec.kt`：统一 token（160/240/280，进入 ease-out，跟手 spring，入场不从 0，
  错峰 40ms，跟随系统减弱动效）
- 5 批替换已装机：书架主转场 480→240 · 详情页 400→240 · 文本选择 400/360→240/160 ·
  音频/朗读 520→280 · 图表 320→240 · 播放暂停 300→160 · 换源 480/360→240/160 ·
  排版面板/系统菜单改 ease-out · 换源与模态去掉 ease-in
- **共享元素本来就有**（MainActivity 里 `SharedTransitionLayout`，`sharedCoverKey` 一路传到封面），
  不需要新建
- 故意保留：点阵微光 2600ms 线性（循环动效）、主题换肤 700ms（全局）

## 书架切分组的卡顿：是渲染问题，不是动画问题

实测（标签真实坐标 y=616）：切 漫画↔小说 三轮，412 帧 / 卡顿 2.91% / 95 分位 23ms /
99 分位 77ms / **0 次丢 Vsync / Slow UI thread 12 次**。

系统点名 Slow UI thread → 主线程在切换时干重活：组数据 + 整屏条目合成 + 封面解码上屏。
**再改动画时长不会有效果。** 下一步该做：限邻页预组合、封面先占位后解码、预热邻组封面。

## 启动：不用再查

debug 冷启动 3.0s / **release 413ms**；release 各阶段比 debug 快 3~10 倍
（Application 22ms vs 218ms、Content 首帧 71ms vs 561ms）。慢的是 debug 包本身
（无 R8、无 baseline profile、dex 校验、纯 JIT）。临时埋点已删。
release 参考包可用 `/mnt/c/Users/mikujoker/legado-work/rel.apk`（debug 证书签名，仅供计时）。

---

# 2026-10-06 第二轮（书架性能 · 目录规则 · 标题字号）

## 书架切分组：P1/P4 做了，但卡顿没解决 —— 别重复劳动

- P1：`beyondViewportPageCount` 0→1（邻页预组合）
- P4：远处切分组（|Δ|>1）改「直接落位 + 240ms ease-out 淡入」（`jumpToGroupPage`），不再滑过
  中间页；相邻切换仍走滑动
- 启动浮现：一个时钟 + 每条按 index 取相位，在 `graphicsLayer` 里读（不触发重组）。
  ⚠️ **时钟必须由「LazyGrid 真的布局出内容」触发**（`layoutInfo.totalItemsCount > 0`）：
  按「数据到了」触发会被 debug 冷启动 3 秒多坑掉，动画整段跑在空屏上，书一出现就是全亮
- 实测（debug，同协议各两遍）：最坏帧 **p99 150~300ms → 44~69ms**，但**卡顿帧绝对条数没降**
  （15~23 → 17~21）—— 只是把开销挪了位置

### 测量坑（都踩过）

1. **别看「卡顿率%」**：新包同动作渲染帧数变多（83 → 237），分母一大百分比就好看，
   绝对条数才作数
2. profile 结论：一帧 30ms 的 `AndroidOwner:measureAndLayout` 里，19 条书自己只占一部分
   （全 trace：`bs:itemComp` 301ms vs `Compose:recompose` 728ms，measure 仅 6.3ms）→ 真凶在
   **条目之外**（pager 的 SubcomposeLayout / LazyGrid / 共享元素 lookahead）与**绘制阶段**
   （最慢帧 draw 84ms，改动前同类帧约 1ms）
3. 本机**没有可用的方法级 profiler**：无 root、simpleperf 被内核拒（`cpu-cycles`/`cpu-clock`
   都不支持）、`am profile --streaming` 拿不到包内 marker → 只能临时插 `Trace.beginSection`
4. Google 官方：debug 上的 Lazy layout 性能数不可信，最终验收要 release

## 目录规则（D5）已交付：覆盖 69.1% → 76.5%

- 「重新分章」入口：**目录页 → ⋮ → 更新目录**（每本一次）；**批量**入口在
  **目录规则页 → ⋮ → 对所有本地 TXT 重新分章**
- 规则表只在**空表**时才从 assets 播种 → 换了内置规则后设备侧要**清空一次规则表**
- 新增 `TxtTocRule.isFallback`（DB 109→110）：命中数天然占优的规则（分隔线）只在没有普通
  规则「能用」时才参与竞争。⚠️ **别用 serialNumber<0 当标记**：UI 新建规则不设 serialNumber，
  默认就是 -1，会把用户自建规则全变成兜底
- 内置规则 28 → 13 条（15 条停用规则逐条实测：零效果 / 误判爆炸 / 污染，没有一条值得留）
- 离线回归 harness：`project/scripts/toc_regression.py` —— 照抄 app 的**两层**语义
  （选规则：第一个 block + 1000 字符窗口；切章：整文件、无窗口）。混成一层会让 14 本对拍书全对不上
- 剩余 54 本**确认没有章节结构**（两次独立扫描）→ 归 D4 AI 分章

## 标题字号被画成 0 号字（已修）

生效配置 `titleSize = 0` → 正文里那行章节名被画成 0 号字，彻底消失且无任何报错（用户只会
说「没标题了」）。原因：老配置/老备份里这个字段是「档位下标」，而 fork 里当 sp 用。已在配置
进内存与写回的所有入口夹到 8..60（`ReadBookConfig.withLegalTitleSize`）。

---

# PDF / EPUB 阅读引擎

## PDF：已换 MuPDF（2026-10-06）

**旧实现（`android.graphics.pdf.PdfRenderer` + 文本流里的 `<img>`）的三个病**，真机都复现过：

1. `PdfRenderer` **不允许并发 openPage**，而阅读页一次要取一"章"（旧实现 10 页）里的多张图 →
   抛异常被 `catch (_: Exception) { return null }` 静默吞掉 → **灰页**
2. `protected fun finalize() { closePdf() }`：GC 时把渲染器关掉，之后**连已加载成功的页也变灰**
   （用户报的「载入三页，一翻又没了」）
3. 零缓存：每次进入可视区都重新读盘 + 重新光栅化

**MuPDF 的获取方式**（Artifex 官方 Maven 不可达、JitPack 只有 metadata 没有构件）：

- `.so`：F-Droid 官方 MuPDF viewer 的 APK 里取 `lib/{arm64-v8a,armeabi-v7a}/libmupdf_java.so`
- Java 绑定：主仓库同 tag 的 `platform/java/src/com/artifex/mupdf/fitz/*.java`（62 个，已进仓库）
- 库名对应：`Context.java` 里是 `System.loadLibrary("mupdf_java")`

**四个真机踩出来的坑（改这块前必读）**：

1. **`getPixels()` 只接受带 alpha 的 RGB/BGR 位图**，否则运行时抛
   `invalid colorspace for getPixels (must be RGB/BGR with alpha)`
2. **本版本绑定里 `getPixels()` 返回 `IntArray`（已按 ARGB 打包）**，不是字节流 →
   直接 `Bitmap.setPixels(...)`，别自己拼字节/算 stride
3. ★ **必须自己建 pixmap、`clear(255)` 铺不透明白底，再用 `DrawDevice` 画页**：
   PDF 页通常没有背景填充（白底是阅读器给的），只画内容的话背景是透明的 → 在深色界面上
   就成了"白底黑字变黑底"（用户报的"显示不正常"）。官方 viewer 也是先 clear 再画
4. **页图缓存路径必须带版本号**（现在是 `pdf-v2/`）：磁盘缓存与 Coil 都按文件名作键，
   改了渲染方式却不换路径，就永远读到旧图（这个坑真机上白花了一轮）

**显示层走漫画阅读器**（`LocalMangaLoader` 增 PDF 分支 + `MainNavGraph` 路由）：
要的是 telephoto 的「双指缩放 + 缩放后按锁定尺寸平移」，文本阅读器那条路没有视口变换概念。

- 漫画阅读器缩放**默认关闭**（`MangaSettings.disableMangaScale` → `disableScale` 默认 true）→ 已对 PDF 强制打开
- PDF 一页一章 → 会命中"换章"分支 → 已对 PDF 的 `Ready` 分支返回空列表（否则一页正文夹一屏黑底提示）
- PDF 强制 `MangaScrollMode.WEBTOON_WITH_GAP`（连续滚动；分页模式在 PDF 上等于不停切章）
- 漫画阅读器背景默认纯黑（`MangaSettings.background` 默认 `0xFF000000`）→ PDF 换成米色底 +
  一点粉/蓝/紫的极淡对角渐变（其他书不变、深色主题不变）
- **PDF 换章名要触发重建**：`MangaReaderDataRepository` 只在 `chapterCount == 0 ||
  book.isLocalModified()` 时重建章节表 → 删掉该书的 chapters 行即可触发

## EPUB（未做，下一轮主任务）

现状：`me.ag2s.epublib` **只抽文本** → 用户要的"效果无损"做不到。
计划：解析继续用 epublib，渲染改成 **WebView 加载 spine + 注入主题 CSS**；有内嵌封面就用内嵌的。

---

# 2026-10-06 第三轮（PDF 整本一章 · 一路滑到底不停顿）

## 用户口径（这轮的原话）

> 「我对 pdf 的期待就是要从下滑到上不会出现停顿，现在滑到分页的地方自然会停下要重新再划」
> 「我希望我压根看不到加载那一下，我希望他能一直有预加载」

## 结构（改了就是这条，别退回去）

- **PDF = 整本一章**：会话里只有第 0 章，全部页都挂在这一章下（`LocalMangaLoader.loadPdf`）。
  列表内容永远够滑，图片预取器第一次看得见后面几十页。
- **DB 章节表仍是页级脚手架**（`localMangaLoader.chapters` 照旧返回 `第N页`）：
  目录用它（页级条目、点一条跳页）、书架进度用它、`durChapterTitle` 用它。
  **会话章数 = 1，章节表行数 = 页数**，两者不一致是有意为之。
- **页图是惰性地址** `pdf-page-image://<书md5>/<页号>`，`PdfPageMapper`（Coil Mapper，
  注册在 `appModule` 的 ImageLoader）在真正取图（**含预取**）时才光栅化。
  `PdfPageFiles` 取图后按页号顺序**预渲染 8 页**。

## 五个坑（改 PDF / 条漫前必读）

1. **一页一章 + 会话窗口 ±1 章 = 每页撞墙**：列表里永远只有前/当/后 3 页内容，
   滑到当前页底部前面就没东西 → 停顿 → 再划一次才补页。**连续滚动不能用「一页一章」。**
2. **条漫换章判定旧规则在一页一章时永远不成立**：旧规则要求「当前章整章滚出视口」，
   而 3 页内容 5377px 减一屏 2600px 最多滚 2777px，当前页底边在 3582px → 露在视口里出不去。
   真机表现：第 1 页翻不到第 2 页。现在判定是「**视口阅读线（视口中心）所在的那一页**」。
3. **光栅化不能放在章加载时**：一页一章时 `MangaChapterImagePrefetch` 只看得到当前 1 页，
   预取形同虚设，翻快点就当场渲染 → 看见加载。惰性地址 + Mapper 才让预取带上"提前渲染"。
4. **PDF 特判只覆盖了 `Ready`**：`Loading`/`Empty` 态仍会插一张带转圈的 96dp 换章卡片，
   而且 Coil 淡入会让下一页以半透明出现——两个都像"在加载"。现在 PDF 非失败态一律不插卡片、关淡入。
5. **进度口径**：书架百分比 = `(durChapterIndex + 1) / totalChapterNum`。
   PDF 会话章号恒 0，所以 `durChapterIndex` 必须存**页号**（见 `openBook` / `persistProgress`），
   `totalChapterNum` 仍是页数。写错了表现就是"永远 100%"或"永远 0%"。

## 自愈

PDF 章节表**行数 ≠ 页数**就按页重建（旧版留过「一段十页」的 `分段_N` 表）。
不需要 adb 改库；打开一次就修好。**旧「一段十页」的阅读位置没有做换算**（算不准），
那些书的续读位置可能比原来靠前。

## 环境坑（新增，都踩过）

1. **`adb shell am start` 会让 pi 的 bash 工具在那一行之后中断**（输出被吃、后续行不执行）。
   驱动设备要写成脚本 + 后台跑：脚本里 `exec >/tmp/xxx.log 2>&1`，然后
   `nohup bash <脚本> < /dev/null > /dev/null 2>&1 &`。
   ⚠️ **这个 nohup 必须是那次 call 的第一条命令**；跟在长 adb 命令（如 install）后面时，
   子进程会随 call 结束被清掉。
2. `ReadMangaActivity` 是 `exported="false"`，shell 起不来（SecurityException）。
   走主界面路由：`am start -n <pkg>/...MainActivity --es startRoute book/read/manga [--es bookUrl <路径>]`
   （`ROUTE_READ_MANGA = "book/read/manga"`；不带 `bookUrl` = 「最近在读」那本）。
3. 文件名里的 `&` 会让 adb shell 截断命令（番外篇就是这么没验成的）。
4. `run-as` 里的相对路径和 `sh -c` 容易踩空：用**绝对路径**、
   别用 `$D/*/` 这种要被外层 shell 展开的 glob，用 `find <绝对路径> -name '*.jpg'`。

## 这轮的验证手法（比看画面更硬）

- **浮层读数**：`第94页 · 页码 94/163 · 章节 1/1 · 进度 57.7%` —— 章节 1/1 证明整本一章，
  94/163 = 57.7% 证明进度口径没坏，一次快滑 45→94 证明不撞墙。
- **预渲染落盘数**：`adb shell run-as <pkg> find <cache>/local-manga/pdf-v2 -name '*.jpg' | wc -l`。
- 崩溃只看 `grep -c 'Process: <pkg>'`。

## 遗留

- 番外篇 / 能天使 打开一次让章节表自愈（7 行 / 3 行的旧 `分段_N`）。
- PDF 页缓存目录在会话结束仍会被清（沿用原设计）→ 每次重开要重新铺几页。
- PDF 页缓存的磁盘占用不设上限（163 页约 65 MB，且 `close()` 会清）。
