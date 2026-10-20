package com.arshe.tvos.ui

import android.content.Intent
import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.arshe.tvos.data.HERO_PX
import com.arshe.tvos.data.Action
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.SaverStore
import com.arshe.tvos.data.rememberClock
import com.arshe.tvos.data.rememberPoster
import kotlinx.coroutines.delay

private const val SLIDE_MS = 40_000

/** Aerial-style slideshow of catalog backdrops; text drifts slowly to protect the OLED panel. */
@Composable
fun Screensaver(items: List<HeroItem>, onCurrent: (HeroItem?) -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val current = items.getOrNull(index % items.size.coerceAtLeast(1))
    LaunchedEffect(current) { onCurrent(current) }
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(SLIDE_MS.toLong())
            index = (index + 1) % items.size
        }
    }
    val time = rememberClock()
    val t = rememberInfiniteTransition(label = "saver")
    val drift by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(240_000, easing = LinearEasing), RepeatMode.Restart), label = "drift",
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(current, animationSpec = tween(2400), label = "slide") { item ->
            if (item == null) return@Crossfade
            val pan by rememberInfiniteTransition(label = "pan").animateFloat(
                0f, 1f, infiniteRepeatable(tween(SLIDE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "panT",
            )
            val img by rememberPoster(item.poster, HERO_PX)
            val bmp = img
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1.12f + 0.08f * pan
                    scaleX = s; scaleY = s
                    translationX = 60f - 140f * pan
                    translationY = -20f * pan
                },
            ) {
                if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, item.heroBias))
                else ArtCanvas(item.art, Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(.62f to Color.Transparent, 1f to Color.Black.copy(.65f)),
            ),
        )
        // Four-point drift loop, ~1 px every couple of seconds.
        Column(
            Modifier.align(Alignment.BottomStart).offset(120.dp, (-80).dp).graphicsLayer {
                val (dx, dy) = driftAt(drift)
                translationX = dx; translationY = dy
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(time, style = jText(64, 700, Color.White.copy(.72f), tracking = TitleTracking))
            current?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandBadge(it.brand, 30)
                    BasicText("${it.title} · ${it.app} — OK, чтобы открыть", style = jText(24, 600, Color.White.copy(.55f)), maxLines = 1)
                }
            }
        }
    }
}

private fun driftAt(f: Float): Pair<Float, Float> {
    val pts = listOf(0f to 0f, 30f to -12f, 52f to 6f, 18f to 16f, 0f to 0f)
    val seg = (f * 4).coerceAtMost(3.999f)
    val i = seg.toInt()
    val k = seg - i
    val (x0, y0) = pts[i]
    val (x1, y1) = pts[i + 1]
    return (x0 + (x1 - x0) * k) to (y0 + (y1 - y0) * k)
}

/** System screensaver hosting the same slideshow. */
class TvosDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private var current: HeroItem? = null
    private var items by mutableStateOf(emptyList<HeroItem>())

    private fun post(r: () -> Unit) = android.os.Handler(mainLooper).post(r)

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        items = SaverStore.items
        if (items.isEmpty()) Thread { SaverStore.load(this).let { loaded -> post { items = loaded } } }.start()
        setContentView(ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TvosDream)
            setViewTreeSavedStateRegistryOwner(this@TvosDream)
            setContent { DesignCanvas { Screensaver(items) { current = it } } }
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        // The window can be detached (DESTROYED) before the system reports the stop.
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return true
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        val action = current?.action
        if (ok && action is Action.Launch) {
            runCatching { startActivity(Intent(action.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
        return true
    }
}
