package com.arshe.tvos.ui

import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin

/** Procedural backdrops ported from the CSS gradients in the design. */
sealed interface Layer {
    /** CSS `radial-gradient(rx% ry% at cx% cy%, stops)`. */
    class Radial(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val stops: List<Pair<Float, Color>>) : Layer

    /** CSS `repeating-linear-gradient(angle, stops)` with stops in px over one period. */
    class Stripes(val angle: Float, val period: Float, val stops: List<Pair<Float, Color>>) : Layer
}

class Art(val layers: List<Layer>)

private fun rgba(hex: Long, a: Float = 1f) = Color(0xFF000000 or hex).copy(alpha = a)
private fun clear(hex: Long) = rgba(hex, 0f)

object Arts {
    val red = Art(listOf(
        Layer.Stripes(90f, 76f, listOf(0f to rgba(0, .38f), 30f to rgba(0, .38f), 30f to clear(0), 76f to clear(0))),
        Layer.Radial(.62f, .40f, .7f, .9f, listOf(0f to rgba(0xFF2A2A), .4f to rgba(0x8A0A10), .8f to rgba(0x1A0204), 1f to rgba(0))),
    ))
    val coral = Art(listOf(
        Layer.Radial(.75f, .28f, .4f, .55f, listOf(0f to rgba(0xFFD678, .9f), .7f to clear(0xFFD678))),
        Layer.Radial(.60f, .50f, .7f, .9f, listOf(0f to rgba(0xFF5A2E), .5f to rgba(0xB3123E), .9f to rgba(0x22040F))),
    ))
    val silver = Art(listOf(
        Layer.Radial(.62f, .40f, .3f, .45f, listOf(0f to rgba(0xFFFFFF), 1f to clear(0xFFFFFF))),
        Layer.Radial(.60f, .45f, .7f, .9f, listOf(0f to rgba(0x9FB0C4), .5f to rgba(0x39414D), .95f to rgba(0x0A0B0E))),
    ))
    val teal = Art(listOf(
        Layer.Radial(.70f, .30f, .45f, .6f, listOf(0f to rgba(0x78FFDC, .5f), .7f to clear(0x78FFDC))),
        Layer.Radial(.60f, .55f, .7f, .9f, listOf(0f to rgba(0x0F7A6A), .55f to rgba(0x07332D), 1f to rgba(0x020A09))),
    ))
    val violet = Art(listOf(
        Layer.Radial(.30f, .25f, .45f, .6f, listOf(0f to rgba(0x45D6FF, .75f), .7f to clear(0x45D6FF))),
        Layer.Radial(.70f, .60f, .7f, .9f, listOf(0f to rgba(0x8C4BFF), .5f to rgba(0x2A1080), .95f to rgba(0x070320))),
    ))
    val blue = Art(listOf(
        Layer.Radial(.65f, .45f, .7f, .9f, listOf(0f to rgba(0x4A82FF), .45f to rgba(0x163AA8), .92f to rgba(0x061033))),
    ))
    val amber = Art(listOf(
        Layer.Stripes(0f, 7f, listOf(0f to rgba(0, .28f), 3f to rgba(0, .28f), 3f to clear(0), 7f to clear(0))),
        Layer.Radial(.62f, .45f, .7f, .9f, listOf(0f to rgba(0xFFBF66), .45f to rgba(0xC0601F), .92f to rgba(0x3A1806))),
    ))
    val graphite = Art(listOf(
        Layer.Stripes(135f, 22f, listOf(0f to rgba(0xFFFFFF, .05f), 2f to rgba(0xFFFFFF, .05f), 2f to clear(0xFFFFFF), 22f to clear(0xFFFFFF))),
        Layer.Radial(.62f, .45f, .7f, .9f, listOf(0f to rgba(0x8D96A3), .48f to rgba(0x3F454E), .92f to rgba(0x121417))),
    ))
    val pink = Art(listOf(
        Layer.Radial(.85f, .30f, .4f, .6f, listOf(0f to rgba(0x45D6FF, .65f), .7f to clear(0x45D6FF))),
        Layer.Radial(.55f, .60f, .5f, .7f, listOf(0f to rgba(0x8C4BFF, .85f), .7f to clear(0x8C4BFF))),
        Layer.Radial(.70f, .40f, .7f, .9f, listOf(0f to rgba(0xFF4D6D), .7f to rgba(0x4A0B2A), 1f to rgba(0))),
    ))

    // HDMI / tuner backdrops from the Sources screen.
    val hdmiActive = Art(listOf(
        Layer.Radial(.65f, .38f, .6f, .7f, listOf(0f to rgba(0x4C7DFF, .75f), .7f to clear(0x4C7DFF))),
        Layer.Radial(.5f, .5f, 1.2f, 1.2f, listOf(0f to rgba(0x0A1A52), .6f to rgba(0x03081C), 1f to rgba(0x02040C))),
    ))
    val hdmiIdle = Art(listOf(
        Layer.Radial(.65f, .38f, .5f, .6f, listOf(0f to rgba(0x8C92A0, .2f), .7f to clear(0x8C92A0))),
        Layer.Radial(.5f, .5f, 1.2f, 1.2f, listOf(0f to rgba(0x14161A), .6f to rgba(0x07080A), 1f to rgba(0x050506))),
    ))
    val tuner = Art(listOf(
        Layer.Stripes(0f, 7f, listOf(0f to rgba(0, .25f), 3f to rgba(0, .25f), 3f to clear(0), 7f to clear(0))),
        Layer.Radial(.65f, .40f, .6f, .7f, listOf(0f to rgba(0xE9A15B, .6f), .7f to clear(0xE9A15B))),
        Layer.Radial(.5f, .5f, 1.2f, 1.2f, listOf(0f to rgba(0x2A1A0A), .6f to rgba(0x0C0703), 1f to rgba(0x050302))),
    ))

    private val pool = listOf(red, coral, silver, teal, violet, blue, amber, graphite, pink)
    fun forKey(key: String): Art = pool[Math.floorMod(key.hashCode(), pool.size)]
}

fun DrawScope.drawArt(art: Art) {
    drawRect(Color.Black)
    for (layer in art.layers.asReversed()) when (layer) {
        is Layer.Radial -> drawRadial(layer)
        is Layer.Stripes -> drawStripes(layer)
    }
}

private fun DrawScope.drawRadial(l: Layer.Radial) {
    val w = size.width
    val h = size.height
    val c = Offset(w * l.cx, h * l.cy)
    val rx = (w * l.rx).coerceAtLeast(1f)
    val k = (h * l.ry) / rx
    val brush = Brush.radialGradient(*l.stops.toTypedArray(), center = c, radius = rx, tileMode = TileMode.Clamp)
    withTransform({ scale(1f, k, pivot = c) }) {
        val top = c.y + (0f - c.y) / k
        val bottom = c.y + (h - c.y) / k
        drawRect(brush, topLeft = Offset(0f, top), size = Size(w, bottom - top))
    }
}

private fun DrawScope.drawStripes(l: Layer.Stripes) {
    val rad = Math.toRadians(l.angle.toDouble())
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val stops = l.stops.map { (px, color) -> (px / l.period) to color }.toTypedArray()
    val brush = Brush.linearGradient(*stops, start = Offset.Zero, end = dir * l.period, tileMode = TileMode.Repeated)
    drawRect(brush)
}

/**
 * Draws an [Art] from a bitmap rendered once per (art, size). Gradient shaders are
 * expensive on the TV's Mali GPU; a cached texture costs one textured quad per frame.
 */
@Composable
fun ArtCanvas(art: Art, modifier: Modifier = Modifier) {
    Spacer(modifier.drawWithCache {
        val bmp = BitmapCache.art(art, size)
        onDrawBehind { drawCached(bmp) }
    })
}

object BitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Smooth gradients survive half resolution; it quarters the CPU render time and memory. */
    private const val RES = 0.5f

    fun art(art: Art, size: Size) = get("art:${System.identityHashCode(art)}", size) { drawArt(art) }

    fun get(key: String, size: Size, draw: DrawScope.() -> Unit): ImageBitmap {
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val k = "$key:$w:$h"
        cache.get(k)?.let { return it }
        val bmp = ImageBitmap((w * RES).toInt().coerceAtLeast(1), (h * RES).toInt().coerceAtLeast(1))
        val canvas = androidx.compose.ui.graphics.Canvas(bmp)
        canvas.scale(RES, RES)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat()), draw)
        bmp.prepareToDraw()
        cache.put(k, bmp)
        return bmp
    }

    /** Renders backdrops ahead of time so the first focus on a tile never stalls a frame. */
    fun prewarm(arts: Collection<Art>, sizes: List<Size>) {
        for (a in arts) for (s in sizes) art(a, s)
    }
}

fun DrawScope.drawCached(bmp: ImageBitmap) {
    drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
}
