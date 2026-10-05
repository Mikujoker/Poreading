# Kazusa Reader — 个人定制版「阅读」

基于 [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3)（Material Design 3 重构版）
的深度定制分支，目标是做一台**顺手、丝滑、会自己修书的**阅读器。

- 上游 HEAD（克隆时）：`c3e1f7e`，版本 3.26.16
- 包名：`io.legato.kazusa`（debug 构建带 `.debug` 后缀）
- **与原版「阅读」共存安装**，互不影响

## 目标

1. **丝滑**：转场用 spring 物理动画 + 共享元素，做到 iOS/Google 系应用那种跟手感
2. **书源 100% 继承**：不重写规则引擎，复用上游；在这个基础上加 AI 自愈
3. **AI 深度集成**：书源自愈、乱码文件名复原、自动找封面、分章
4. **自己的阅读主题**：不用自带的那些背景，字体/配色/排版全部自定义

## 当前状态

| 项 | 状态 |
|---|---|
| 编译环境 | ✅ WSL(Ubuntu 22.04) + Temurin JDK 21 + Android SDK(platform 37) |
| 构建 | ✅ 冷构建 ~11 分钟，**增量 30~60 秒** |
| 装机 | ✅ 已装到 OnePlus 11，与原版共存 |
| 数据迁移 | ✅ 385 本书 + 24528 书源 |
| 本地书 | ✅ 281 本已修好可读 |

## 构建

```bash
# 环境（详见 project/ENVIRONMENT.md）
source ~/.android-env.sh
cd ~/work/legado-md3
./gradlew assembleAppDebug -PenableAbiSplits=false --max-workers=8 --console=plain

# 产物
app/build/outputs/apk/app/debug/app-app-debug.apk
```

## 装机

```bash
export PATH="$HOME/.local/bin:$PATH"          # WSL 里包装了 Windows 的 adb.exe
cp app/build/outputs/apk/app/debug/app-app-debug.apk /mnt/c/Users/mikujoker/legado-work/
adb install -r "C:/Users/mikujoker/legado-work/app-app-debug.apk"
```

## 目录

- `project/TODO.md` — **需求与待办清单（看这个）**
- `project/PROGRESS.md` — 进度日志
- `project/DECISIONS.md` — 关键决策与理由
- `project/ENVIRONMENT.md` — 环境搭建与新机器复现
- `project/artifacts/` — 计划表 / 回滚清单（CSV）
- `project/scripts/` — 可复现的操作脚本
