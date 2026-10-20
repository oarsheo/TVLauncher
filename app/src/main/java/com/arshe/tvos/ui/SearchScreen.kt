package com.arshe.tvos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.Images
import com.arshe.tvos.data.rememberPoster

private const val KEY_W = 74
private const val KEY_H = 64
private const val KEY_GAP = 8
private const val RESULTS_X = 680
private const val CARD_W = 320
private const val CARD_H = 180
private const val POSTER_W = 150
private const val POSTER_H = 225

@Composable
fun SearchScreen(l: Launcher) {
    val inResults = l.zone == Zone.Row
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Soft glow of the focused result behind everything.
        val focusedItem = if (inResults) l.results.getOrNull(l.sRow)?.items?.getOrNull(l.sIdx[l.sRow] ?: 0) else null
        if (focusedItem != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = .35f }) { Billboard(focusedItem) }

        Column(Modifier.offset(96.dp, 54.dp).width(((KEY_W + KEY_GAP) * KB_COLS - KEY_GAP).dp)) {
            BasicText("Поиск", style = jText(48, 800, tracking = TitleTracking))
            QueryField(l.query, l.searching)
            Spacer(Modifier.height(24.dp))
            Keyboard(l)
            BasicText(
                "Цифры — кнопками пульта · Back — стереть",
                Modifier.padding(top = 20.dp),
                style = jText(22, 600, Color.White.copy(.45f)),
            )
        }

        Results(l, Modifier.offset(RESULTS_X.dp, 54.dp))
    }
}

@Composable
private fun QueryField(query: String, searching: Boolean) {
    Row(
        Modifier.padding(top = 18.dp).fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(.1f)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (query.isEmpty()) BasicText("Фильм, сериал или видео", style = jText(30, 600, Color.White.copy(.4f)))
        else BasicText(query + "▏", Modifier.weight(1f), style = jText(34, 800), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (searching) BasicText("…", style = jText(34, 800, Color.White.copy(.6f)))
    }
}

@Composable
private fun Keyboard(l: Launcher) {
    val keys = KEYBOARDS[l.kb]
    val active = l.zone != Zone.Row
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP.dp)) {
        keys.chunked(KB_COLS).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP.dp)) {
                row.forEachIndexed { c, k ->
                    val i = r * KB_COLS + c
                    KeyCap(label(k, l.kb), focused = active && i == l.kbIdx, control = k.length > 1 || k in setOf(KEY_SPACE, KEY_DEL, KEY_CLEAR))
                }
            }
        }
    }
}

private fun label(k: String, kb: Int) = when (k) {
    KEY_LANG -> if (kb == 1) "РУС" else "ENG"
    KEY_NUM -> if (kb == 2) "АБВ" else "123"
    else -> k
}

@Composable
private fun KeyCap(text: String, focused: Boolean, control: Boolean) {
    val s by animateFloatAsState(if (focused) 1.12f else 1f, tween(160, easing = J.ButtonSpring), label = "key")
    Box(
        Modifier.size(KEY_W.dp, KEY_H.dp)
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.White.copy(if (control) .14f else .07f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = jText(if (text.length > 1) 22 else 30, 700, if (focused) Color.Black else Color.White).copy(textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun Results(l: Launcher, modifier: Modifier) {
    val rows = l.results
    val inResults = l.zone == Zone.Row
    if (rows.isEmpty()) {
        Box(modifier.size(1150.dp, 900.dp), contentAlignment = Alignment.Center) {
            val hint = when {
                l.searching -> "Ищу…"
                l.query.trim().length >= 2 -> "Ничего не нашлось"
                else -> "Начните вводить название —\nищем сразу в Netflix, MEGOGO, Apple TV+ и YouTube"
            }
            BasicText(hint, style = jText(30, 600, Color.White.copy(.5f)).copy(textAlign = TextAlign.Center))
        }
        return
    }
    // The focused row slides to the top of the results column.
    val heights = rows.map { 46 + (if (it.portrait) POSTER_H else CARD_H) + 48 }
    val tops = heights.runningFold(0) { a, h -> a + h }
    val shift by animateFloatAsState(if (inResults) -tops[l.sRow].toFloat() else 0f, tween(420, easing = J.Glide), label = "res")
    Box(modifier.width(1180.dp).height(1000.dp)) {
        Box(Modifier.graphicsLayer { translationY = shift }) {
            rows.forEachIndexed { r, row ->
                key(row.title) {
                    val a by animateFloatAsState(if (inResults && r < l.sRow) 0f else 1f, tween(250), label = "ra")
                    Column(Modifier.offset(0.dp, tops[r].dp).graphicsLayer { alpha = a }) {
                        RowTitle(row.title, row.brand, size = 28)
                        Spacer(Modifier.height(14.dp))
                        val idx = l.sIdx[r] ?: 0
                        Strip(
                            count = row.items.size,
                            focused = if (inResults && l.sRow == r) idx else null,
                            window = 0,
                            anchor = idx,
                            w = if (row.portrait) POSTER_W else CARD_W,
                            h = if (row.portrait) POSTER_H else CARD_H,
                            scale = 1.08f,
                            gap = 20,
                        ) { i, f -> ResultCard(row.items[i], f, row.portrait) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(item: HeroItem, focused: Boolean, portrait: Boolean) {
    val w = if (portrait) POSTER_W else CARD_W
    val h = if (portrait) POSTER_H else CARD_H
    FocusCard(focused, w.dp, h.dp) {
        val img by rememberPoster(if (portrait) item.cover ?: item.poster else Images.cardUrl(item.poster), w)
        val t = img
        if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (!portrait) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(.75f))))
            BasicText(item.title, Modifier.align(Alignment.BottomStart).padding(14.dp), style = jText(22, 800), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
