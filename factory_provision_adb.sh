#!/usr/bin/env bash
#
# Factory provisioning helper (best-effort) for preinstall at scale.
# This script is intended for controlled factory/QA environments.
#
# What it does:
# - Installs (or updates) an APK
# - Launches the app once (so it can unpack/bootstrap)
# - (Optional) kicks installer activity via deep link/intent if you implement it
#
# NOTE:
# - Many permissions (battery optimization whitelist, etc.) cannot be auto-granted
#   on non-root production devices unless the app is privileged/system.
# - Treat this as a template; adjust package/activity names and intents.
#
# Usage:
#   ./factory_provision_adb.sh /path/to/app.apk app.package.name app.package.name/.MainActivity [device_token] [mode]
#

set -euo pipefail

APK_PATH="${1:-}"
PKG="${2:-}"
LAUNCH_ACTIVITY="${3:-}"
DEVICE_TOKEN="${4:-}"
MODE="${5:-}" # auto|deepseek|kimi|claude (optional)

if [ -z "$APK_PATH" ] || [ -z "$PKG" ] || [ -z "$LAUNCH_ACTIVITY" ]; then
  echo "usage: $0 <apk_path> <package> <launch_activity> [device_token] [mode]"
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found"
  exit 1
fi

echo "[1/6] wait for device..."
adb wait-for-device

echo "[2/6] install apk..."
adb install -r "$APK_PATH"

echo "[3/6] launch once..."
adb shell am start -n "$LAUNCH_ACTIVITY" >/dev/null

echo "[4/8] provision token/mode (best-effort)..."
if [ -n "$DEVICE_TOKEN" ]; then
  # Prefer system property in ROM/factory (requires root / privileged context).
  adb shell "setprop persist.oyster.device_token '$DEVICE_TOKEN'" 2>/dev/null || true
  if [ -n "$MODE" ]; then
    adb shell "setprop persist.oyster.mode '$MODE'" 2>/dev/null || true
  fi

  # Debug fallback: write app-private file via run-as (only works on debuggable builds).
  adb shell "run-as '$PKG' sh -c 'echo \"$DEVICE_TOKEN\" > files/device_token.txt && chmod 600 files/device_token.txt'" 2>/dev/null || true
fi

echo "[5/8] allow notifications (best-effort; Android 13+ uses runtime prompt)..."
# Some OEMs ignore; safe to attempt.
adb shell cmd notification allow_listener "$PKG" 2>/dev/null || true

echo "[6/8] attempt to disable battery optimizations (may fail on non-privileged apps)..."
adb shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true

echo "[7/8] relaunch app to trigger preinstall flow..."
adb shell am start -n "$LAUNCH_ACTIVITY" >/dev/null

echo "[8/8] done."
