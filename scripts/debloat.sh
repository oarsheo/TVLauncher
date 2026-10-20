#!/usr/bin/env bash
# Debloat for Philips 65OLED707/12 (TPM211EA, Android TV 11).
# Uses `pm uninstall --user 0` — reversible with: adb shell cmd package install-existing <pkg>
# Usage: scripts/debloat.sh <tv-ip> [--apply] [--tier2]
set -uo pipefail

IP="${1:?usage: $0 <tv-ip> [--apply] [--tier2]}"; shift
APPLY=0; TIER2=0
for a in "$@"; do
  case "$a" in --apply) APPLY=1 ;; --tier2) TIER2=1 ;; esac
done
A=(adb -s "$IP:5555")

# Tier 1: safe, nothing depends on them
TIER1=(
  com.google.android.play.games
  com.google.android.videos
  com.google.android.youtube.tvmusic
  com.google.android.feedback
  com.google.android.marvin.talkback
  com.android.printspooler
  com.android.providers.calendar
  com.google.android.syncadapters.calendar
  com.phorus.playfi.tv               # DTS Play-Fi, ~31 MB resident
  com.phorus.playfi.softap
  io.mimi.sdk.service                # Mimi hearing personalization
  io.mimi.tvapp
  org.droidtv.candeebug              # Philips debug logger, ~27 MB resident
  org.droidtv.usagelogger
  org.droidtv.tv.frameworklogger
  org.droidtv.demome                 # shop demo mode
  org.droidtv.calmancalibrator
  org.droidtv.android.fitness
  org.droidtv.amazonalexa
  org.droidtv.astrahdplus            # German satellite operator
  org.droidtv.m7opapp                # M7 satellite operator
  org.droidtv.sunrise                # Swiss operator
  org.droidtv.freeviewplayers        # UK Freeview
  uk.co.freeview.amc_catchup uk.co.freeview.amc_horror uk.co.freeview.bbc
  uk.co.freeview.bbc_sounds uk.co.freeview.ch4_vod uk.co.freeview.ch5
  uk.co.freeview.explore uk.co.freeview.fvpconfigauth uk.co.freeview.itv
  uk.co.freeview.mdsclient uk.co.freeview.onnow uk.co.freeview.sony_kids_vod
  uk.co.freeview.stv uk.co.freeview.tifbridge uk.co.freeview.uktv
)

# Tier 2: only after a custom launcher is installed and set as HOME
TIER2_PKGS=(
  com.google.android.tvlauncher
  com.google.android.tvrecommendations
  org.droidtv.leanbackcustomizer
  org.droidtv.recommendation
  org.droidtv.nettvrecommender
  com.google.android.backdrop
  com.android.dreams.basic           # replaced by the launcher's own screensaver
  org.droidtv.nettv.market           # Philips App Gallery
  org.droidtv.nettvapps
  org.droidtv.nettv_launcherapp
  org.droidtv.eum                    # built-in e-manual
  org.droidtv.dropboxprovider
  com.google.android.apps.mediashell # Chromecast built-in (skip if you cast from a phone)
)

PKGS=("${TIER1[@]}")
[ "$TIER2" = 1 ] && PKGS+=("${TIER2_PKGS[@]}")

installed=$("${A[@]}" shell pm list packages | sed 's/package://' | tr -d '\r')
for p in "${PKGS[@]}"; do
  grep -qx "$p" <<<"$installed" || { echo "skip  $p (not installed)"; continue; }
  if [ "$APPLY" = 1 ]; then
    printf '%-45s ' "$p"; "${A[@]}" shell pm uninstall --user 0 "$p"
  else
    echo "would remove  $p"
  fi
done
[ "$APPLY" = 1 ] || echo "dry run — add --apply to execute"
