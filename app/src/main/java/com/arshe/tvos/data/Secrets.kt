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

    /** House inverter's Wi-Fi logger (ip:serial), battery kWh and the % where it cuts off. */
    val deye: Pair<String, Long>? get() = props.getProperty("deye")?.split(':')?.takeIf { it.size == 2 }
        ?.let { (ip, sn) -> sn.trim().toLongOrNull()?.let { ip.trim() to it } }
    val batteryKwh: Double get() = props.getProperty("deye.kwh")?.toDoubleOrNull() ?: 0.0
    val batteryReserve: Int get() = props.getProperty("deye.reserve")?.toIntOrNull() ?: 20
}
