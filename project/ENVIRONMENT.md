# 环境（从零复现）

## 位置

| 项 | 路径 |
|---|---|
| JDK 21 (Temurin) | `~/toolchain/jdk21` |
| Android SDK | `~/android-sdk`（cmdline-tools + platform-tools + `platforms/android-37.0` + `build-tools/37.0.0`） |
| Gradle 缓存 | `~/gradle-home` |
| 源码 | `~/work/legado-md3` |
| 环境变量 | `~/.android-env.sh`（已 link 进 `~/.bashrc`） |

占用：全部落在 **D 盘**（WSL vhdx 在 `D:\WSL\Ubuntu`），C 盘不动。

## 工具链版本（上游要求）

```
Gradle  9.8.0      AGP 9.4.1        Kotlin 2.4.20     KSP 2.3.12
Room    2.8.5      Compose BOM 2026.09.00
compileSdk/targetSdk 37   minSdk 26   JDK 21（CI 也是 21）
```

## 关键坑：网络

WSL 里设了 `http_proxy=127.0.0.1:7890`，导致**连国内镜像也走代理**。实测：

| 目标 | 走代理 | 直连 |
|---|---|---|
| dl.google.com | 97 KB/s | **20 MB/s** |
| 清华 TUNA | 23 MB/s | 20 MB/s |
| GitHub 原站 | 0（超时） | 190 KB/s |
| **gh-proxy.com** | — | **3.6 MB/s** |
| 腾讯 Gradle 镜像 | — | **18.7 MB/s** |

**结论**：构建/下载一律 `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY`。

已配置的镜像：
- `settings.gradle` 自带 Aliyun（google / public / gradle-plugin）
- `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 已改为腾讯镜像
- JDK 用清华 `mirrors.tuna.tsinghua.edu.cn/Adoptium/`
- 仓库克隆用 `https://gh-proxy.com/https://github.com/…`

## 安装步骤（新机器）

```bash
# 1) JDK 21
curl -fL -o jdk21.tar.gz \
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
mkdir -p ~/toolchain/jdk21 && tar -xzf jdk21.tar.gz -C ~/toolchain/jdk21 --strip-components=1

# 2) Android cmdline-tools
curl -fL -o clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
mkdir -p ~/android-sdk/cmdline-tools && unzip -q clt.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest

# 3) SDK 组件（注意包名带次版本号！）
export JAVA_HOME=~/toolchain/jdk21 ANDROID_HOME=~/android-sdk
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

## 设备侧注意

- 手机是 ColorOS，**封了 `appops set`**：
  `SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES`
  → 权限只能手点，adb 授不了
- debug 构建可用 `run-as io.legato.kazusa.debug` 读它的私有目录/数据库（`legado.db` 124 MB）
- 设备上用 `od`（不是 `iconv`）来验证文件名字节；`sqlite3` 设备上没有，要 `run-as cat` 拉出来查
- `adb push/pull` 是 **Windows 程序**，看不懂 WSL 的 `/tmp`，要走 `/mnt/c` 中转
