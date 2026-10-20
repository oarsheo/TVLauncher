package com.arshe.tvos.data

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Per-TV secrets (YouTube API key, JointSpace pairing) live in a file on the TV, not in the
 * APK, so public release builds carry none. Pushed once with scripts/push-secrets.sh to
 * /sdcard/Android/data/com.arshe.tvos/files/secrets.properties; it survives updates.
 */
object Secrets {
    private var props = Properties()

    fun load(context: Context) {
        val f = File(context.getExternalFilesDir(null), "secrets.properties")
        props = Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
    }

    val youtubeKey: String get() = props.getProperty("youtube.apiKey", "")
    val jointspaceUser: String get() = props.getProperty("jointspace.user", "")
    val jointspacePass: String get() = props.getProperty("jointspace.pass", "")
}
