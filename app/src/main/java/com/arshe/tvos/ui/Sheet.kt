package com.arshe.tvos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun ControlSheet(l: Launcher, time: String) {
    val scrim by animateFloatAsState(if (l.sheetOpen) .62f else 0f, tween(320), label = "scrim")
    if (scrim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(scrim)))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = l.sheetOpen,
            enter = fadeIn(tween(320)) + slideInHorizontally(tween(320, easing = J.SheetIn)) { 60 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { 60 },
        ) {
            Column(
                Modifier.width(700.dp).fillMaxHeight()
                    .shadow(120.dp, ambientColor = Color.Black, spotColor = Color.Black)
                    .background(J.Panel)
                    .padding(start = 48.dp, end = 96.dp, top = 54.dp, bottom = 54.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
                    BasicText("Быстрые настройки", Modifier.weight(1f), style = jText(40, 800))
                    BasicText(time, style = jText(28, 600, Color.White.copy(.6f)))
                }
                l.sheetRows.forEachIndexed { i, row -> SheetRowView(row, i == l.sheetFocus) }
                Spacer(Modifier.weight(1f))
                BasicText("Menu или Back — закрыть", Modifier.padding(horizontal = 20.dp), style = jText(24, 600, Color.White.copy(.45f)))
            }
        }
    }
}

@Composable
private fun SheetRowView(row: SheetRow, on: Boolean) {
    if (row.gapBefore) Spacer(Modifier.height(14.dp))
    val s by animateFloatAsState(if (on) 1.02f else 1f, tween(200, easing = J.ButtonSpring), label = "rs")
    val ink = if (on) Color.Black else Color.White
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp)
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Color.White else Color.Transparent)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelInk = if (row.danger) (if (on) Color(0xFFC8102E) else Color(0xFFFF5A5F)) else ink
        BasicText(row.label, Modifier.weight(1f), style = jText(28, 700, labelInk), maxLines = 1)
        row.value?.let {
            val shown = if (row.adjust != null && on) "‹  $it  ›" else it
            BasicText(shown, style = jText(26, if (row.adjust != null) 700 else 600, ink.copy(if (on) .8f else .6f)), maxLines = 1)
        }
        row.toggle?.let { Toggle(it, on) }
        row.slider?.let { (v, max) -> Slider(v, max, on) }
        row.chips?.let { Chips(it, row.chip, on) }
    }
}

@Composable
private fun Toggle(value: Boolean, focused: Boolean) {
    val x by animateFloatAsState(if (value) 30f else 0f, tween(220, easing = J.FocusSpring), label = "knob")
    Box(
        Modifier.size(68.dp, 38.dp).clip(RoundedCornerShape(19.dp))
            .background(if (value) J.Accent else if (focused) Color.Black.copy(.18f) else Color.White.copy(.2f)),
    ) {
        Box(Modifier.offset(4.dp, 4.dp).graphicsLayer { translationX = x }.size(30.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun Slider(value: Int, max: Int, focused: Boolean) {
    val f by animateFloatAsState(value / max.toFloat(), tween(160), label = "slider")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(220.dp, 8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (focused) Color.Black.copy(.15f) else Color.White.copy(.18f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(if (focused) Color.Black else Color.White))
        }
        BasicText("$value", Modifier.width(36.dp), style = jText(26, 800, if (focused) Color.Black else Color.White))
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, focused: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            sel && focused -> Color.Black
                            sel -> Color.White
                            focused -> Color.Black.copy(.08f)
                            else -> Color.White.copy(.1f)
                        },
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = jText(24, 800, when {
                        sel && focused -> Color.White
                        sel -> Color.Black
                        focused -> Color.Black.copy(.65f)
                        else -> Color.White.copy(.7f)
                    }),
                )
            }
        }
    }
}
