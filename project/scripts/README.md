# 脚本

## 环境与构建

- `setup-env-a1.sh` — 装 JDK 21 + Android cmdline-tools（清华/dl.google 直连镜像）
- `build-debug.sh` — 构建 debug APK（含 unset 代理 + 8 并行）

## 数据修复（一次性，已执行完，保留以备复现）

- `fix-local-book-paths.py` — **281 本本地书 bookUrl：SAF URI → 裸路径**
  - 走新 app 的 Web API（`/saveBook` + `/deleteBook`）
  - 前置：`adb forward tcp:1122 tcp:1122`
  - 结果：新建 280 / 删旧 281 / 零失败

## 设备端脚本（一次性，已执行完，未入库）

原来的那几个是一次性脚本，跑完就清了。要复现的话照下面这个套路重生成：
**必须用设备端脚本文件**（`adb push` 到 `/data/local/tmp/` 再 `adb shell sh`），
不要用内联命令 —— 书名里全是 U+FFFD 加生 GBK 字节，内联的引号逃逸会把这串弄坏。

当时做了什么：

| 脚本 | 作用 |
|---|---|
| `do-dup.sh` | 重复文件隔离：95 个移到 `/sdcard/legado-trash/dups-20261005/` |
| `verify-refs.sh` | 校验书架引用的 281 个路径是否都存在（结果 277 存在 / 4 是历史坏引用） |
| `md5all.sh` | 批量 md5，用来把「同尺寸」收敛成「真重复」 |
| `probe.sh` / `probe2.sh` | 抽每个文件开头 700 字节（`od -An -tx1`）用来猜书名 |

生成套路：Python 读路径清单 → 逐条写 `q = "'" + p.replace("'", "'\\''") + "'"` →
拼成 `#!/system/bin/sh` 脚本 → `adb push` → `adb shell sh`。

## 产物对应关系

| 脚本 | 产出 |
|---|---|
| `fix-local-book-paths.py` | 直接改 app 数据，回滚表见 `artifacts/rollback-local-books.csv` |
