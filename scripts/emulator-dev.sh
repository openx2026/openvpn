#!/usr/bin/env bash
# 模拟器调试：把本机 3000 端口映射到模拟器 127.0.0.1:3000
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

if ! command -v adb >/dev/null; then
  echo "adb 未找到，请安装 Android SDK Platform-Tools" >&2
  exit 1
fi

adb reverse tcp:3000 tcp:3000
echo "已设置 adb reverse（模拟器 127.0.0.1:3000 -> 本机 3000）"
adb reverse --list
echo ""
echo "请确保后端已启动: cd openvpn-backend && npm run start:dev"
echo "然后安装 App: ./gradlew :app:installDebug"
