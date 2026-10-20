package com.arshe.tvos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshe.tvos.data.Source

@Composable
fun SourcesScreen(l: Launcher) {
    val sources = l.sources
    val cur = sources.getOrNull(l.srcIdx)
    Box(Modifier.fillMaxSize()) {
        if (cur != null) HeroBackdrop(Backdrop("src:" + cur.id, cur.art, null))

        Box(Modifier.offset(J.CONTENT_X.dp, 150.dp).size(1100.dp, 340.dp), contentAlignment = Alignment.BottomStart) {
            AnimatedContent(
                targetState = cur,
                contentKey = { it?.id },
                transitionSpec = { (fadeIn(tween(380, 260)) + slideInVertically(tween(560, 260, J.CopyIn)) { 14 }) togetherWith fadeOut(tween(160)) },
                contentAlignment = Alignment.BottomStart,
                label = "srcCopy",
            ) { s -> if (s != null) SourceCopy(s) }
        }

        Row(Modifier.offset(J.CONTENT_X.dp, 512.dp)) {
            JButton(
                if (cur?.live != false) "Переключиться" else "Открыть вход",
                focused = l.zone == Zone.Hero,
                icon = { ink -> SwitchIcon(ink) },
            )
        }

        BasicText("Входы", Modifier.offset(J.CONTENT_X.dp, 616.dp), style = jText(30, 700))
        Row(Modifier.offset(J.CONTENT_X.dp, 668.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            sources.forEachIndexed { i, s ->
                val on = l.zone == Zone.Row && i == l.srcIdx
                Box(Modifier.graphicsLayer { alpha = if (s.live || on) 1f else .6f }) {
                    FocusCard(on, 300.dp, 169.dp, origin = if (i == 0) TransformOrigin(0f, .5f) else TransformOrigin.Center) {
                        ArtCanvas(s.art, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(.7f))))
                        Box(
                            Modifier.offset(16.dp, 14.dp).height(36.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(.55f)).padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { BasicText(s.port, style = jText(24, 800)) }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 16.dp)) { Dot(s.live) }
                        BasicText(s.name, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 14.dp), style = jText(30, 800), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCopy(s: Source) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(.14f)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText(s.port, style = jText(24, 800, tracking = 0.08.em)) }
            Dot(s.live)
            BasicText(s.status, style = jText(24, 700, Color.White.copy(.75f)))
        }
        BasicText(s.name, Modifier.padding(top = 18.dp), style = jText(104, 800, tracking = TitleTracking, lineHeight = 104.sp), maxLines = 1)
        BasicText(s.desc, Modifier.padding(top = 18.dp).widthIn(max = 820.dp), style = jText(28, 500, Color.White.copy(.8f), lineHeight = 39.sp), maxLines = 2)
    }
}

@Composable
private fun Dot(live: Boolean) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(if (live) J.On else Color.White.copy(.3f)))
}
