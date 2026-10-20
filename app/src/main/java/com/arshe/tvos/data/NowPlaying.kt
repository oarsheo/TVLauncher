package com.arshe.tvos.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Something the user watched recently, captured from the playing app's media session. */
data class Recent(
    val pkg: String,
    val title: String,
    val subtitle: String,
    val art: String?,
    val position: Long,
    val duration: Long,
    val updated: Long,
    /** Deep link resolved by [Lookup] (exact title), when the session didn't give us one. */
    val link: String? = null,
)

/**
 * Remembers what was playing in each app. Streaming apps publish a MediaSession while
 * they play; reading other apps' sessions needs an enabled notification listener, which
 * is granted once over adb (`cmd notification allow_listener`).
 */
object NowPlaying {
    private const val MAX = 12
    private val _items = MutableStateFlow<List<Recent>>(emptyList())
    val items: StateFlow<List<Recent>> = _items

    private lateinit var file: File
    private lateinit var artDir: File
    private lateinit var app: Context

    fun init(context: Context) {
        if (::file.isInitialized) return
        app = context.applicationContext
        file = File(context.filesDir, "recent.json")
        artDir = File(context.filesDir, "art").apply { mkdirs() }
        _items.value = runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                arr.getJSONObject(i).run {
                    Recent(
                        getString("pkg"), getString("title"), optString("subtitle"),
                        optString("art").ifEmpty { null }, optLong("pos"), optLong("dur"), optLong("t"),
                        optString("link").ifEmpty { null },
                    )
                }
            }
        }.getOrDefault(emptyList())
        _items.value.forEach(::enrich)
    }

    fun record(pkg: String, meta: MediaMetadata, state: PlaybackState?) {
        val title = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        if (title.isBlank()) return
        val subtitle = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val prev = _items.value.firstOrNull { it.pkg == pkg && it.title == title }
        val art = saveArt(pkg, title, meta) ?: prev?.art
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val item = Recent(
            pkg, title, subtitle, art,
            position = state?.position?.takeIf { it > 0 } ?: prev?.position ?: 0,
            duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: prev?.duration ?: 0,
            updated = System.currentTimeMillis(),
            link = prev?.link,
        )
        // One entry per show: a new episode replaces the previous one of the same series.
        val show = item.show
        val list = (listOf(item) + _items.value.filterNot { it.pkg == pkg && (it.title == title || it.show == show) }).take(MAX)
        _items.value = list
        persist(list)
        enrich(item)
    }

    /** Apps like Megogo/Netflix publish no artwork: look the show up by name in the background. */
    private fun enrich(r: Recent) {
        if (r.link != null || r.pkg !in setOf(PKG_MEGOGO, PKG_NETFLIX, PKG_APPLETV)) return
        Thread {
            val found = Lookup.find(app, r.pkg, r.show) ?: return@Thread
            val cur = _items.value
            val list = cur.map {
                if (it.pkg == r.pkg && it.show == r.show) it.copy(art = it.art ?: found.backdrop, link = found.link) else it
            }
            _items.value = list
            persist(list)
        }.start()
    }

    fun updatePosition(pkg: String, state: PlaybackState) {
        val cur = _items.value
        val i = cur.indexOfFirst { it.pkg == pkg }
        if (i < 0 || state.position <= 0) return
        val list = cur.toMutableList().also { it[i] = it[i].copy(position = state.position, updated = System.currentTimeMillis()) }
        _items.value = list
        persist(list)
    }

    private fun saveArt(pkg: String, title: String, meta: MediaMetadata): String? {
        val bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val f = File(artDir, "${pkg}_${title.hashCode()}.jpg")
        return runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            "file://${f.absolutePath}"
        }.getOrNull()
    }

    private fun persist(list: List<Recent>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("pkg", it.pkg).put("title", it.title).put("subtitle", it.subtitle)
                .put("art", it.art ?: "").put("pos", it.position).put("dur", it.duration).put("t", it.updated)
                .put("link", it.link ?: ""))
        }
        runCatching { file.writeText(arr.toString()) }
        // Drop artwork no longer referenced.
        val keep = list.mapNotNull { it.art?.removePrefix("file://") }.toSet()
        artDir.listFiles()?.filter { it.absolutePath !in keep }?.forEach { it.delete() }
    }
}

/** The series name when the session title is an episode ("Сезон 5, Серія 10…" + "Гра престолів"). */
val Recent.show: String
    get() = if (pkg in setOf(PKG_MEGOGO, PKG_NETFLIX, PKG_APPLETV) && subtitle.isNotBlank() && EPISODE.containsMatchIn(title)) subtitle else title

private val EPISODE = Regex("""(?i)(сезон|серія|серия|season|episode|эпизод|епізод|\bS\d+\s*:?\s*E\d+)""")

class NowPlayingListener : NotificationListenerService() {
    private val callbacks = mutableMapOf<MediaSession, MediaController.Callback>()
    private var msm: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    private class MediaSession(val token: android.media.session.MediaSession.Token) {
        override fun equals(other: Any?) = other is MediaSession && other.token == token
        override fun hashCode() = token.hashCode()
    }

    override fun onListenerConnected() {
        NowPlaying.init(this)
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val me = ComponentName(this, NowPlayingListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, me)
            track(m.getActiveSessions(me))
        }.onFailure { Log.w("TVOS", "media sessions unavailable", it) }
    }

    override fun onListenerDisconnected() {
        msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        callbacks.clear()
    }

    private fun track(controllers: List<MediaController>) {
        val live = controllers.associateBy { MediaSession(it.sessionToken) }
        callbacks.keys.retainAll(live.keys)
        for ((key, c) in live) {
            if (key in callbacks || c.packageName == packageName) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    metadata?.let { NowPlaying.record(c.packageName, it, c.playbackState) }
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    state ?: return
                    if (state.state == PlaybackState.STATE_PAUSED || state.state == PlaybackState.STATE_STOPPED) {
                        NowPlaying.updatePosition(c.packageName, state)
                    }
                }
            }
            c.registerCallback(cb)
            callbacks[key] = cb
            c.metadata?.let { NowPlaying.record(c.packageName, it, c.playbackState) }
        }
    }
}
