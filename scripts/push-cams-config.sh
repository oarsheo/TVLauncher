#!/usr/bin/env bash
# Copy the NVR access (nvr.* from local.properties) to the camera box; it isn't baked into the APK.
# Usage: scripts/push-cams-config.sh <ip> [box|tv]
#   box: starts on boot and is the home screen;  tv: just an app next to the launcher
set -euo pipefail
cd "$(dirname "$0")/.."
IP="${1:?usage: $0 <ip> [box|tv]}"
MODE="${2:-box}"
[ "$MODE" = box ] && ON=true || ON=false
TMP=$(mktemp)
grep -E '^nvr\.' local.properties | sed 's/^nvr\.//' > "$TMP"
printf "autostart=%s\nhome=%s\n" "$ON" "$ON" >> "$TMP"
adb -s "$IP:5555" push "$TMP" /sdcard/Android/data/com.arshe.cams/files/nvr.properties >/dev/null
rm -f "$TMP"
adb -s "$IP:5555" shell am force-stop com.arshe.cams
echo "config pushed"
