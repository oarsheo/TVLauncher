package com.arshe.tvos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.arshe.tvos.data.HERO_PX
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
import com.arshe.tvos.data.Secrets
import com.arshe.tvos.data.YouTubeSource
import com.arshe.tvos.ui.LauncherRoot
import com.arshe.tvos.ui.isHomeIntent
import com.arshe.tvos.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Apps grid / inputs cards — in design px.
private val ART_SIZES = listOf(Size(300f, 169f))

class MainActivity : ComponentActivity() {
    private lateinit var apps: AppsRepository
    private lateinit var watchNext: WatchNextRepository
    private lateinit var sources: SourcesRepository
    private lateinit var launcher: Launcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Secrets.load(this)
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

        // OTA: look for a new release now and every 6 hours; install only while hidden.
        lifecycleScope.launch {
            while (true) {
                if (Updater.check(this@MainActivity) && !launcher.visible) Updater.install(this@MainActivity)
                delay(6 * 60 * 60 * 1000L)
            }
        }

    }

    override fun onStart() {
        super.onStart()
        launcher.visible = true
    }

    // While another app plays, hand our image memory back to it: drop the UI and the
    // decoded-image caches (artwork reloads from the disk cache on return).
    override fun onStop() {
        super.onStop()
        launcher.visible = false
        Images.clear()
        BitmapCache.clear()
        // A downloaded update goes in while something else is on screen.
        if (Updater.status.state == Updater.State.Ready) lifecycleScope.launch { Updater.install(this@MainActivity) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Images.clear()
            BitmapCache.clear()
        }
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
            by[PKG_YOUTUBE]?.let { put(PKG_YOUTUBE) { YouTubeSource(this@MainActivity, it.brand).rows() } }
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
            // Only what the first screen shows: the rest loads on demand, keeping startup memory low.
            rows.take(1).forEachIndexed { r, row ->
                row.items.take(5).forEachIndexed { i, it ->
                    val card = if (row.portrait) it.cover ?: it.poster else it.poster
                    card?.let { u -> Images.load(this@MainActivity, u, if (row.portrait) 200 else 400) }
                    if (r == 0 && i < 2) {
                        val hero = if (it.key.startsWith("mg:")) Lookup.megogoBackdrop(this@MainActivity, it.key.removePrefix("mg:")) ?: it.poster else it.poster
                        hero?.let { u -> Images.load(this@MainActivity, u, HERO_PX) }
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
