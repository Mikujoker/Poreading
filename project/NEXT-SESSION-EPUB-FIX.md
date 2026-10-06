# 新会话启动 prompt：修 EPUB 阅读页（菜单呼不出 + 要上下滚动）

把下面整段复制给新会话即可。

---

我在做个人定制的 Legado（阅读）安卓 app，仓库在 **WSL**：`~/work/legado-md3`（Windows 侧 read/edit 读不到 WSL 路径 → 用 bash；`\\wsl$\Ubuntu-22.04\...` 这种 UNC 路径 read/replace 可用）。项目文档在 `project/`。

## 本轮任务（EPUB 阅读页，用户报的两个 bug + 收尾）
EPUB 走的是 **Readium Kotlin toolkit**（不是自绘阅读器），相关代码：
- `app/src/main/java/io/legado/app/feature/reader/readium/ReadiumOpener.kt`（打开本地 epub → Publication）
- `app/src/main/java/io/legado/app/feature/reader/readium/ReadiumReaderScreen.kt`（Compose 页，宿主 `EpubNavigatorFragment`）
- `app/src/main/java/io/legado/app/ui/book/read/ReadBookRouteScreen.kt` 里 `state.book.isEpub` 时早返回该页（**独占整屏，绝不能叠加自绘阅读器画布**）

**必须修的 2 个问题**
1. **菜单呼不出**：现在用 Compose 在整屏 Box 里叠了一条"顶部 56dp 触摸条"（点它 `menuVisible = true`），真机上**点了没反应**。怀疑：`AndroidView`（Readium 的 WebView）把触摸吃掉了 / Compose 与 AndroidView 重叠区的 z 序与触摸分派不按预期。修的方向（优先官方做法）：**别用叠加**，改成给导航器留出真实空间的"上/下栏"（官方 test app 的 `EpubReaderFragment` 就是父 Fragment + 真 toolbar），或在 Compose 侧用可靠的方式拿到点击（必要时用 Android 原生覆盖 View 放在 WebView 之上）。菜单内容至少要有：**返回 / 目录**（现在这两个已经写好，在 `ReaderMenu`/`TocSheet` 里，只是进不去）。
2. **要上下滚动，现在是左右翻页**：我传的是 `EpubPreferences(scroll = true)`（`createFragmentFactory(initialPreferences = ...)`），真机上仍是**左右翻页** ✗。查清 Readium 正确开滚动的方式（怀疑要经 `EpubNavigatorFactory.Configuration(defaults = EpubDefaults(...))` 放开该偏好，或用 navigator 的 `submitPreferences(EpubPreferences(scroll = true))`）。**验收：手指上下滑动就是连续滚动**（不要左右翻页）。

**顺手可做（不影响使用）**：滚动/翻页切换开关、章节内精确进度（`Locator.locations.progression`）、主题映射（现在只有字号跟随 `styleConfig.textSize`，颜色/字体未映射）。

## 环境与固定手法（别重走）
- 编译：`bash ~/build3.sh`（增量 1~6 分钟，会写 `/tmp/b3.log`）；装机：
  `cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk && adb install -r "C:/Users/mikujoker/legado-work/v.apk"`
  adb：`/mnt/c/Users/mikujoker/AppData/Local/Android/Sdk/platform-tools/adb.exe`；包名 `io.legato.kazusa.debug`
- **只信跑到结论的构建**：`grep -E "BUILD SUCCESSFUL|BUILD FAILED" /tmp/b3.log`（我曾看日志尾部就误报"0 错" ✗ 别犯）
- ⚠️ `adb shell am start` 会让 pi 的 bash 工具在该行之后中断 → 写成脚本 + `nohup bash <脚本> &`，且 nohup 必须是该次调用的第一条命令
- 冷启动打开某本书（`--es bookUrl` 传中文路径要整条命令带引号，热启动会忽略 bookUrl）：
  `adb shell "am start -n io.legato.kazusa.debug/io.legado.app.ui.main.MainActivity --es startRoute book/read --es bookUrl '<epub 绝对路径>'"`
- **CDP 在这台设备不可用**（`Runtime.enable/Page.enable/Runtime.evaluate` 全超时）→ 验证靠**截图**（`adb exec-out screencap -p > x.png`，然后用 read 看图）+ 崩溃栈（`adb logcat -b crash -d | tail -40`）
- 设备上的 epub 测试书：`/storage/emulated/0/Download/Browser/【ice3333】作品合集.epub`（含多子书，适合测目录）
- **千万别动数据库**：用户有 391 本书 / 24519 个书源；AppDatabase 现在 `version = 111`（我加的 `opdsSources` 表 + AutoMigration 110→111），**不要 fallbackToDestructiveMigration**

## Readium 的关键事实（我已经踩过，直接用）
- 依赖已就位：`readium-shared/streamer/navigator` **3.4.0**（catalog 里 `readium = "3.4.0"`）
  - **3.3.0 会崩**：`WebViewServer.allowCors` 往不可变 map 里 put → `UnsupportedOperationException`，3.4.0 才修
  - 三个 readium 依赖**必须 `exclude(group="org.jsoup", module="jsoup")`**：Readium 会把 jsoup 顶到 1.22，而本仓库因 issue #3811 必须留在 1.16.2，否则 `AnalyzeByJSoup.kt:388` 编译失败
- 打开书：`AssetRetriever(contentResolver, DefaultHttpClient())` → `retrieve(File)`；`PublicationOpener(DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null))` → `open(asset, allowUserInteraction = true)`（都是 suspend，`Try` 用 **`getOrNull()`** 不是 `getOrElse`）
- 呈现：`EpubNavigatorFactory(publication, Configuration()).createFragmentFactory(initialLocator = ..., initialPreferences = ...)`（`initialLocator` 必填可传 null）；宿主用 `FragmentContainerView` + `supportFragmentManager.fragmentFactory.instantiate(...)`（也可用官方建议的 `AndroidFragment<EpubNavigatorFragment>`，见 https://github.com/readium/kotlin-toolkit/discussions/552）
- `EpubNavigatorFragment`：`go(locator: Locator, animated: Boolean)` / `goForward/goBackward(Boolean)` / `currentLocator: StateFlow<Locator>`；`Publication.tableOfContents`（Link 树，`Link.children`）/`readingOrder`/`locatorFromLink(Link)`
- **查 API 别猜**：从 Gradle 缓存 javap（我用这个把签名一个个钉死）
  ```bash
  cd /tmp && rm -rf rd && mkdir rd && cd rd
  for m in navigator streamer shared; do a=$(find ~/gradle-home/caches/modules-2/files-2.1/org.readium.kotlin-toolkit/readium-$m -name "*.aar" | head -1); mkdir -p $m && unzip -o -q "$a" -d $m classes.jar && unzip -o -q $m/classes.jar -d $m/cls; done
  ~/toolchain/jdk21/bin/javap -public -cp navigator/cls org.readium.r2.navigator.epub.EpubPreferences | grep get
  ```
- 官方文档：https://readium.org/kotlin-toolkit/latest/guides/navigator/navigator/ ；参考实现（Readium + calibre-web + OPDS）：https://github.com/chmouel/liseur

## 其他约束
- `ReadBook` 的会话字段（`book/durChapterIndex/durChapterPos`）是 **private set** → 外部只能用语义化命令，我目前用 `viewModel.onIntent(ReadBookIntent.OpenChapter(index))` 回写进度
- 架构护栏：UI/ViewModel **不得直连 DAO**、不得新增旧偏好调用（`verifyConfigArchitecture` 任务会在编译时卡住）
- 沟通用中文；小步改、每步跑到构建结论；改完在真机截图验收（菜单可见 + 上下滚动）
