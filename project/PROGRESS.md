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
