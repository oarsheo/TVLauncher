#!/usr/bin/env bash
# Bump versionCode, build the release APK and publish it as GitHub release v<versionCode>.
# The TV picks it up within 6 hours (or via Quick settings → Обновления) and installs it
# while the launcher is in the background.
# Usage: scripts/release.sh "what changed"
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:-}"
GRADLE=app/build.gradle.kts
CUR=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$GRADLE")
NEXT=$((CUR + 1))
sed -i '' -E "s/versionCode = $CUR/versionCode = $NEXT/; s/versionName = \"[^\"]*\"/versionName = \"0.$NEXT\"/" "$GRADLE"

./gradlew -q :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk /tmp/TVLauncher.apk

git add "$GRADLE"
git commit -q -m "Release v$NEXT"
git push -q origin main
gh release create "v$NEXT" /tmp/TVLauncher.apk --title "v$NEXT" --notes "${NOTES:-v$NEXT}"
echo "published v$NEXT"
