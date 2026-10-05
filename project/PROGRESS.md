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
