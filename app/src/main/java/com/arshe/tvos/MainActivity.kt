package com.arshe.tvos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.arshe.tvos.data.AppsRepository
import com.arshe.tvos.data.SourcesRepository
import com.arshe.tvos.data.Images
import com.arshe.tvos.data.NowPlaying
import com.arshe.tvos.data.WatchNextRepository
import androidx.compose.ui.geometry.Size
import com.arshe.tvos.ui.BitmapCache
import com.arshe.tvos.ui.Launcher
import com.arshe.tvos.data.ContentRow
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.JustWatchSource
import com.arshe.tvos.data.MegogoSource
import com.arshe.tvos.data.PKG_APPLETV
import com.arshe.tvos.data.PKG_MEGOGO
import com.arshe.tvos.data.PKG_NETFLIX
import com.arshe.tvos.data.PKG_YOUTUBE
import com.arshe.tvos.data.Lookup
import com.arshe.tvos.data.SaverStore
import com.arshe.tvos.data.YouTubeSource
import com.arshe.tvos.ui.LauncherRoot
import com.arshe.tvos.ui.isHomeIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Hero backdrop, wide card, home app card, grid/source card — in design px.
private val ART_SIZES = listOf(Size(1500f, 800f), Size(352f, 198f), Size(264f, 148f), Size(300f, 169f))

class MainActivity : ComponentActivity() {
    private lateinit var apps: AppsRepository
    private lateinit var watchNext: WatchNextRepository
    private lateinit var sources: SourcesRepository
    private lateinit var launcher: Launcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = AppsRepository(this)
        watchNext = WatchNextRepository(this) { launcher.tiles }
        sources = SourcesRepository(this)
        launcher = Launcher(this, lifecycleScope, apps)

        // A launcher never finishes on Back; the key handler decides what Back means.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        NowPlaying.init(this)
        lifecycleScope.launch { NowPlaying.items.collect { launcher.recents = it } }

        setContent { LauncherRoot(launcher) }
    }

    override fun onResume() {
        super.onResume()
        launcher.refreshAmbi()
        lifecycleScope.launch {
            val tiles = withContext(Dispatchers.IO) { apps.load() }
            withContext(Dispatchers.Default) {
                BitmapCache.prewarm((tiles + AppsRepository.allAppsTile).map { it.art }.toSet(), ART_SIZES)
            }
            launcher.tiles = tiles
            val (cont, src) = withContext(Dispatchers.IO) { watchNext.load() to sources.load() }
            launcher.reload(tiles, cont, src)
            loadPages(tiles)
        }
    }

    /** Fetch each content app's catalog in parallel; pages appear as they arrive. */
    private fun loadPages(tiles: List<HeroItem>) {
        val by = tiles.associateBy { it.key }
        val sources: Map<String, () -> List<ContentRow>> = buildMap {
            by[PKG_NETFLIX]?.let { put(PKG_NETFLIX) { JustWatchSource(this@MainActivity, PKG_NETFLIX, "nfx", "Netflix", it.brand, it.art).rows() } }
            by[PKG_APPLETV]?.let { put(PKG_APPLETV) { JustWatchSource(this@MainActivity, PKG_APPLETV, "atp", "Apple TV+", it.brand, it.art).rows() } }
            by[PKG_MEGOGO]?.let { put(PKG_MEGOGO) { MegogoSource(this@MainActivity, it.brand, it.art).rows() } }
            by[PKG_YOUTUBE]?.let { put(PKG_YOUTUBE) { YouTubeSource(this@MainActivity).rows() } }
        }
        for ((pkg, load) in sources) lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { load() }
            if (rows.isNotEmpty()) launcher.pages = launcher.pages + (pkg to rows)
            SaverStore.update(launcher.pages, emptyList())
            prefetch(rows)
        }
    }

    /** Warm the image cache so the billboard and the first cards show artwork on first focus. */
    private fun prefetch(rows: List<ContentRow>) {
        lifecycleScope.launch(Dispatchers.IO) {
            rows.forEachIndexed { r, row ->
                row.items.take(8).forEachIndexed { i, it ->
                    val card = if (row.portrait) it.cover ?: it.poster else it.poster
                    card?.let { u -> Images.load(this@MainActivity, u, if (row.portrait) 260 else 480) }
                    if (r == 0 && i < 6) {
                        val hero = if (it.key.startsWith("mg:")) Lookup.megogoBackdrop(this@MainActivity, it.key.removePrefix("mg:")) ?: it.poster else it.poster
                        hero?.let { u -> Images.load(this@MainActivity, u, 1920) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.isHomeIntent()) launcher.goHome()
    }
}
