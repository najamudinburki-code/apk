#!/usr/bin/env bash
set -euo pipefail
if command -v adb >/dev/null 2>&1; then
  sh_adb="$(command -v adb)"
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  sh_adb="$ANDROID_HOME/platform-tools/adb"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
  sh_adb="$HOME/Library/Android/sdk/platform-tools/adb"
elif [[ -x "$HOME/Android/Sdk/platform-tools/adb" ]]; then
  sh_adb="$HOME/Android/Sdk/platform-tools/adb"
else
  echo 'Install Android SDK Platform-Tools or set ANDROID_HOME to your SDK folder.' >&2
  exit 1
fi
"$sh_adb" reverse tcp:3000 tcp:3000
"$sh_adb" reverse tcp:5173 tcp:5173
echo 'Select the dev build variant in Android Studio and click Run.'
echo 'Optional phone browser dashboard: http://localhost:5173'
