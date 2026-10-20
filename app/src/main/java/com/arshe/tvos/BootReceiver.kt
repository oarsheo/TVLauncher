package com.arshe.tvos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arshe.tvos.data.LocalAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Philips boots into the last source; with no channels tuned that is the TV+ promo or the
 * channel-installation wizard. For a minute after boot, a detached shell loop watches the
 * focused window and sends the user Home if one of those shows up (HDMI etc. is left alone).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // adbd's network listener can come up a little after BOOT_COMPLETED.
                repeat(4) {
                    if (LocalAdb.shell(app, "setsid -d sh -c '$WATCH' </dev/null >/dev/null 2>&1; sleep 1") != null) return@launch
                    delay(2000)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val WATCH = "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do sleep 5; " +
            "case \"\$(dumpsys window | grep mCurrentFocus)\" in " +
            "*org.droidtv.fastip*|*org.droidtv.channels*|*euinstaller*) " +
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME; exit;; esac; done"
    }
}
