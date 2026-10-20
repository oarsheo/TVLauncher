package com.arshe.tvos.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.arshe.tvos.data.HERO_PX
import com.arshe.tvos.data.Brand
import com.arshe.tvos.data.rememberPoster

// ---------- Hero backdrop ----------

data class Backdrop(val key: String, val art: Art, val poster: String?)

@Composable
fun HeroBackdrop(backdrop: Backdrop, modifier: Modifier = Modifier, dim: Float = 0f) {
    Box(modifier.offset(x = 420.dp).size(1500.dp, 800.dp).clip(RoundedCornerShape(0.dp))) {
        Crossfade(backdrop, animationSpec = tween(600, delayMillis = 250), label = "hero") { b ->
            KenBurns { BackdropFill(b) }
        }
        // Both scrims baked into one cached texture.
        Spacer(Modifier.fillMaxSize().drawWithCache {
            val bmp = BitmapCache.get("scrim", size) {
                drawRect(Brush.horizontalGradient(
                    0f to Color.Black, .18f to Color.Black.copy(.85f), .5f to Color.Black.copy(.25f), .75f to Color.Transparent,
                ))
                drawRect(Brush.verticalGradient(.45f to Color.Transparent, .78f to Color.Black.copy(.7f), 1f to Color.Black))
            }
            onDrawBehind { drawCached(bmp) }
        })
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(dim)))
    }
}

@Composable
private fun BackdropFill(b: Backdrop) {
    val poster by rememberPoster(b.poster, HERO_PX)
    val bmp = poster
    if (bmp != null) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
    } else {
        ArtCanvas(b.art, Modifier.fillMaxSize())
    }
}

@Composable
fun KenBurns(periodMs: Int = 30_000, content: @Composable BoxScope.() -> Unit) {
    val t by rememberInfiniteTransition(label = "kb").animateFloat(
        0f, 1f, infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "kbT",
    )
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val s = 1.02f + 0.07f * t
            scaleX = s; scaleY = s
            translationX = -24f * t
            translationY = -8f * t
        },
        content = content,
    )
}

// ---------- Focusable card ----------

@Composable
fun FocusCard(
    focused: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    scale: Float = 1.08f,
    lift: Float = 0f,
    origin: TransformOrigin = TransformOrigin(0f, .5f),
    content: @Composable BoxScope.() -> Unit,
) {
    val s by animateFloatAsState(if (focused) scale else 1f, tween(220, easing = J.FocusSpring), label = "s")
    val y by animateFloatAsState(if (focused) lift else 0f, tween(220, easing = J.FocusSpring), label = "y")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "ring")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                scaleX = s; scaleY = s
                translationY = y
                transformOrigin = origin
            }
            .shadow(if (focused) 30.dp else 0.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .drawWithContent {
                drawContent()
                if (ring > 0f) drawRoundRect(
                    Color.White.copy(alpha = ring),
                    topLeft = Offset(-2f, -2f),
                    size = Size(size.width + 4f, size.height + 4f),
                    cornerRadius = CornerRadius(14f),
                    style = Stroke(4f),
                )
            },
    ) {
        Box(Modifier.fillMaxSize().clip(shape).background(Color(0xFF111111)), content = content)
    }
}

// ---------- Badges, buttons, status ----------

@Composable
fun BrandBadge(brand: Brand, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(brand.color),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(brand.letter, style = jText(if (brand.letter.length > 1) 18 else 24, 800, brand.ink).copy(textAlign = TextAlign.Center))
    }
}

@Composable
fun LivePill() {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(6.dp)).background(J.Accent).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        BasicText("LIVE", style = jText(24, 800, tracking = 0.08.em))
    }
}

@Composable
fun JButton(label: String, focused: Boolean, icon: (@Composable (Color) -> Unit)? = null, bold: Boolean = true) {
    val s by animateFloatAsState(if (focused) 1.06f else 1f, tween(200, easing = J.ButtonSpring), label = "b")
    val ink = if (focused) Color.Black else Color.White
    Row(
        Modifier
            .height(72.dp)
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else J.Button)
            .padding(horizontal = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) icon(ink)
        BasicText(label, style = jText(28, if (bold) 800 else 700, ink))
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier) {
    Box(modifier.height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(.28f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(J.Accent))
    }
}

@Composable
fun StatusBar(time: String, vpn: Boolean, online: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = J.SAFE_Y.dp, end = J.SAFE_X.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (!online) BasicText("Нет сети", style = jText(24, 700, Color.White.copy(.8f)))
        if (vpn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(J.On))
            BasicText("VPN", style = jText(24, 700, Color.White.copy(.9f)).copy(shadow = Shadow(Color.Black.copy(.7f), Offset(0f, 2f), 12f)))
        }
        BasicText(time, style = jText(28, 700, Color.White.copy(.9f)).copy(shadow = Shadow(Color.Black.copy(.7f), Offset(0f, 2f), 12f)))
    }
}

// ---------- Left rail ----------

enum class RailItem(val label: String) { Home("Главная"), Apps("Приложения"), Sources("Источники"), Settings("Настройки") }

@Composable
fun Rail(open: Boolean, focus: Int, active: RailItem) {
    val shadeW by animateDpAsState(if (open) 640.dp else 220.dp, tween(300), label = "shade")
    val a0 by animateFloatAsState(if (open) .95f else .85f, tween(300), label = "a0")
    val a1 by animateFloatAsState(if (open) .85f else .4f, tween(300), label = "a1")
    Box(
        Modifier.width(shadeW).fillMaxHeight().background(
            Brush.horizontalGradient(0f to Color.Black.copy(a0), .55f to Color.Black.copy(a1), 1f to Color.Transparent),
        ),
    )
    Box(Modifier.fillMaxHeight().width(640.dp), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.offset(x = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            RailItem.entries.forEachIndexed { i, item ->
                val focused = open && i == focus
                val isActive = item == active
                val ink = when {
                    focused -> Color.Black
                    isActive -> Color.White
                    else -> Color.White.copy(.55f)
                }
                val labelA by animateFloatAsState(if (open) 1f else 0f, tween(220), label = "l")
                Box {
                    if (isActive && !focused) Box(
                        Modifier.offset(x = (-14).dp, y = 18.dp).size(4.dp, 28.dp).clip(RoundedCornerShape(2.dp)).background(J.Accent),
                    )
                    Row(
                        Modifier.height(64.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (focused) Color.White else Color.Transparent)
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        RailIcon(item, ink)
                        if (labelA > 0.01f) BasicText(
                            item.label,
                            style = jText(28, 700, ink),
                            modifier = Modifier.graphicsLayer { alpha = labelA; translationX = -10f * (1 - labelA) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailIcon(item: RailItem, ink: Color) {
    Canvas(Modifier.size(34.dp)) {
        val stroke = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (item) {
            RailItem.Home -> drawPath(Path().apply {
                moveTo(5f, 15f); lineTo(17f, 5f); lineTo(29f, 15f); lineTo(29f, 29f); lineTo(21f, 29f)
                lineTo(21f, 21f); lineTo(13f, 21f); lineTo(13f, 29f); lineTo(5f, 29f); close()
            }, ink, style = stroke)
            RailItem.Apps -> listOf(5f to 5f, 19f to 5f, 5f to 19f, 19f to 19f).forEach { (x, y) ->
                drawRoundRect(ink, Offset(x, y), Size(10f, 10f), CornerRadius(3f))
            }
            RailItem.Sources -> {
                drawPath(Path().apply {
                    moveTo(4f, 11f); lineTo(30f, 11f); lineTo(30f, 19f); lineTo(26f, 24f); lineTo(8f, 24f); lineTo(4f, 19f); close()
                }, ink, style = stroke)
                drawLine(ink, Offset(11f, 16f), Offset(23f, 16f), 2.6f, StrokeCap.Round)
            }
            RailItem.Settings -> {
                listOf(10f, 17f, 24f).forEach { y -> drawLine(ink, Offset(5f, y), Offset(29f, y), 2.6f, StrokeCap.Round) }
                listOf(12f to 10f, 22f to 17f, 15f to 24f).forEach { (x, y) ->
                    drawCircle(Color.Black, 3f, Offset(x, y))
                    drawCircle(ink, 3f, Offset(x, y), style = Stroke(2.6f))
                }
            }
        }
    }
}

@Composable
fun PlayIcon(ink: Color) {
    Canvas(Modifier.size(26.dp)) {
        drawPath(Path().apply { moveTo(6f, 3f); lineTo(23f, 13f); lineTo(6f, 23f); close() }, ink)
    }
}

@Composable
fun SwitchIcon(ink: Color) {
    Canvas(Modifier.size(26.dp)) {
        val st = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(4f, 9f); lineTo(20f, 9f); lineTo(16f, 5f) }, ink, style = st)
        drawPath(Path().apply { moveTo(22f, 17f); lineTo(6f, 17f); lineTo(10f, 21f) }, ink, style = st)
    }
}
