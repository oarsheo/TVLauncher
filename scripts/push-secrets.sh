#!/usr/bin/env bash
# Copy the per-TV secrets from local.properties to the TV (they are not baked into the APK).
# Usage: scripts/push-secrets.sh <tv-ip>
set -euo pipefail
cd "$(dirname "$0")/.."
IP="${1:?usage: $0 <tv-ip>}"
TMP=$(mktemp)
grep -E '^(youtube\.apiKey|jointspace\.user|jointspace\.pass)=' local.properties > "$TMP"
grep -E '^nvr\.deye' local.properties | sed 's/^nvr\.//' >> "$TMP" || true # house inverter, optional
adb -s "$IP:5555" push "$TMP" /sdcard/Android/data/com.arshe.tvos/files/secrets.properties >/dev/null
rm -f "$TMP"
adb -s "$IP:5555" shell am force-stop com.arshe.tvos
echo "secrets pushed; launcher restarted"
