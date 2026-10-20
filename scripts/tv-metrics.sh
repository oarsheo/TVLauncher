#!/usr/bin/env bash
# Collects a performance snapshot from the TV over adb.
# Usage: scripts/tv-metrics.sh <tv-ip>
set -euo pipefail

IP="${1:?usage: $0 <tv-ip>}"
OUT="reports/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

adb connect "$IP:5555" >/dev/null
A=(adb -s "$IP:5555")
sh_() { "${A[@]}" shell "$@" 2>&1; }

sh_ getprop                                  > "$OUT/getprop.txt"
sh_ cat /proc/meminfo                        > "$OUT/meminfo.txt"
sh_ cat /proc/cpuinfo                        > "$OUT/cpuinfo.txt"
sh_ dumpsys meminfo                          > "$OUT/dumpsys-meminfo.txt"
sh_ dumpsys cpuinfo                          > "$OUT/dumpsys-cpuinfo.txt"
sh_ top -b -n 1 -m 40                        > "$OUT/top.txt"
sh_ df -h                                    > "$OUT/df.txt"
sh_ dumpsys diskstats                        > "$OUT/diskstats.txt"
sh_ pm list packages -f -U                   > "$OUT/packages-all.txt"
sh_ pm list packages -s                      > "$OUT/packages-system.txt"
sh_ pm list packages -3                      > "$OUT/packages-user.txt"
sh_ pm list packages -d                      > "$OUT/packages-disabled.txt"
sh_ dumpsys activity services                > "$OUT/services.txt"
sh_ dumpsys activity processes               > "$OUT/processes.txt"
sh_ dumpsys package preferred-activities     > "$OUT/preferred-activities.txt"
sh_ cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN > "$OUT/home-resolver.txt"
sh_ dumpsys gfxinfo                          > "$OUT/gfxinfo.txt"
sh_ dumpsys procstats --hours 24             > "$OUT/procstats-24h.txt"
sh_ dumpsys batterystats --checkin           > "$OUT/batterystats.txt" || true
sh_ settings list global                     > "$OUT/settings-global.txt"
sh_ settings list secure                     > "$OUT/settings-secure.txt"
sh_ cat /proc/pressure/memory                > "$OUT/psi-memory.txt" || true
sh_ cat /proc/pressure/cpu                   > "$OUT/psi-cpu.txt" || true
sh_ cat /proc/pressure/io                    > "$OUT/psi-io.txt" || true
sh_ cat /sys/block/mmcblk0/stat              > "$OUT/emmc-stat.txt" || true
sh_ cat /sys/class/mmc_host/mmc0/mmc0:0001/life_time > "$OUT/emmc-life.txt" || true
sh_ logcat -d -b main,system,crash -t 5000   > "$OUT/logcat.txt"

echo "saved to $OUT"
