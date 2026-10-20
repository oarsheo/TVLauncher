# TVLauncher

A custom Netflix-style home screen for a Philips 65OLED707 (Android TV 11, MediaTek MT5895), plus the scripts used to profile and debloat the TV over adb.

## Launcher (`app/`)

Kotlin + Jetpack Compose, laid out on a fixed 1920×1080 canvas.

- **Top bar**: Netflix · YouTube · Megogo · Apple TV, plus shortcuts to TV, inputs, all apps and quick settings.
- **Per-app pages**: a full-screen billboard and content rows with deep links into each title.
  - Netflix and Apple TV+: popular in Ukraine, via JustWatch's public GraphQL.
  - Megogo: rows parsed from its public storefront; links open `megogo://video/<id>`.
  - YouTube: Data API charts (trending, music, gaming).
  - "Continue watching": captured from the media sessions of the playing apps.
- **All apps** grid (hold OK to reorder), **inputs** (HDMI via `TvInputManager`), **quick settings** sheet.

### Build & install

```sh
echo "youtube.apiKey=<YOUR_KEY>" >> local.properties   # optional, enables YouTube rows
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.arshe.tvos

# media sessions → "Continue watching"
adb shell cmd notification allow_listener com.arshe.tvos/com.arshe.tvos.data.NowPlayingListener

# make it the home screen (Android 11 always prefers the priority=2 stock launcher, so disable it)
adb shell cmd package set-home-activity com.arshe.tvos/.MainActivity
adb shell pm disable-user --user 0 com.google.android.tvlauncher
# rollback: adb shell pm enable com.google.android.tvlauncher
```

## Scripts

- `scripts/tv-metrics.sh <ip>`: snapshot of memory, CPU, packages and services into `reports/`.
- `scripts/debloat.sh <ip> [--apply] [--tier2]`: removes bloat per user with `pm uninstall --user 0` (reversible). Without `--apply` it is a dry run.

## Docs

- `docs/launcher-design-brief.md`: the design brief the launcher was drawn from.
