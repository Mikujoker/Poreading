# 下一轮会话的启动指令

把下面整段贴给新 agent 即可。

---

## 贴给新 agent 的内容

我在做一个基于 `legado-with-MD3` 的个人定制版「阅读」，仓库在 WSL 里的
`~/work/legado-md3`（Windows 侧看不到，要用 bash 读写；工具里的 read/edit 读不到 WSL 路径）。

**先读这三个**（都在 `~/work/legado-md3/project/`）：
- `HANDOFF.md` —— 环境、构建/装机/截图手法、**踩过的坑清单**（务必先看完再动手）
- `TODO.md` —— 需求清单与当前优先级
- `DECISIONS.md` —— 已定死的决策（含数据层口径）

**环境（坑都踩过了，别重走）**
- 编译：`~/build3.sh`（增量 30 秒~1 分钟；release 首次要 13 分钟）
- 装机：`cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/v.apk`
  然后 `adb install -r "C:/Users/mikujoker/legado-work/v.apk"`（adb 是 Windows 程序，看不懂 WSL 路径）
- 差分手法：`adb exec-out screencap -p > /mnt/c/...png`，再用 read 工具看图
- **看手机上看包名 `io.legato.kazusa.debug` 那个**（应用名带 D）；`io.legato.kazusa` 是我临时装的 release，没数据
- **已知会烦你的**：① `adb kill-server && adb start-server` 要常备（daemon 会自己掉）
  ② `adb shell input tap` 在 Compose 上**会偶发丢点击**，复测要用 `input swipe x y x y 120`
  ③ 别从截图目测坐标，用 `uiautomator dump` 拿真实 bounds（目测会偏 50~80px）
  ④ 判崩溃只看 `grep 'Process: io.legato.kazusa'`
  ⑤ 构建/下载一律 `unset http_proxy https_proxy`
- 书架分组的标签在 **y=616**（轻小说 158 / 小说 451 / 刘备 719 / 漫画 987 / 全部 1206）

## 当前状态（2026-10-06）

**已完成并装机**：书源管理三标签+星标+批量+分页 · 阅读页「米纸·宋」预设（暖灰纸 + 宋体 + 近黑字）
+ 夜间两条 + 纸纹 · 书架默认封面中式书封（米粉底 + 青绿山水 + 红日霞光 + 志莽行书 + 朱红印章）
· 书名/文件名的 C2 改名（含 4 处收尾修复）· B2 动效 token 层 + 5 批替换 · 启动定位

**启动结论（别再查了）**：debug 冷启动 3.0s、**release 413ms** —— 慢的是 debug 包本身
（无 R8/baseline profile），代码侧不需要优化启动。各阶段 release 比 debug 快 3~10 倍。

**B2 剩下的是渲染问题，不是动画问题**：书架切分组实测 412 帧 / 卡顿 2.91% / 99 分位 77ms，
系统点名 **Slow UI thread 12 次** —— 主线程在切换时做「组数据 + 整屏条目合成 + 封面解码」。
下一步应查封面加载与条目合成（限邻页预组合 / 封面先占位后解码 / 预热邻组封面），不要再动动画时长。

## 建议下一个任务（挑一个）

1. **书架切分组的渲染优化**（承上：把 Slow UI thread 那 12 帧降下来）
2. **D2 AI 爬站修源**（用户已给 DeepSeek key；铁律：只改失败的那一段、回归验证通过才写回、
   key 存设置页加密不硬编码）
3. **D5 目录规则包罗万象版**（用户点名；做法：写规则包 + 拿 281 本本地书跑回归证明分章正确）
4. 阅读页翻页的「跟手」（要动 pager 层）

## 硬约束

- `project/DESIGN.md` 是硬约束（暖色、不出现句子、不用文字胶囊、动效 ≤300ms 用 ease-out）
- 改数据层前先看 `HANDOFF.md` 里「书 URL 迁移」那节（外键 + 列名两个坑，都踩过）
- 装完机一定要自己截图看效果再说话
