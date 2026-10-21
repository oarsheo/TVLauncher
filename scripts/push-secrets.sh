#!/usr/bin/env bash
# Copy the per-TV secrets from local.properties to the TV (they are not baked into the APK).
# Merges into the TV's existing file: each TV keeps its own JointSpace pairing; local.properties'
# pairing is only used where the TV has none yet.
# Usage: scripts/push-secrets.sh <tv-ip>
set -euo pipefail
cd "$(dirname "$0")/.."
IP="${1:?usage: $0 <tv-ip>}"
REMOTE=/sdcard/Android/data/com.arshe.tvos/files/secrets.properties
OLD=$(mktemp); NEW=$(mktemp)
adb -s "$IP:5555" shell "cat $REMOTE 2>/dev/null" | tr -d '\r' > "$OLD" || true
grep -E '^youtube\.apiKey=' local.properties > "$NEW" || true
grep -E '^nvr\.deye' local.properties | sed 's/^nvr\.//' >> "$NEW" || true # house inverter, optional
if grep -q '^jointspace\.user=' "$OLD"; then
  grep -E '^jointspace\.(user|pass)=' "$OLD" >> "$NEW"
else
  grep -E '^jointspace\.(user|pass)=' local.properties >> "$NEW" || true
fi
adb -s "$IP:5555" push "$NEW" "$REMOTE" >/dev/null
rm -f "$OLD" "$NEW"
adb -s "$IP:5555" shell am force-stop com.arshe.tvos
echo "secrets pushed; launcher restarted"
