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
- **All apps** grid (hold OK to reorder), **inputs** (HDMI via `TvInputManager`).
- **Quick settings** (Menu key): Ambilight on/off, mode, brightness, sleep timer, standby — through the TV's
  JointSpace API on `127.0.0.1:1926` (pair once with `scripts/philips-pair.py`, put the printed
  `jointspace.user` / `jointspace.pass` into `local.properties` and run `scripts/push-secrets.sh`).
- **Screensaver** (`TvosDream`): Aerial-style catalog backdrops with a drifting clock; OK opens the title.

### Build & install

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.arshe.tvos

# per-TV secrets stay on the TV, never in the APK: put youtube.apiKey / jointspace.user /
# jointspace.pass into local.properties, then
scripts/push-secrets.sh <tv-ip>

# media sessions → "Continue watching"
adb shell cmd notification allow_listener com.arshe.tvos/com.arshe.tvos.data.NowPlayingListener

# make it the home screen (Android 11 always prefers the priority=2 stock launcher, so disable it)
adb shell cmd package set-home-activity com.arshe.tvos/.MainActivity
adb shell pm disable-user --user 0 com.google.android.tvlauncher
# rollback: adb shell pm enable com.google.android.tvlauncher

# screensaver after 10 minutes idle
adb shell settings put secure screensaver_components com.arshe.tvos/com.arshe.tvos.ui.TvosDream
adb shell settings put system screen_off_timeout 600000
```

## Updates

The launcher checks `github.com/<update.repo>` releases (tag `v<versionCode>`, `.apk` asset) at
start and every 6 hours, downloads newer builds and installs them through its local ADB link
while another app is on screen, then AOT-compiles itself. Publish with `scripts/release.sh "notes"`
(needs `gh auth login`). Release APKs contain no secrets. For a private repo put a read-only
fine-grained token into `local.properties` as `github.token`.

## Scripts

- `scripts/tv-metrics.sh <ip>`: snapshot of memory, CPU, packages and services into `reports/`.
- `scripts/release.sh "notes"`: bump the version, build and publish a GitHub release for OTA.
- `scripts/philips-pair.py <ip> request|grant <PIN>`: JointSpace pairing for the quick settings.
- `scripts/debloat.sh <ip> [--apply] [--tier2]`: removes bloat per user with `pm uninstall --user 0` (reversible). Without `--apply` it is a dry run.

## Docs

- `docs/launcher-design-brief.md`: the design brief the launcher was drawn from.
