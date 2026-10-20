package com.arshe.tvos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

private const val SX = 339
private const val SY = 250
private const val GRID_Y = 300

@Composable
fun AppsScreen(l: Launcher) {
    val order = l.appsOrder
    val tiles = l.tiles.associateBy { it.key }
    val focusedKey = order.getOrNull(l.appsFocus)
    val focusedTile = focusedKey?.let { tiles[it] }
    val move = l.moveMode
    val row = l.appsFocus / Launcher.APPS_COLS
    val scrollY by animateFloatAsState(-(row - 1).coerceAtLeast(0) * SY.toFloat(), tween(380, easing = J.Glide), label = "gridY")

    Box(Modifier.fillMaxSize()) {
        // Accent glow from the focused tile's brand colour.
        val glow = focusedTile?.brand?.color ?: Color.Transparent
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(0f to glow.copy(alpha = .2f), 1f to Color.Transparent, center = Offset(1152f, -108f), radius = 1400f),
            ),
        )

        Column(Modifier.offset(J.CONTENT_X.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText("Приложения", style = jText(64, 800, tracking = (-0.03).em))
            BasicText(
                if (move) "Перемещение: ${focusedTile?.title.orEmpty()}" else "Порядок здесь = порядок ряда на главной · удерживайте OK, чтобы переставить",
                style = jText(26, 600, if (move) Color.White else Color.White.copy(.6f)),
            )
        }

        Box(Modifier.offset(J.CONTENT_X.dp, GRID_Y.dp).graphicsLayer { translationY = scrollY }) {
            order.forEachIndexed { slot, k ->
                val tile = tiles[k] ?: return@forEachIndexed
                key(k) {
                    val on = slot == l.appsFocus && l.zone != Zone.Rail
                    val pos by animateOffsetAsState(
                        Offset(((slot % Launcher.APPS_COLS) * SX).toFloat(), ((slot / Launcher.APPS_COLS) * SY).toFloat()),
                        tween(220, easing = J.FocusSpring), label = "pos",
                    )
                    val a by animateFloatAsState(if (on) 1f else if (move) .4f else .85f, tween(250), label = "a")
                    Box(
                        Modifier
                            .offset { IntOffset(pos.x.toInt(), pos.y.toInt()) }
                            .graphicsLayer { alpha = a }
                            .then(if (on && move) Modifier.wobble() else Modifier),
                    ) {
                        AppCard(tile, on, 300, 169, scale = if (move) 1.12f else 1.08f, lift = if (move) -16f else -6f)
                        if (on && move) MoveArrows()
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = move,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
            enter = fadeIn(tween(260)) + slideInVertically(tween(260, easing = J.CopyIn)) { 16 },
            exit = fadeOut(tween(160)),
        ) {
            Row(
                Modifier.height(76.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF181818)).padding(horizontal = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(34.dp),
            ) {
                BasicText("← ↑ ↓ → переместить", style = jText(26, 700))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        BasicText("OK", style = jText(22, 800, Color.Black))
                    }
                    BasicText("положить", style = jText(26, 700))
                }
                BasicText("Back — отменить", style = jText(26, 700, Color.White.copy(.7f)))
            }
        }
    }
}

@Composable
private fun Modifier.wobble(): Modifier {
    val r by rememberInfiniteTransition(label = "wob").animateFloat(
        -1.2f, 1.2f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "r",
    )
    return this.graphicsLayer { rotationZ = r }
}

@Composable
private fun MoveArrows() {
    Canvas(Modifier.offset((-40).dp, (-40).dp).size(380.dp, 250.dp).graphicsLayer { alpha = .8f }) {
        val st = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun chevron(vararg p: Pair<Float, Float>) = drawPath(Path().apply {
            moveTo(p[0].first, p[0].second); p.drop(1).forEach { lineTo(it.first, it.second) }
        }, Color.White, style = st)
        chevron(14f to 113f, 4f to 125f, 14f to 137f)
        chevron(366f to 113f, 376f to 125f, 366f to 137f)
        chevron(178f to 14f, 190f to 4f, 202f to 14f)
        chevron(178f to 236f, 190f to 246f, 202f to 236f)
    }
}
