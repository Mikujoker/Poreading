# 进度日志

## 2026-10-05

### 侦察阶段

- 确认设备：OnePlus PJE110（一加 11），Android 16 / SDK 36，ColorOS
- 原版「阅读」= `io.legado.app.release` **3.25**，2025-01-22 后未更新
- 决策基线：`HapeLee/legado-with-MD3`（3.26.16），它自带包名 `io.legato.kazusa` → 天然共存
- 导出备份：`bookshelf.json` 385 书 / `bookSource.json` **24528 条 105 MB**

### 本地书问题的三级根因（逐层挖出）

1. **文件名乱码不可逆**：下载器把 GBK 文件名按 UTF-8 解码，非法字节被替换成 U+FFFD。
   设备端 `od` 证明磁盘上真的存着 `EF BF BD`。但 **57% 能从内容首行/《》还原**。
2. **bookUrl 是 SAF URI**：`content://com.android.externalstorage.documents/tree/…`
3. **SAF 授权不能跨 app 转移**：
   ```
   Permission Denial: opening provider com.android.externalstorage.ExternalStorageProvider
   from io.legato.kazusa.debug … requires that you obtain access using ACTION_OPEN_DOCUMENT
   ```
   `MANAGE_EXTERNAL_STORAGE` 只给**裸路径**权限，**不给 SAF content:// 权限**。

### 走过的弯路（记录下来避免重犯）

- ❌ **给旧 app 注入新 bookUrl**：Legado 会重新推导本地书 URL（`导入根目录 + originName`），
  注入的值被覆盖 → 读取失败。已完整回滚，并改用 app 原生导入方案。
- ❌ **第一次用 `platforms;android-37` 装 SDK**：Android 改用次版本号了，真名是 `platforms;android-37.0`。
- ❌ **`exec > >(tee ...)` + nohup**：进程替换在 nohup 下挂掉，脚本没跑起来。改成直接重定向。
- ❌ **`| tail -40` 包住构建命令**：输出被缓冲，看不到实时日志，误判成卡死。改成直写日志文件。

### 完成的修复

| 提交 | 内容 |
|---|---|
| `f6defb7` | Web 服务端口被占时不再崩溃；失败时回滚服务状态；网络监听加 isRun 守卫 |

### 完成的运维

- 重复文件：87 组同尺寸 → md5 校验后 **86 组真重复 / 181 个文件**，移走 95 个
  （保留规则优先保留**书架在用**的那一份）
- 完整性验证：281 本引用 → 277 完好；缺的 4 个**操作前就已缺失**（双重证据）
- **281 本本地书批量修复**：bookUrl 从 SAF URI 改成裸路径
  - 280 本「新建 + 删旧」，1 本只删旧，**零失败**
  - 抽样复测：先前报失败的 4 本全部可读（1576/1284/625/221 章）
- 书源体检方案：24528 → 建议保留 1584，冻结 5110

### 测试脚本教训

用 Web API 测本地书正文时，**必须先 `refreshToc` 建章节表再 `getBookContent`**。
直接读正文时 app 会内部等 30 秒（懒生成章节表），若 curl 超时设得比 30 秒短就会**全部假失败**。

### A1 书源管理界面（同日）

**做完**：五级健康度（SourceHealth）+ 规则快照 SQL + ViewModel 接线 → 统计条 → 行样式 → AI 入口。
全部装机截图验证。

**踩到的三个坑**
1. `book_sources_part` 是 Room 的 **DatabaseView，没有规则字段** → 另加只读 SQL 取「规则是否为空」，
   避免改视图（改视图要动 DB 版本 + 迁移）
2. `BookSource.respondTime` **默认 180000**（从未测过）→ 原判定把它当「慢源」，
   3.5k 条被误标冷冻。改成只有 8s~180s 之间才算慢
3. 属性初始化顺序：Kotlin 按声明顺序初始化，`ruleFlagsByUrl` 必须声明在引用它的地方之前

**性能实测（关键结论）**
- `book_sources_part` 视图 5.2s、规则查询 4.1s、**只读 3 个小列也要 2.6s**（PC SSD）
- 根因是数据体量：24528 行 / 119 MB，SQLite 行式存储，读小列也要扫全页
- 结论：**这个页面慢是体量决定的，不是代码问题**（原版列表用同一个视图，同样 5 秒）
- 已做：字符串判 host、单次遍历、stateIn 共享、落盘缓存（stale-while-revalidate）
- 未做：**列表分页**（缓存救不了 flowAll）

**UI 冲突（待拍板）**
按原型加的底部批量操作条与本 app 原有的底部图标条重叠 → 撤掉自己那条，避免重复 UI。

## 2026-10-05（晚）A1 三标签重写

### 做完

- 数据层：`book_sources` 加 `isFavorite`；视图 `book_sources_part` 同步加列；
  `migration_107_108`（ALTER + 重建视图）；DAO/Repository 写入点
- ViewModel：`BookSourceTab` 三标签、星标乐观更新、刷新只扫常用；
  删掉 `ruleFlagsByUrl` + `HealthIndex`（省一次 4.1s 全表查询）与全部批量意图
- 页面：标签行钉在顶栏下、失效页刷新、行上星标；删统计条/多选/行状态色/11 项批量操作
- 删文件 `SourceHealth.kt`、`SourceHealthStrip.kt`；字符串 4 个 locale

### 性能防护（刷新要跑几千条，不加会死）

1. 校验状态 500ms 采样后才喂 UI（否则每条结果都重算整张列表）
2. 校验进行中暂停订阅全表快照（否则 Room 每写一条就让 119 MB 查询重跑一遍）
3. `setEnabled` 复用共享快照，不再单独开全表查询

### 迁移安全

- 迁移前全量拉库备份到 `legado-work/predb/`（125 MB + WAL）；基线：24528 源 / 6429 启用 / 385 书 / v107
- 迁移后核对：**完全一致**，user_version=108
- 关键点：迁移里重建视图的 SQL 必须与 `@DatabaseView` 注解**逐字一致**（含行尾空格），
  Room 迁移后拿 `sqlite_master` 原文比对；已对 `108.json` 的 `createSql` 做 `==` 校验通过

### 踩到的坑

1. **`adb shell input tap` 在 Compose 上会偶发丢点击** —— 同一坐标点两次，一次无效一次有效。
   复测改用 `input swipe x y x y 120`；坐标来源从「截图目测」改成 `uiautomator dump`
   （目测会系统性偏 50~80px，害我两次误判成「按钮坏了」）
2. adb daemon 会自己掉（`cannot connect to daemon`）→ `kill-server` + `start-server`
3. 设备无 sqlite3 → 查库要 `adb exec-out run-as ... cat databases/legado.db` 拉到 /mnt/c 用 python 查

### 验证结论（都截图看过）

三标签、星标跨进程持久、刷新只扫常用（1 条 → 成功 0 失败 1）、失败落失效列表带原因、
取消星标即时退出失效、三种空态、无常用时空集提示、校验设置面板、本包崩溃 0

## 2026-10-05（夜）阅读页：字体 + 纸纹

用户把优先级改成「阅读体验（背景/字体）+ AI 修源」最前。

### 修的真 bug

字体选择器存 `file://` URI，而 `loadTypeface` 只认 `content://` 和裸路径 →
`File("file:///…").isFile` 恒为 false → **静默回退系统字体**。用户说「没看出来字体改变了」，
判断是对的。修完楷体一眼可见。

### 迭代过程（三次被否）

1. 纯色米纸 `#F6F1E8` → 被否：「很单薄啊，一点质感都没有」
2. 照 prototype 的 CSS 配方（底色 + 三个径向色斑 + 噪声）烘成图 → 被否：
   「只看出来一点点，就是上面两个黄色图案，看起来很丑」—— 色斑是给浏览器大屏的，手机上就是黄渍
3. 改成纸本身该有的层次（受光 + 帘纹 + 纤维 + 云纹 + 颗粒），做三版让用户现场点选 → 选「纸页」

### 关键工程发现

- **app 会把 `files/readConfig.json` 写回**（任何样式改动都落盘内存列表）→ 从外面 push 配置
  一定会被覆盖。正确做法：预设写进 `assets/defaultData/readConfig.json`，设备端删掉私有文件让它回落
- 背景 `bgType`：0 纯色 / 1 assets / 2 externalFiles 或绝对路径
- 纸纹必须按手机真实分辨率（1264x2780）生成，否则颗粒被放大糊掉

### 交付物

- `scripts/gen-paper-texture.py`（纸纹生成，可调参重跑）
- `assets/bg/kazusa-*.jpg` 五张（纸页/纤维/棉絮/暖褐夜/深蓝夜）
- `assets/defaultData/readConfig.json` 内置预设 index 6-9

## 2026-10-05（深夜）B5 书架页显示优化（第一版）

用户指出「这么搞本身就很丑，你不如上网搜搜无封面默认封面的设计」。于是先查资料
（bookcoverslab / coverfairy），再按原则改，不再盲猜。原则已写进 `DESIGN.md`。

### 改了什么

- **默认封面（无封面图时）**：书名改成「自己断行 + 居中成块 + 行距 1.18 + 字号随长度自适应」，
  去掉白色描边，**有书名时不再画 `Icons.Default.Book` 占位图标**（抢焦点）
- **封面下的小标题**：按 DESIGN.md 字阶定 13sp/500（小字号档 11sp）、行高 1.4、
  固定两行高度（一行与两行的书名不再把下一排顶歪）

### 踩的坑

1. 第一次改把书名整块**向左溢出被裁**：StaticLayout 自己按 `setAlignment` 居中，画笔必须是 `LEFT`；
   我用 `CENTER` 又居中了一次。后来干脆弃用 StaticLayout，自己按实测宽度断行 + 逐行 `drawText`。
2. 改名 `namePaint` → `nameTextPaint` 后漏改绘制处引用，编译报 Unresolved。

### 外部依据（摘）

- 一个阅读顺序；三者一样大是最常见败笔
- 描边/发光是「弱构图的遮羞布」，分隔该来自留白与色块
- 断行按视觉重量平衡、不留孤字；缩略图 + 灰度双测试
- 默认封面书名实测对比度 6.28:1

### 默认封面改版（用户给了具体口径）

用户否掉了「通用书本图标 + 居中书名」的方向，给出中式书封口径：米粉底 + 山水 + 手写楷体书名 +
书名下朱红印章印作者（无作者则都不要）。

- 山水：程序生成（`scripts/gen-cover-art.py`）。第一版失败（锯齿山脊、灰板砖水面、硬条雾气），
  重写为「低频平滑脊线 + 每层墨色自上而下渐隐 + 高斯柔边 + 宽雾带 + 水面色不是块」
- 资源 `assets/coverArt/kazusa-shanshui.png`（480x645，128 KB）
- 书名用手写楷体（霞鹜文楷），字号按长度分档，断行沿用 `balancedTitleLines`
- 印章只在有作者时画

## 2026-10-07 本地书文件治理（导入即搬移 / 改名连源文件）

### 用户报的三个现象与真实根因

| 现象 | 根因 |
|---|---|
| "导入后 novel 里是副本，原文件名字没改" | `FileAssociationActivity` 的导入逻辑是「把源文件**拷**进默认书目录」，源文件原地不动（默认书目录被设成 `…/tree/primary%3ADownload%2Flegado%2Fnovel`） |
| "改名连源文件后，详情页文件名不对" | SAF 分支改名成功，但 `BookInfoEditViewModel` 用 `File(newUrl).name` 取文件名 —— `newUrl` 是 `content://…/document/primary%3ADownload%2F…`，取出来是 URL 编码串，写回 `originName`，详情页就显示这堆垃圾 |
| "莫名要重新加入书架 / 打不开、目录内容出错" | `Book.getLocalUri()` 读不到旧 URL 时会「按 originName 找同名文件 + `bookDao.replace()` 重绑」——`replace` 是 delete+insert，而 `chapters.bookUrl` 外键是 `ON DELETE CASCADE`，**整本目录被静默删掉**；同时 `localUriCache`/`ReadBook` 还握着旧 URL（`ReadBook` 里 `book?.bookUrl != input.book.bookUrl` 会直接拒收章节输入） |

### 改了什么

- **新增** `help/book/LocalLibrary.kt`：书库目录（`Download/legado/novel`）、文件名安全化、重名退让、
  `stage()` 返回可 `commit`/`rollback` 的在途搬移（同卷 `renameTo` 秒改；跨卷/权限不足退化为拷贝，**库搬完才删源**）
- **新增** `help/book/BookUrlMigration.kt`：`books.bookUrl` 是主键，改文件名 = 改主键；查 schema 把所有
  `bookUrl` 列 + 外键指向 `books(bookUrl)` 的表一起平移（含 `exact_chapter_page_counts.bookId`），
  书签/阅读记录按「书名+作者」跟着改；事务内用 `defer_foreign_keys`（嵌套时）/ `foreign_keys=OFF`（独立时）
- **改** `LocalBook.importFile`：先按解析出的书名把文件搬进书库目录，再建书（`bookUrl` 记裸路径）
- **删** `FileAssociationActivity` 里"拷进默认书目录"整套逻辑与选目录面板
- **改** `LocalBookRename`：搬移 → 迁移 → 删源；改名后作废 `localUriCache`、同步 `ReadBook` 的 URL
- **改** `BookExtensions.getLocalUri()` 两处自动重绑：`delete+insert` → `BookUrlMigration`（不再 CASCADE 删目录）
- **改** `BookInfoEditViewModel`：`originName` 用搬移结果的真文件名；`ReadBook` 用旧 URL 判断
- **改** `BookInfoViewModel` + `BookInfoRouteScreen`：`onBookUrlChanged` 同步内存 URL、`onInfoEdited` 回写 `inBookshelf`
  （修「改名后显示放入书架」的竞态：ON_RESUME 刷新会拿旧 URL 查库查空）
- **加** 详情页右上角菜单「删除书籍」（用户要求加回，点开原 `ShelfDeleteSheet`）
- **API**：重写 `/renameLocalBooks`（含 content:// 书、originName 对齐、dryRun 计划），新增
  `/importLocalFile`、`/scanLocalLibrary`、`/deleteLocalFiles`

### 验证（端上实测，不靠"感觉"）

- 导入：`Download/Browser/soushu2025.com@搬移测试[搜书吧].txt` 经"打开方式"导入 → 源文件消失、
  落在 `novel/`、`bookUrl` 裸路径、章节已解析
- 改名：UI 里把《女侠且绿》改名（选「一起改」）→ 详情页「已在书架」+ 文件名 `UITestBook.txt`；
  章节表仍 16 行、进度 `durChapterIndex=12/durChapterPos=12021` 保留、旧文件消失/新文件在（测试后已还原书名）
- 善后 API 执行：`originNameAligned=141`、`moveIntoLibrary=1`、`failed=0`，复查 dryRun `planned=0`
- 办法：`uiautomator dump` 拿坐标自己点 UI（`am start` 以 shell 身份发 content:// 会被系统
  `SecurityException` 拒，改 `file://` 或 intent 路由 `--es startRoute book/info --es bookUrl`）

### 踩的坑

1. 设备上装的是 `noR8` 变体（`versionName=…-noR8`、`isDebuggable` 继承 release = false）→ `run-as` 不可用；
   临时给 `noR8` 加 `isDebuggable = true` 出取证包读完 DB，提交前已还原
2. `adb` 是 Windows 侧 exe（WSL wrapper），`/tmp` 路径它看不见 → push/pull 一律用 `C:\…` 或
   `cd /mnt/c/...` 后的相对路径
3. 设备 shell 里带中文/GBK 的 `ls|grep`/`find -name` 不可靠 → 让脚本把结果写文件再 pull 回来分析

### 后续（同日）：外部书全部归位

- 用户确认后把 novel 之外的 **138 本**（Browser / QQBrowser / [sxsy.org]炎心 / BaiduNetdisk / Download 根）
  一并搬进 `Download/legado/novel` 并改名为书名（`/renameLocalBooks {includeOutside: true}`），失败 0
- 结果：全部 **281 本**本地书都在 novel、路径 **MISSING 0**、无孤儿文件、无内容重复组；复查 dryRun `planned=0`
- **注意**：Web API 走 `127.0.0.1:1122` 时本机 `http_proxy=127.0.0.1:7890` 会抢答 **502**，
  脚本里要 `urllib.request.ProxyHandler({})` 绕开（上一轮 `tools/book_debug.py` 早踩过同一个坑）；
  另外 Web 服务随 MainActivity 起停，重装/进程被杀后要先 `am start` 拉起再用 API

### 目录重建 + TXT 兜底 + 推 GitHub（同日）

- 142 本被旧代码清空目录的书：查 `/getChapterList` 时代码在空表时会自动回退 `refreshToc`，
  于是那一轮扫描等于**把 243 本一次性重建了**（250/257 有章节）
- 剩 7 本报"目录列表为空"（章标格式在现有规则覆盖外，如 `#第一章·…`、`第一章␣␣…`、行首无章标）：
  改为**规则无匹配时退化为「无目录分章」**（`TextFile.getChapterList`：清 `book.tocUrl` → `analyze()` 按
  `maxLengthWithNoToc` 切块），7 本全部可读（111/68/12/71/20/56/33 章）
- **推到 GitHub 踩的坑**：
  1. WSL git 没有凭据 → 可借 Windows 侧 GCM：
     `git -c credential.helper='/mnt/d/Git/mingw64/bin/git-credential-manager.exe' push ...`
     （凭据在 Windows 凭据管理器里的 `git:https://github.com`，不用把 token 贴出来）
  2. **本仓库是浅克隆**（`.git/shallow`）→ 从浅克隆推完整历史到别的远端会报
     `remote: fatal: did not receive expected object … / index-pack failed`（`--no-thin` 也没用）
     → 先 `git fetch --unshallow origin`（上游 79.5 MB）再推
  3. 用户仓库 main 原是一个孤立"快照"提交，与本地历史无共同祖先 → 用 `--force-with-lease` 覆盖
