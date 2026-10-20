package com.arshe.tvos.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.arshe.tvos.ui.Art
import com.arshe.tvos.ui.Arts

const val KEY_TV = "special:tv"
const val KEY_SOURCES = "special:sources"
const val KEY_SETTINGS = "special:settings"
const val KEY_ALL = "special:all"

private const val PKG_TV = "org.droidtv.playtv"

private data class Known(val art: Art, val brand: Brand, val meta: String, val desc: String)

private val KNOWN = mapOf(
    "com.netflix.ninja" to Known(Arts.red, Brand(Color(0xFFE50914), "N"), "Приложение", "Фильмы и сериалы Netflix"),
    "com.google.android.youtube.tv" to Known(Arts.coral, Brand(Color(0xFFFF2A1F), "Y"), "Приложение", "Видео, подписки и трансляции"),
    "com.megogo.application" to Known(Arts.violet, Brand(Color(0xFF7B5CFF), "M"), "Приложение", "Фильмы, сериалы и ТВ-каналы"),
    "com.apple.atve.androidtv.appletv" to Known(Arts.silver, Brand(Color(0xFFE9EDF2), "A", Color.Black), "Приложение", "Apple TV+ и фильмы из медиатеки"),
    "com.nordvpn.android" to Known(Arts.blue, Brand(Color(0xFF4F86FF), "V"), "VPN", "Защищённое соединение для всех приложений"),
)

private val DEFAULT_ORDER = listOf(
    "com.netflix.ninja", "com.google.android.youtube.tv", "com.megogo.application",
    "com.apple.atve.androidtv.appletv", "com.nordvpn.android", KEY_TV, KEY_SOURCES, KEY_SETTINGS,
)

// Our own launcher entry and system plumbing that should never show as a tile.
private val HIDDEN = setOf("com.arshe.tvos", PKG_TV)

private val GRAPHITE = Brand(Color(0xFF2B2E36), "")

class AppsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** All tiles (apps + built-in shortcuts) in the user's order. */
    fun load(): List<HeroItem> {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
            .filter { it.activityInfo.packageName !in HIDDEN }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                val known = KNOWN[pkg]
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).setClassName(pkg, ri.activityInfo.name)
                HeroItem(
                    key = pkg,
                    app = label,
                    brand = known?.brand ?: GRAPHITE.copy(letter = label.take(1)),
                    title = label,
                    meta = known?.meta ?: "Приложение",
                    desc = known?.desc ?: "Открыть $label",
                    art = known?.art ?: Arts.forKey(pkg),
                    action = Action.Launch(launch),
                )
            }

        val specials = listOfNotNull(
            pm.getLeanbackLaunchIntentForPackage(PKG_TV)?.let {
                HeroItem(KEY_TV, "ТВ", GRAPHITE.copy(letter = "TV"), "ТВ-каналы", "Эфир",
                    "Эфирные, кабельные и спутниковые каналы", Arts.amber, primary = "Смотреть", action = Action.Launch(it))
            },
            HeroItem(KEY_SOURCES, "Входы", GRAPHITE.copy(letter = "H"), "Источники", "HDMI и тюнер",
                "Приставки, консоли и эфирное ТВ", Arts.teal, action = Action.OpenSources),
            HeroItem(KEY_SETTINGS, "Система", GRAPHITE.copy(letter = "S"), "Настройки", "Система",
                "Быстрые настройки, сеть и все параметры телевизора", Arts.graphite, action = Action.OpenSettings),
        )

        val all = (apps + specials).associateBy { it.key }
        val saved = prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val ordered = (saved + DEFAULT_ORDER).distinct().mapNotNull { all[it] }
        val rest = all.values.filter { it !in ordered }.sortedBy { it.title.lowercase() }
        return ordered + rest
    }

    fun saveOrder(keys: List<String>) = prefs.edit { putString("order", keys.joinToString(",")) }

    companion object {
        val allAppsTile = HeroItem(KEY_ALL, "Библиотека", GRAPHITE.copy(letter = "+"), "Все приложения",
            "Библиотека", "Удерживайте OK, чтобы переставить", Arts.pink, action = Action.OpenApps)

        fun settingsIntent() = Intent(Settings.ACTION_SETTINGS)
        fun networkIntent() = Intent(Settings.ACTION_WIFI_SETTINGS)
    }
}
