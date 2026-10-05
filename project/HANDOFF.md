# 交接：下个会话从这里开始

## 现状一句话

「书源管理」的**五级健康度 + 统计条 + 状态色行样式 + AI 修复入口**已实现并**装机验证**；
最后一步「健康度落盘缓存」已写完、**编译通过**，但**还没在真机验证**（用户拔了手机去吃饭）。

## 第一步：验证缓存（手机插上后立刻做）

```bash
# 1) 装最新包
export PATH="$HOME/.local/bin:$PATH"
cp ~/work/legado-md3/app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk
adb install -r "C:/Users/mikujoker/legado-work/v.apk"
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n io.legato.kazusa.debug/io.legado.app.ui.main.MainActivity
# 2) 导航：我的(1140,2690) → 书源管理(500,1154)，进两次
```

判定标准：
1. **第一次**进：统计条慢（要全表扫描），算完写入缓存
2. **第二次**进：统计条应**第一帧就有数字**，不再等 5 秒
3. 缓存文件存在且约 1 MB：
   `adb shell run-as io.legato.kazusa.debug ls -l cache/source_health_cache.json`
4. 随便拨一条书源的开关再重开：指纹应失效 → 重算一次（数字不该是脏的）
5. 全程 `FATAL EXCEPTION` 计数为 0

## 性能真相（已实测，别再猜）

| 查询 | PC SSD | 说明 |
|---|---|---|
| `book_sources_part` 视图（列表在用） | **5233 ms** | |
| 规则快照查询 | 4063 ms | |
| 只读 3 个小列（对照） | 2609 ms | 读小列也要扫全表 |

原因：`book_sources` 是 **24528 行 / 119 MB**，规则以 JSON 文本存储；SQLite 行式存储，
读一个小列也要把整页读进来。手机闪存比 SSD 慢数倍。

> **缓存救的是统计条和行色；救不了列表本身那次 `flowAll()`。**
> 要让页面真正秒开，必须在下面二选一：
> - **分页**：`limit/offset`，并把筛选/排序下推到 SQL（改动中等、风险低）
> - **派生小表**（TODO 方案 A）：新建约 100 KB 小表只存判定所需字段；需 DB 迁移（高风险，先备份）

## 等用户拍板的两件事

1. **浮动批量操作条**：按原型加的底部文字条与 app 原有的底部图标条**叠在一起了**（截图 `bar2.png`）。
   我暂时撤掉了自己那条。要么改共享组件 `RuleListScaffold` / `ListScaffold` 把原有的换成文字条，
   要么沿用原有图标条。**别两条并存。**
2. **书源体检方案是否应用**（TODO 的 A2）：建议先只冻结「响应 > 8s」那批。

## 本轮已装机验证的成果

| 项 | 证据 |
|---|---|
| 统计条（五级 + 计数 + 点击筛选） | 截图显示 `4.7k / 1.8w / 1k / 631 / 313` |
| 行样式（状态色底 + 左侧色块） | 截图：在用=绿、待修/冷冻=紫 |
| 菜单「AI 修复」入口 | 截图：置顶/置底/搜索/调试/**AI 修复**/删除 |
| 统计条点击筛选 | 截图：点「待观察」后列表切到禁用源 |
| 「未测 ≠ 慢」分类修复 | 冷冻 3.5k → 1k、在用 2.2k → 4.7k |

## 环境与验证手法（坑都踩过了）

- 编译：`~/build3.sh`（增量 13~40 秒），产物 `app/build/outputs/apk/app/debug/`
- `adb` 是 **Windows 程序**（WSL 里包装成 `~/.local/bin/adb`），**看不懂 WSL 路径**，push/pull 要走 `/mnt/c` 中转
- 截图：`adb exec-out screencap -p > /mnt/c/...png`，再用 read 工具看
- 手机休眠会让 `input tap` 失效 → 先 `input keyevent KEYCODE_WAKEUP`
- ColorOS **封了 `appops set`**，权限只能手点
- SAF 授权**不能跨 app 转移**；本地书 bookUrl 已批量改成裸路径（见下）
- 构建/下载一律 `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY`（WSL 里设了代理，
  连国内镜像也走代理会慢十倍；GitHub 用 `gh-proxy.com`）
- 用 Web API 测本地书正文时**必须先 `refreshToc` 建章节表**，否则 `getBookContent` 会假失败
