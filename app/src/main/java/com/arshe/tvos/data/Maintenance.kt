package com.arshe.tvos.data

import android.content.Context
import androidx.core.content.edit

/**
 * Daily housekeeping through the local ADB link, run while the TV sits idle (screensaver):
 * re-AOT-compile apps that fell back to profile/JIT mode (Play Store updates do that), trim
 * the eMMC and drop app caches when storage runs low. Output goes to
 * /data/local/tmp/tvos-maint.log.
 */
object Maintenance {
    private val APPS = listOf(
        PKG_NETFLIX, PKG_YOUTUBE, PKG_MEGOGO, PKG_APPLETV,
        "com.nordvpn.android", "com.google.android.inputmethod.latin",
    )

    // Only apps whose code is no longer fully compiled; a no-op on most days.
    /** Too big to compile fully on a 5 GB /data; profile-guided is enough and they update often. */
    private val PROFILE_APPS = listOf("com.google.android.gms", "com.android.vending")

    // (The launcher itself is left out: its bundled baseline profile keeps it in speed-profile.)
    private val SCRIPT = "D=\$(dumpsys package dexopt); for p in ${APPS.joinToString(" ")}; do " +
        "pm path \$p >/dev/null 2>&1 || continue; " +
        "case \"\$(echo \"\$D\" | grep -A2 \"\\[\$p\\]\" | grep -m1 -o \"status=[a-z-]*\")\" in " +
        "status=speed) ;; *) echo \"compile \$p\"; cmd package compile -m speed -f \$p;; esac; done; " +
        "for p in ${PROFILE_APPS.joinToString(" ")}; do " +
        "case \"\$(echo \"\$D\" | grep -A2 \"\\[\$p\\]\" | grep -m1 -o \"status=[a-z-]*\")\" in " +
        "status=speed|status=speed-profile) ;; *) echo \"profile-compile \$p\"; cmd package compile -m speed-profile -f \$p;; esac; done; " +
        "sm fstrim; pm trim-caches 1500M; echo done"

    private const val PREFS = "maintenance"
    private const val LAST = "last"

    fun due(context: Context, hours: Int): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(LAST, 0) > hours * 3_600_000L

    suspend fun run(context: Context) {
        prefs(context).edit { putLong(LAST, System.currentTimeMillis()) }
        LocalAdb.shell(context, "setsid -d sh -c '$SCRIPT' </dev/null >/data/local/tmp/tvos-maint.log 2>&1; sleep 1")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
