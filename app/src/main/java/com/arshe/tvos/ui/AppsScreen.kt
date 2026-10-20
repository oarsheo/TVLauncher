package com.arshe.tvos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.rememberPoster

private const val LIST_X = 168
private const val LIST_Y = 230
private const val ROW_H = 88
private const val ROW_GAP = 8
private const val VISIBLE = 8
private const val PREVIEW_X = 840
private const val PREVIEW_W = 984
private const val PREVIEW_H = 553

/** "Все приложения": a list on the left, a large preview of the focused app on the right. */
@Composable
fun AppsScreen(l: Launcher) {
    val apps = l.orderedTiles
    val focused = apps.getOrNull(l.appsFocus)
    val inList = l.zone == Zone.Row
    val move = l.moveMode

    Box(Modifier.fillMaxSize()) {
        val glow = focused?.brand?.color ?: Color.Transparent
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(0f to glow.copy(alpha = .16f), 1f to Color.Transparent, center = Offset(1344f, 486f), radius = 1200f),
            ),
        )

        Row(Modifier.offset(LIST_X.dp, 90.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BasicText("Приложения", style = jText(64, 800, tracking = (-0.03).em))
            BasicText(
                if (move) "перемещение · ↑ ↓, OK — положить" else "${apps.size}",
                Modifier.padding(bottom = 10.dp),
                style = jText(28, 600, Color.White.copy(if (move) .85f else .5f)),
            )
        }

        AppList(l, apps, inList, move)

        if (focused != null) {
            Preview(l, focused)
            Row(Modifier.offset(PREVIEW_X.dp, 820.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Launcher.APP_ACTIONS.forEachIndexed { i, label ->
                    JButton(label, focused = l.zone == Zone.Hero && l.appsBtn == i, bold = i == 0)
                }
            }
            BasicText(
                "↑ ↓ — список · → — действия · OK — открыть · удерживайте OK — переставить",
                Modifier.offset(PREVIEW_X.dp, 930.dp),
                style = jText(24, 600, Color.White.copy(.45f)),
            )
        }
    }
}

@Composable
private fun AppList(l: Launcher, apps: List<HeroItem>, inList: Boolean, move: Boolean) {
    // Keep the focused row inside the visible window.
    val first = (l.appsFocus - (VISIBLE - 2)).coerceIn(0, (apps.size - VISIBLE).coerceAtLeast(0))
    val scroll by animateFloatAsState(-(first * (ROW_H + ROW_GAP)).toFloat(), tween(300, easing = J.Glide), label = "list")
    Box(Modifier.offset(LIST_X.dp, LIST_Y.dp).size(620.dp, (VISIBLE * (ROW_H + ROW_GAP)).dp).clip(RoundedCornerShape(12.dp))) {
        // Unbounded: the column is taller than the window and slides inside it; bounded, rows
        // past the 8th would be measured with zero height and never show up.
        Column(
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = scroll },
            verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
        ) {
            apps.forEachIndexed { i, app ->
                key(app.key) { AppRow(app, i, on = inList && i == l.appsFocus, selected = i == l.appsFocus, moving = move && i == l.appsFocus) }
            }
        }
    }
}

@Composable
private fun AppRow(app: HeroItem, index: Int, on: Boolean, selected: Boolean, moving: Boolean) {
    val s by animateFloatAsState(if (on) 1.03f else 1f, tween(200, easing = J.ButtonSpring), label = "row")
    val wob by rememberInfiniteTransition(label = "wob").animateFloat(
        -0.6f, 0.6f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "w",
    )
    val ink = when {
        on -> Color.Black
        selected -> Color.White
        else -> Color.White.copy(.7f)
    }
    Row(
        Modifier.width(600.dp).height(ROW_H.dp)
            .graphicsLayer {
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0f, .5f)
                rotationZ = if (moving) wob else 0f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Color.White else if (selected) Color.White.copy(.12f) else Color.Transparent)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BrandBadge(app.brand, 48)
        BasicText(app.title, Modifier.weight(1f), style = jText(32, 700, ink, tracking = (-0.01).em), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("%02d".format(index + 1), style = jText(24, 800, ink.copy(alpha = if (on) .45f else .35f)))
    }
}

@Composable
private fun Preview(l: Launcher, app: HeroItem) {
    Box(
        Modifier.offset(PREVIEW_X.dp, LIST_Y.dp).size(PREVIEW_W.dp, PREVIEW_H.dp)
            .shadow(40.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111111)),
    ) {
        AnimatedContent(
            targetState = app,
            contentKey = { it.key },
            transitionSpec = { fadeIn(tween(450, 150)) togetherWith fadeOut(tween(250)) },
            label = "preview",
        ) { a ->
            // Landscape artwork only: Megogo catalog posters are portrait, so skip those.
            val shot = l.previewOf(a)?.takeIf { !it.key.startsWith("mg:") }?.poster
            if (shot != null) {
                val img by rememberPoster(shot, PREVIEW_W)
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            } else {
                ArtCanvas(a.art, Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(.85f))))
        AnimatedContent(
            targetState = app,
            contentKey = { it.key },
            transitionSpec = { (fadeIn(tween(320, 180)) + slideInVertically(tween(480, 180, J.CopyIn)) { 12 }) togetherWith fadeOut(tween(150)) },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 44.dp, end = 44.dp, bottom = 36.dp),
            label = "copy",
        ) { a ->
            Column {
                BasicText(a.title, style = jText(84, 800, tracking = TitleTracking, lineHeight = 84.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(a.desc, Modifier.padding(top = 12.dp), style = jText(28, 500, Color.White.copy(.82f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
