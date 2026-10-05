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
