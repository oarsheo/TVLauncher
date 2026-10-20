package com.arshe.tvos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.arshe.tvos.ui.Arts
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

const val PKG_YOUTUBE = "com.google.android.youtube.tv"

/** "Most popular" chart from the YouTube Data API, cached on disk for a few hours. */
class YouTubeTrends(private val context: Context) {
    fun load(region: String = "UA", category: String? = null): List<HeroItem> {
        val key = Secrets.youtubeKey
        if (key.isBlank()) return emptyList()
        val cache = File(context.cacheDir, "yt_trends_${category ?: "all"}.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < TTL_MS
        val json = if (fresh) cache.readText() else fetch(key, region, category)?.also { cache.writeText(it) }
            ?: cache.takeIf { it.exists() }?.readText()
            ?: return emptyList()
        return runCatching { parse(json) }.onFailure { Log.w("TVOS", "yt parse", it) }.getOrDefault(emptyList())
    }

    private fun fetch(key: String, region: String, category: String?): String? = runCatching {
        val url = URL(
            "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails,statistics" +
                "&chart=mostPopular&regionCode=$region&hl=ru&maxResults=15&key=$key" +
                (category?.let { "&videoCategoryId=$it" } ?: ""),
        )
        (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 6000
            readTimeout = 8000
            if (responseCode != 200) {
                Log.w("TVOS", "yt http $responseCode: ${errorStream?.bufferedReader()?.readText()?.take(300)}")
                null
            } else inputStream.bufferedReader().readText()
        }
    }.onFailure { Log.w("TVOS", "yt fetch", it) }.getOrNull()

    private fun parse(json: String): List<HeroItem> {
        val items = JSONObject(json).getJSONArray("items")
        val brand = Brand(androidx.compose.ui.graphics.Color(0xFFFF2A1F), "Y")
        return List(items.length()) { i ->
            val v = items.getJSONObject(i)
            val id = v.getString("id")
            val sn = v.getJSONObject("snippet")
            val thumbs = sn.getJSONObject("thumbnails")
            val thumb = listOf("maxres", "standard", "high", "medium", "default")
                .firstNotNullOfOrNull { thumbs.optJSONObject(it)?.optString("url") }
            val views = v.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull()
            val dur = v.optJSONObject("contentDetails")?.optString("duration")?.let { formatDuration(it) }
            val live = sn.optString("liveBroadcastContent") == "live"
            HeroItem(
                key = "yt:$id",
                app = "YouTube",
                brand = brand,
                title = sn.optJSONObject("localized")?.optString("title") ?: sn.getString("title"),
                meta = listOfNotNull(sn.optString("channelTitle"), views?.let { formatViews(it) }, dur).joinToString(" · "),
                desc = sn.optString("description").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                art = Arts.coral,
                poster = thumb,
                live = live,
                primary = if (live) "Смотреть эфир" else "Смотреть",
                action = Action.Launch(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).setPackage(PKG_YOUTUBE),
                ),
            )
        }
    }

    private fun formatViews(n: Long) = when {
        n >= 1_000_000 -> "%.1f млн просмотров".format(n / 1_000_000.0)
        n >= 1_000 -> "${n / 1000} тыс. просмотров"
        else -> "$n просмотров"
    }

    private fun formatDuration(iso: String): String? = runCatching {
        val d = Duration.parse(iso)
        if (d.isZero) return null
        val t = d.seconds
        val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
        if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }.getOrNull()

    companion object {
        private const val TTL_MS = 3 * 60 * 60 * 1000L
    }
}
