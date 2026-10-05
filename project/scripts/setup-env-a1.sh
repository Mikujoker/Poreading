#!/bin/bash
set -uo pipefail
TOOL="$HOME/toolchain"; SDK="$HOME/android-sdk"
echo "### [A1] Temurin JDK 21 → $TOOL/jdk21"
if [ ! -x "$TOOL/jdk21/bin/java" ]; then
  curl -fL --retry 3 -o "$TOOL/jdk21.tar.gz" -w "  JDK HTTP=%{http_code} bytes=%{size_download}\n" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" || { echo "  !! JDK 下载失败"; exit 1; }
  rm -rf "$TOOL/jdk21"; mkdir -p "$TOOL/jdk21"
  tar -xzf "$TOOL/jdk21.tar.gz" -C "$TOOL/jdk21" --strip-components=1 && rm -f "$TOOL/jdk21.tar.gz"
fi
"$TOOL/jdk21/bin/java" -version 2>&1 | head -3

echo "### [A2] Android command-line tools → $SDK/cmdline-tools/latest"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 3 -o "$TOOL/cmdline-tools.zip" -w "  CLT HTTP=%{http_code} bytes=%{size_download}\n" \
    "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip" || { echo "  !! CLT 下载失败"; exit 1; }
  rm -rf "$SDK/cmdline-tools"; mkdir -p "$SDK/cmdline-tools"
  unzip -q "$TOOL/cmdline-tools.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$TOOL/cmdline-tools.zip"
fi
ls "$SDK/cmdline-tools/latest/bin/" | head

cat > "$HOME/.android-env.sh" <<'ENVEOF'
export JAVA_HOME="$HOME/toolchain/jdk21"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export GRADLE_USER_HOME="$HOME/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
ENVEOF
echo "### A1+A2 完成，环境文件: ~/.android-env.sh"
du -sh "$TOOL" "$SDK"
