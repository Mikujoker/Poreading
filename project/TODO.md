# 待办与需求清单

状态：`[x]` 已完成 · `[~]` 进行中 · `[ ]` 待做 · `[?]` 待用户决定
优先级：`P1` 最先 · `P2` · `P3` 可以往后

> ## 当前优先级（2026-10-05 深夜，用户重排）
>
> 用户原话：「要做的首先是 ui 背景字体适配，然后是书架页面显示书籍的优化，
> 然后是使用体验 ui 跟手感流畅度的优化，这几个优先级拉到最高」
>
> **P0（按顺序）**
> 1. **UI 背景字体适配**（B1/B3/B6）—— 阅读页背景 + 字体，参照 prototype 里那套搭配
> 2. **书架页面书籍显示优化**（B5）—— 封面下小标题排版 + 无封面时的默认封面构图
> 3. **使用体验 / UI / 跟手感 / 流畅度**（B2）—— spring 动画、共享元素、predictive back
>
> **新收需求（先入库不急做）**
> - **EPUB 与 PDF 适配**：PDF 要能放大观看，且**放大后不会自己漂移**；
>   上下拖动 = 正常滚动（不要变成平移画面）
>
> 其余（书源去重入库、AI 修源等）排在这些之后。


## ⚠️ 状态校准（2026-10-06，以 git 历史 + 代码为准）

下面正文里的 `[ ]` **大量是过期标记**，按 git/代码核实后的真实状态如下：

**其实早就做完（别再当待办）**
- **B1/B3/B6** 阅读页字体 + 纸纹 + 配色 → `PROGRESS.md`「阅读页：字体 + 纸纹」；`ReadStyleConfigStore`；提交 `1a5bcca`
- **B5** 书架显示 + **默认封面**（米粉底 + 山水 + 手写楷体书名 + 朱砂印章；没作者不画印章）→ `DESIGN.md` 最终口径 + `CoilBookCover.kt` + `scripts/gen-cover-art.py`；`37630e6 perf(bookshelf)`
- **B2** 手感/动效（spring / 共享元素 / predictive back）→ 代码在（`DampedDragAnimation`、`SharedTransition`、`predictiveBack`）
- **PDF 适配** → `3f63a09`（MuPDF 内核 + 漫画阅读器缩放）+ `4a513ee`（连续滚动、去换章黑层）+ `7ee2dfb`（按需渲染 + 后台预渲染）
- **D5** 分章规则包罗万象版 → 覆盖率 69.1%→76.5%，规则 28→13，兜底语义
- **D2** AI 书源自愈 → 2026-10-06：端上整源生成 / 单字段修复 / 登录能力 / 调试接口
- **E1** release 构建 → `legado-work/rel.apk` 33MB（debug 106MB），release 变体 minify + 签名齐备

**要做（用户已确认，其余已从需求删除）**
1. ✅ **本地书文件治理 第二轮完成**（2026-10-07）：
   - **导入即搬移**：本地文件导入时直接搬到 `Download/legado/novel/<书名><ext>`（不再"拷一份副本、源文件原地不动"），`bookUrl` 记裸路径；`LocalBook.importFile` 统一入口，`FileAssociationActivity` 里那套"拷进默认书目录"删掉
   - **改名连源文件**：`LocalBookRename` 重写为「搬移 → 库内引用迁移 → 删源」，`books.bookUrl` 是主键，迁移走 `help/book/BookUrlMigration.kt`（查 schema 平移所有 `bookUrl`/外键引用表）
   - **修掉的 bug**：① `getLocalUri()` 的自动重绑原来用 `delete+insert`，`chapters` 外键 ON DELETE CASCADE 会把整本目录删掉 → 改成真迁移；② SAF 书改名后 `originName = File(content://…).name` 写进 URL 编码垃圾，详情页文件名显示不对 → 用搬移结果里的真文件名；③ 改名后详情页显示「放入书架」（`refreshShelfState` 拿旧 URL 查库查空 + `onInfoEdited` 不回写 `inBookshelf`）→ `onBookUrlChanged` 同步内存 URL
   - **数据修复已执行**：141 本 `originName` 对齐磁盘真名，1 本 `content://` 转裸路径，失败 0，复查幂等（复查 dryRun planned=0）
   - **删除入口**：详情页右上角菜单加回「删除书籍」（点开原删除 Sheet）
   - **API**：`/renameLocalBooks`（重写）、新增 `/importLocalFile`、`/scanLocalLibrary`、`/deleteLocalFiles`
   - **外部书全部归位（2026-10-07 已执行）**：`includeOutside=true` 把 Browser/QQBrowser/炎心/BaiduNetdisk 等目录外的 **138 本**一次性搬进 `Download/legado/novel` 并改名为书名，库内引用同步迁移，失败 0；复查 `planned=0`、**281 本本地书全部在 novel、路径 MISSING 0**、孤儿 0、内容重复组 0
   - **收尾清理（2026-10-07 已执行）**：删掉 6 条指向已消失文件的虚引用记录（各自在 novel 里都有文件仍在的另一条，删前逐条核对）+ 2 个 Alice 孤儿 epub（内容相同的重复下载、无书引用）；复查 281 本本地书路径 **MISSING 0**、novel 孤儿 0、内容重复组 0
2. 🔄 **EPUB 无损阅读（实现完成，待端上验证）**：新增 `model/localBook/EpubWebDocument.kt`（原样取压缩包 XHTML + 资源流 + 主题 CSS 注入）与 `feature/reader/EpubWebContent.kt`（WebView + `shouldInterceptRequest` 拦截 `https://epub.local/*`）；`ReadBookRouteScreen` 里对 `book.isEpub` 覆盖一层 WebView（画布仍在下层，分页/首帧/章节状态机照旧）。
   - 进度：章节内位置 = 滚动比例 ×10000 写进 `durChapterPos`（`publish=false`，持久化读字段所以存得住）
   - 验证手段：**CDP**（debug 包已开 `WebView.setWebContentsDebuggingEnabled`）查 DOM/计算样式/图片解码，不用截图；脚本 `legado-work/verify_epub.py`
   - 旧描述（作废）：（用户已定方案：**整本改 WebView**，spine 逐章 + 注入主题 CSS + 保留目录/进度；`androidx.webkit` 已依赖 → `WebViewAssetLoader` 或 `shouldInterceptRequest` 从 zip 取资源都可以）
3. ⏳ 之后：**版本切正式 release**（R8 + ABI 拆分；E1 已出过 33MB `rel.apk`）+ 确认流畅度；注意 release 是 `io.legato.kazusa`（另一 app → 数据要迁移）、无 `run-as`
4. ✅ PDF 小活已完成：`番外篇/能天使` 经 `refreshToc` 自愈（章节表 3 行 → 24 行）

**已按用户要求从需求删除（不再跟踪）**：B4 首开提速、E2 存储权限主动申请、D3 AI 找封面、D4 AI 分章命名、书源去重 12680 条入库与其验证、A2 书源体检方案、B5 两个待决点（书名重复 / 书名块位置）

## 已完成

- [x] 备份：app 内本地备份 zip + 坚果云 WebDAV
- [x] 编译环境从零搭好（WSL + JDK21 + Android SDK 37），能出 APK
- [x] 装机验证：与原版共存，不覆盖
- [x] 数据迁移：385 本书 + 24528 书源 + 分组 + 书签 + 进度
- [x] 修复：Web 服务端口被占用时崩溃（提交 `f6defb7`）
- [x] 修复：Web 服务启动失败后 UI 开关仍显示「已开启」
- [x] **修复：281 本本地书在新 app 里全部可读**（SAF URI → 裸路径）
- [x] Download 重复文件清理：95 个隔离到 `legado-trash/`，可回收 182 MB
- [x] 书源体检方案（`artifacts/source-plan.csv`，24528 行）
- [x] 回滚清单 4 份（见 `artifacts/`）

---

## P1 — 第一优先

### 书源（用户指定 P1 首选）

- [x] **A1 书源管理界面重写**（2026-10-05 完成，装机验证）
  - 三个标签：**常用 / 失效 / 全部**（用户口径：启用是启用，常用是常用，常用≈收藏）
  - 常用 = 新列 `book_sources.isFavorite`，行上星标切换；**失效 = 常用里校验判失败的**（失效 ⊆ 常用）
  - 「失效」页一个「刷新」才跑扫描，只扫常用（复用 `BookSourceCheckService`），结果留内存
  - 保留行菜单「AI 修复」入口；五级健康度 / 统计条 / 多选 / 全部批量操作已删除
  - 详见 `HANDOFF.md`

[?] **A2 应用书源体检方案**（等用户决定）
  - 建议：只冻结「响应 > 8s」那 2601 条（收益最大、风险最小）
  - 数据：启用 6429 → 建议保留 1584；冻结 5110
    - 响应>8s 2601 / 同站同名重复 1105 / 规则不全 650 / 从未成功 424 / 坏源 330

### 阅读界面（用户明确痛点）

- [ ] **B1 自己的阅读主题系统**
  - 用户原话：「不想用阅读自带的那种背景了」「界面和字体字颜色看着都一般」
  - 目标：背景/字体/配色/行距我们自己定，不从这里自带的十几种背景里挑
  - 含：正文字体、字重、字距、行高、首行缩进、段距、边距、夜间配色

- [ ] **B2 转场动效：spring 动画 + 共享元素 + 手势跟手**
  - ⚠️ 已整理标准见 `DESIGN-REFERENCE.md`（Emil Kowalski 动效数值标准）
  - 铁律：UI 动画 ≤300ms；进入用 ease-out；永不用 ease-in；永不 scale(0)；错峰 30–80ms；尊重 prefers-reduced-motion
  - 用户原话：「书架子页面切换时感觉不是特别丝滑流畅，没有系统级滑屏来的流畅舒服」
  - 目标：用 spring 物理动画（不是线性补间）、书架封面→详情→阅读页的共享元素转场、
    predictive back 跟手
  - 验收：主观对比原版明显更"跟手"

- [ ] **B3 字体系统**
  - 用户选择：外挂字体目录（`/sdcard/legado/fonts/`），APK 不膨胀
  - 候选（均为可自由分发的开源授权）：
    正文楷体 霞鹜文楷 Screen/Lite · 正文宋体 思源宋体 · UI 思源黑体/更纱黑体 ·
    标题 得意黑 · 西文 Inter / Source Serif 4
  - 现状：app 已有 `fontFolder` 设置项，3.25 就支持外挂

- [~] **B5 书架页两处文字都难看**（用户明确指出的两处）— 第一版已装机验证
  - 已改：默认封面构图（自适应字号 + 均衡断行 + 居中成块 + 去描边 + 有书名时不画占位图标）；
    封面下小标题 13sp/500、行高 1.4、固定两行高度
  - 待决：①书名在「封面上」和「封面下」重复出现（可对默认封面隐藏小标题，或改显作者）
    ②书名块现在垂直居中（可改成经典书封的上三分之一定位）③默认封面的底色/字色是否再设计
  - **5a 封面下方的小标题**
    - 问题：字号/字重/行高没设计过，长标题断行难看，作者名没弱化，与封面间距随意
    - 方向：定字号阶与颜色阶；标题最多两行 + 优雅省略；作者名降一级颜色与字号；
      封面与文字间距、左右对齐统一
  - **5b 没有封面时，封面占位区里那行标题**
    - 问题：这是 app **用书名合成的默认封面**，构图没设计过 → 字挤在中间、大小失衡、断行难看
    - 方向（待选）：
      1. 首字大字做法：取书名首字做一个大号字标（monogram）+ 书名小字，像 Apple Books 自动生成的封面
      2. 渐变底 + 书名自适应字号居中：长书名自动缩字号，保证不溢出、不丑断行
      3. 纯色/纹理底 + 严格留白 + 书名顶端对齐（书籍封面常见的排版）
      4. 按书名 hash 生成稳定的柔和渐变底色，同一本书每次看到都一样
    - 相关既有设置：`config.xml` 里已有 `useDefaultCover` / `coverShowName` / `coverShowAuthor`，
      说明这块本来就是可配的，我们改的是**生成出来的观感**
  - 两处都纳入 B1 的设计系统（字号阶 + 颜色阶 + 间距规则）

- [ ] **B6 阅读界面配色/字体看着一般**
  - 用户原话：「当前的界面和字体字颜色看着都一般」
  - 做法：定一套完整的颜色 token + 中文正文字体/字重/字距，见 B1 / B3

- [ ] **B4 首次打开本地书卡 30 秒**
  - 原因：本地书的章节表是懒生成的，第一次打开要等它建好
  - 目标：在转换/导入阶段预建章节表

---

## P2 — 第二优先

### 本地书

- [ ] **C1 239 个乱码文件改名**
  - 现在比原计划简单得多：裸路径 + Web API 即可，**不需要重导、不需要回填进度**
  - 映射表已备好：`artifacts/rename-plan.csv`（136 本有进度的用 app 内书名，103 个孤立的从内容猜名）
  - 目标目录：`/sdcard/Download/legado/novel/`

- [ ] **C2 app 内改名时同步重命名磁盘源文件**
  - 用户原话：「以后导入新书改名时可以直接连源文件名也改了」
  - 做法：改 `Book` 编辑逻辑，改名时同步 `renameDocument`

- [ ] **C3 导入新书时规范化文件名 + 不产生重复副本**
  - 用户原话：「每次导入新书时不会额外多产生一个文件」
  - 注：实测那些重复是**下载器（QQBrowser 小说模式）双写**产生的，不是 Legado 干的
  - 目标：导入时按书名规范化文件名；并调查是否能在 app 侧避免/收敛

- [ ] **C4 AI 复原乱码文件名**
  - 42 个猜不出名字的孤立文件，交给 LLM 读内容让它命名
  - 素材：已经用正则+启发式搞定了 57 个（`artifacts/rename-plan.csv` 里标注了 conf）

- [ ] **C5 43 个孤立乱码文件处理**
  - 不在书架里，改名零风险；含 11 个 zip、5 个 rar

- [ ] **C6 4 个历史坏引用修复**
  - 3 个文件还在别处（可修），1 个（`203-228.txt`）已彻底丢失

### AI 能力

- [ ] **D1 多 provider 接入框架**
  - 用户选择：DeepSeek 为主，可切 OpenAI/Claude/智谱
  - ⚠️ API Key 必须放在设置页里加密存储，**不能硬编码进 APK**
  - 发现：这个 fork 自带「AI 对话」入口，可直接复用

- [ ] **D2 AI 书源自愈**
  - 场景：网站没变但书源规则失效
  - 流程：体检失败 → 取 HTML 片段给 LLM 出修正规则 → **回归验证通过才写回**
  - 铁律：只改失败的那一段，绝不自由发挥
  - 地基：上游自带 `ws://127.0.0.1:1235/bookSourceDebug` 调试通道

- [ ] **D3 AI 按标题自动找封面**

- [ ] **D4 AI 分章 + 长章节分段命名**
  - 用户原话：「大长章节分章要是选用的话就一定要分合适的名字」「可以加入 ai 分章」
  - 现状：分段名是 `分段_3` 这种，没有语义

- [x] **D5 分章规则包罗万象版**（2026-10-05 提出，2026-10-06 交付）
  - 结果：230 本本地 txt 覆盖 **69.1% → 76.5%**；劫持有正经标题的书 **0 本**；内置规则 28 → 13 条
  - 方法：`project/scripts/toc_regression.py` 离线回归（照抄 app 两层语义）+ 停用规则逐条启用对比
  - 新增能力：`TxtTocRule.isFallback` 兜底语义（DB 110）+ 批量「对所有本地 TXT 重新分章」
  - 剩余 54 本（42 无规则 + 12 单章）**确认无章节结构**（两次独立扫描）→ 转 D4 AI 分章
  - 装机步骤：目录规则页 → 全选删除（清空规则表）→ ⋮ → 对所有本地 TXT 重新分章
  - 详见 `HANDOFF.md` 的「2026-10-06 第二轮」

### 工程

- [ ] **E1 release 版构建**
  - 现状 debug 77 MB（`classes*.dex` 未压缩 160 MB）
  - 开 R8 + ABI 拆分后预计 20-30 MB

- [ ] **E2 缺存储权限时主动申请**
  - 今天踩的坑：恢复备份后本地书全打不开，因为 SAF 授权不能跨 app 转移
  - 目标：打开本地书发现无权限时弹申请，而不是直接报错

---

## 待用户决定

- [?] 书源体检方案：**应用 / 只冻结响应>8s那批 / 先不动**
---

## 设计硬约束（用户反馈，已固化为 DESIGN.md）

- [x] **用户明确指出的原型问题**（第一版原型）
  - 黑色背景特别丑 → 改暖色（明色默认米纸；深色用暖褐或深蓝，**不用纯黑**）
    - 用户后续补充：「深色模式用深蓝啥的也不是不行」→ 深蓝与暖褐都接受
  - 统计卡下的说明句一股 AI 味 → **界面不出现句子**，只用名词+数字
  - 「响应慢 / 同站同名重复」这类并列短语、以及「徽章」等说法 → 去掉，改短词
  - 要匠心独运的设计师感 → 靠留白/字阶/对齐/克制用色
  - **切到任何一页不能像换了个 app** → 所有页面共用 DESIGN.md 的 token

---

## A1 方向调整（2026-10-05，用户反馈后）

### 用户的原话与判断
> 「书源操作我感觉暂时用不上，你不感觉**应该是你来帮我筛选**吗？我自己筛选特别蠢」
> 「可以全部改成一个**待选书源清单**，上面的冻结啥的全部无所谓，**只留一个常用和失效**就行，
>   待修冻结感觉没必要，我只要加一个 **AI 修复**功能」
> 「你来帮我把目前**重复的书源只保留一个能用的**就行」

**产品判断（认同）**：让机器在电脑侧用全量数据做筛选，别让人在手机上做批量分诊。
手机 UI 只做「看结果 + 单条深操作」。

### 已完成（2026-10-05 装机验证）

三标签、星标写入与跨进程持久、刷新只扫常用、失败结果落失效列表（带原因）、取消星标即时退出失效、
三种空态、无常用时的空集提示、「校验设置」入口。详见 `HANDOFF.md`。

已删除：`SourceHealth.kt`、`SourceHealthStrip.kt`、五级判定与预计算、多选模式、11 项批量操作条。
（所以下面「保留不动」那段里的统计条与行状态色，本轮已按 D8/D9 删除，不要再用它当参考）

### 还没做

- [ ] **去重的 12680 条入库**：JSON 在 `/mnt/c/Users/mikujoker/legado-work/bookSource.deduped.json`，
      入库方式未定（本地导入 vs Web API `/saveBookSources`）
- [ ] **验证去重留下的那条真能用**：靠 app 自带的书源校验跑一遍（现在能按需扫，但只扫常用）
- [x] **列表分页**（2026-10-05 完成）：新建 `(customOrder)` 与 `(isFavorite, customOrder)` 索引
      （迁移 108→109），「全部 + 手动排序」走 `limit` 分页（滑到底续页），筛选/搜索/排序全部下推到 SQL。
      实测：打开只取 200 条、第 397-402 行已能滑到（说明续页生效）、全表搜索仍能命中第 20001 行。
      未做：其他排序与「按域名分组显示」仍旧整表读（没变快也没变慢）
- [x] **批量标「常用」**（2026-10-05 完成）：长按进多选，底栏 = 全选/反选/删除(主) + ⋮(设为常用·取消常用·添加分组)。
      启用·禁用·发现开关·置顶·导出那套没有加回来（用户明确不要）
### 已废弃

- ~~浮动批量操作条~~（与 app 原有底部条重叠，用户也认为批量操作暂时用不上）
- ~~预计算健康度 / 落盘缓存~~（见 DECISIONS D8、D9）
- ~~五级健康度（在用/待观察/冷冻/待修/坏源）~~（见 DECISIONS D9）
5. ⏳ **OPDS 源支持**（用户 2026-10-06 新增）：能添加 OPDS 目录源（Atom/XML 解析 + 可选 Basic Auth），浏览目录、搜索、下载 epub/txt → 导入本地书；范围/入口待用户确认后细化

### OPDS 实现要点（用户已确认范围：浏览+搜索+下载导入）
- **现状**：仓库里没有任何 OPDS/Atom 支持（`grep opds|atom+xml` 为空）；`BookSourceType` 现有 0 文本 / 1 音频 / 2 图片 / 3 file（只提供下载服务的网站）
- **接入方式（计划）**：做成"服务地址 + 可选 Basic Auth"的独立入口（不进书源列表，避免污染），实现：
  1. `OpdsCatalog`（Atom/XML 解析：`<entry><title><link rel="http://opds-spec.org/acquisition" href type>`；分页 `rel="next"`）
  2. `OpdsSource` 实体（id/名称/URL/账号/密码）+ DAO + 管理页
  3. 浏览页：进入 catalog → 列表（子目录/条目 + 封面 `image` link）；搜索：`{searchTerms}` 模板
  4. 下载：`acquisition` link → 下到 `Download/legado/novel/` → **复用 `ui/book/import/local/ImportBook*.kt` 的既有导入流程**进书架
- **复用点**：本地导入 `ui/book/import/local/{ImportBook,ImportBookScreen,ImportBookViewModel}.kt`；书源管理页 `ui/book/source/manage/BookSourceScreen.kt` 作为 UI 参考
- **验证（不靠截图）**：加 `POST /opdsBrowse {url,query}` 这类接口回 JSON（条目/链接数），端上用 `adb` 触发后读接口

### OPDS 进度（2026-10-06 晚）
- ✅ **后端端到端验证通过 5/5**（设备上）：保存源 → 列源 → Gutenberg 服务端搜索(27 条) → 进入书级 feed
  (`/ebooks/11.opds` 解析出 epub 直链) → 下载(136KB) → 导入书架 → 回读确认；脚本 `legado-work/verify_opds.py`
- ✅ 解析层单测 **10/10**（含真实 Gutenberg 两种形态：kind=acquisition 书级 feed / rel=subsection 无 kind）
- ✅ 实体 + DAO + `AutoMigration(110→111)` + Koin（DAO/Repository/ViewModel）
- ✅ HTTP 接口 5 条：`/opdsSources` `/opdsSaveSource` `/opdsDeleteSource` `/opdsBrowse` `/opdsDownload`
- ✅ **UI 已写并接入导航**：`ui/opds/OpdsScreen.kt`（源列表/添加/删除 + 浏览/搜索/下载/下一页/面包屑）、
  `OpdsViewModel.kt`；路由 `MainRouteOpds` + `ROUTE_OPDS="opds"` + `MainNavGraph` entry + `MainIntent.createOpdsIntent`
  → 可用 `am start ... --es startRoute opds` 打开（无截图验证入口）
- ⏳ 待补：把入口放进「我的」页（挨着"书源管理"）；UI 的**视觉验证**需解锁手机

### EPUB 无损渲染：v1 UI 接入**已回退**（2026-10-06 深夜）
**真机实测结论（有截图/日志证据）**
- 取数层本身成立：`EpubWebDocument.chapterHtml()` 走通，WebView 里 `empty:false`（文档确实加载了），
  章节 href 与压缩包真实条目一致（如 `cover.xhtml` / `1/OEBPS/info.xhtml`）
- 失败的三个现象：① 页面被放大数倍（几个字占满屏）② 拖不动 / 不能缩放 ③ 菜单打不开（WebView 吃掉点击）
- 改过一轮（`useWideViewPort=false` 关 overview、`svg/image` 强制按宽、开原生双指缩放、点击经 JS
  `EpubHook.onTap` 回报）→ 复看变成**空白页**，说明"把 WebView 覆盖在自绘画布之上"这个接法不成立
- CDP 也走不通：`Runtime.enable/Page.enable/DOM.getDocument/Runtime.evaluate` 全部超时（协议层活着但
  renderer 不服务命令），所以只能靠截图 + logcat

**下一步（换方案，别再覆盖）**
1. 给 epub 做**独立阅读页**（WebView 独占内容区），不要和自绘画布叠加
2. 手势归属要先解决：WebView 一旦拿到触摸，阅读器的点击区/翻页就全废 →
   要么由 WebView 全权负责（JS `onTap/onScroll` 回报给 Kotlin：中=菜单、左/右=翻章），
   要么在 WebView 上层放一个**只处理点击**的透明层（不消费拖动）
3. 缩放/字号基准：明确 1 CSS px 与 sp 的换算（`useWideViewPort/loadWithOverviewMode` + `textZoom`）
4. 保留物：`model/localBook/EpubWebDocument.kt`（单测 5/7；两个失败：`Uri.encode` 不能 JVM 跑、
   `mimeOf` 缺 `epub` 映射）、`feature/reader/EpubWebContent.kt`
