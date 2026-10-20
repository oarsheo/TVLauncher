package com.arshe.tvos.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.TvContractCompat.PreviewPrograms
import com.arshe.tvos.ui.Arts

private const val TAG = "TVOS"

/**
 * Reads the system "Watch Next" list that streaming apps publish for the home screen.
 * Third-party launchers may only be allowed to see their own rows; in that case this
 * returns an empty list and the home screen falls back to the apps row.
 */
class WatchNextRepository(private val context: Context, private val apps: () -> List<HeroItem>) {

    fun load(): List<HeroItem> {
        val byPkg = apps().associateBy { it.key }
        val out = mutableListOf<HeroItem>()
        val cols = arrayOf(
            WatchNextPrograms.COLUMN_PACKAGE_NAME,
            WatchNextPrograms.COLUMN_TITLE,
            WatchNextPrograms.COLUMN_EPISODE_TITLE,
            WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            WatchNextPrograms.COLUMN_POSTER_ART_URI,
            WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            WatchNextPrograms.COLUMN_INTENT_URI,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            WatchNextPrograms.COLUMN_DURATION_MILLIS,
            WatchNextPrograms.COLUMN_TYPE,
            WatchNextPrograms.COLUMN_RELEASE_DATE,
        )
        try {
            context.contentResolver.query(
                WatchNextPrograms.CONTENT_URI, cols, null, null,
                "${WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 12) {
                    val pkg = c.getString(0) ?: continue
                    val owner = byPkg[pkg] ?: continue
                    val title = c.getString(1) ?: continue
                    val epTitle = c.getString(2)
                    val season = c.getString(3)
                    val episode = c.getString(4)
                    val pos = c.getLong(9)
                    val dur = c.getLong(10)
                    val live = c.getInt(11) == PreviewPrograms.TYPE_CHANNEL
                    val intent = c.getString(8)?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
                    val left = if (dur > 0 && pos > 0) ((dur - pos) / 60_000).coerceAtLeast(1) else null
                    val meta = listOfNotNull(
                        if (season != null && episode != null) "S$season : E$episode" else null,
                        epTitle,
                        left?.let { "осталось $it мин" },
                    ).joinToString(" · ")
                    out += HeroItem(
                        key = "wn:$pkg:$title:$season:$episode",
                        app = owner.app,
                        brand = owner.brand,
                        title = title,
                        meta = meta,
                        desc = c.getString(5).orEmpty(),
                        art = owner.art,
                        poster = c.getString(6) ?: c.getString(7),
                        progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null,
                        live = live,
                        primary = if (live) "Смотреть эфир" else "Продолжить",
                        action = intent?.let { Action.Launch(it) } ?: owner.action,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "watch next not readable: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "watch next query failed", e)
        }
        Log.i(TAG, "watch next items visible: ${out.size}")
        return out
    }
}
