package com.arshe.tvos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshe.tvos.data.DetailsRepository
import com.arshe.tvos.data.Episode
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.rememberPoster

private const val EP_W = 360
private const val EP_H = 203

@Composable
fun DetailScreen(l: Launcher) {
    val item = l.detailItem ?: return
    val d = l.detail
    val lower = l.dZone == 2
    val page by animateFloatAsState(if (lower) -250f else 0f, tween(460, easing = J.Glide), label = "page")
    val backdrop = d?.backdrop ?: item.poster

    Box(Modifier.fillMaxSize()) {
        Billboard(item.copy(key = item.key + "#detail", poster = backdrop, heroBias = if (d?.backdrop != null) 0f else item.heroBias))

        Box(Modifier.fillMaxSize().graphicsLayer { translationY = page }) {
            val headA by animateFloatAsState(if (lower) 0f else 1f, tween(300), label = "head")
            Column(Modifier.offset(96.dp, 96.dp).widthIn(max = 1150.dp)) {
                Row(Modifier.graphicsLayer { alpha = headA }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BrandBadge(item.brand, 40)
                    val kind = d?.kind?.let { " · $it" }.orEmpty()
                    BasicText((item.app + kind).uppercase(), style = jText(24, 800, Color.White.copy(.8f), tracking = 0.2.em))
                }
                val long = item.title.length > 28
                BasicText(
                    item.title,
                    Modifier.padding(top = 18.dp).graphicsLayer { alpha = headA },
                    style = jText(if (long) 64 else 92, 800, tracking = TitleTracking, lineHeight = if (long) 68.sp else 92.sp)
                        .copy(shadow = Shadow(Color.Black.copy(.5f), Offset(0f, 4f), 24f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Facts: year, age, seasons/runtime, IMDb, genres.
                AnimatedContent(d, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "facts") { dd ->
                    Row(
                        Modifier.padding(top = 22.dp).height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (dd == null) {
                            BasicText(if (l.detailLoading) "Загружаю подробности…" else item.meta, style = jText(26, 600, Color.White.copy(.6f)))
                        } else {
                            dd.chips.forEachIndexed { i, c -> if (i == 0) BasicText(c, style = jText(26, 700, Color.White.copy(.85f))) else Chip(c) }
                            dd.imdb?.let { Chip("IMDb $it", gold = true) }
                            if (dd.genres.isNotBlank()) BasicText(dd.genres, style = jText(26, 600, Color.White.copy(.7f)), maxLines = 1)
                        }
                    }
                }
                // In the episodes row the description follows the focused episode.
                val ep = if (lower) l.detailRow.getOrNull(l.dIdx) as? Episode else null
                val desc = ep?.let { "E${it.number} · ${it.title}" + (if (it.desc.isNotBlank()) " — ${it.desc}" else "") }
                    ?: d?.desc?.takeIf { it.isNotBlank() } ?: item.desc
                if (desc.isNotBlank()) BasicText(
                    desc,
                    Modifier.padding(top = 20.dp).widthIn(max = 1000.dp),
                    style = jText(28, 500, Color.White.copy(.85f), lineHeight = 40.sp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                d?.cast?.let {
                    BasicText("В ролях: $it", Modifier.padding(top = 14.dp).widthIn(max = 1000.dp), style = jText(24, 600, Color.White.copy(.55f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.progress?.let { p ->
                    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        BasicText(item.meta.removePrefix("Вы смотрели · "), style = jText(26, 700))
                        ProgressBar(p, Modifier.width(260.dp))
                    }
                }
            }

            Row(Modifier.offset(96.dp, 620.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                JButton(if (item.progress != null) "Продолжить" else "Смотреть", l.dZone == 0 && l.dBtn == 0, icon = { ink -> PlayIcon(ink) })
                JButton("Открыть в ${l.ownerOf(item)?.title ?: item.app}", l.dZone == 0 && l.dBtn == 1, bold = false)
            }

            val seasons = d?.seasons.orEmpty()
            if (seasons.isNotEmpty()) {
                Row(
                    Modifier.offset(96.dp, 740.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText("Серии", Modifier.padding(end = 12.dp), style = jText(30, 700))
                    seasons.forEachIndexed { i, s -> SeasonPill(s.title, selected = i == l.dSeason, focused = l.dZone == 1 && i == l.dSeason) }
                }
                Strip(
                    count = l.episodes.size,
                    focused = if (lower) l.dIdx else null,
                    window = 1,
                    anchor = l.dIdx,
                    w = EP_W, h = EP_H, scale = 1.08f, gap = 24,
                    modifier = Modifier.offset(96.dp, 806.dp),
                ) { i, f -> EpisodeCard(l.episodes[i], i, f, backdrop) }
            } else if (d?.similar?.isNotEmpty() == true) {
                BasicText("Похожее", Modifier.offset(96.dp, 740.dp), style = jText(30, 700))
                Strip(
                    count = d.similar.size,
                    focused = if (lower) l.dIdx else null,
                    window = 0,
                    anchor = l.dIdx,
                    w = 400, h = 225, scale = 1.08f, gap = 24,
                    modifier = Modifier.offset(96.dp, 796.dp),
                ) { i, f -> SimilarCard(d.similar[i], f) }
            }
        }

        BasicText("Back — назад", Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 96.dp), style = jText(24, 700, Color.White.copy(.5f)))
    }
}

@Composable
private fun Chip(text: String, gold: Boolean = false) {
    val c = if (gold) Color(0xFFF5C518) else Color.White.copy(.55f)
    Box(
        Modifier.height(36.dp).border(2.dp, c, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = jText(24, 700, if (gold) c else Color.White.copy(.85f))) }
}

@Composable
private fun SeasonPill(text: String, selected: Boolean, focused: Boolean) {
    val s by animateFloatAsState(if (focused) 1.08f else 1f, tween(200, easing = J.ButtonSpring), label = "pill")
    Box(
        Modifier.graphicsLayer { scaleX = s; scaleY = s }
            .height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color.White else Color.White.copy(.1f))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = jText(24, if (selected) 800 else 700, if (selected) Color.Black else Color.White.copy(.65f))) }
}

@Composable
private fun EpisodeCard(e: Episode, i: Int, focused: Boolean, fallback: String?) {
    Box {
        FocusCard(focused, EP_W.dp, EP_H.dp) {
            val img by rememberPoster(e.image ?: fallback, if (e.image != null) EP_W else 800)
            val t = img
            if (t != null) {
                // Without per-episode stills, show a different zoomed-in region of the show's backdrop.
                val zoom = if (e.image != null) 1f else 1.9f
                val fx = if (e.image != null) 0f else ((i * 0.618f) % 1f) * 2f - 1f
                val fy = if (e.image != null) 0f else ((i * 0.382f) % 1f) * 2f - 1f
                Image(
                    t, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = fx * size.width * (zoom - 1f) / 2f
                        translationY = fy * size.height * (zoom - 1f) / 2f
                    },
                    contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(.75f))))
            BasicText(
                "${e.number}",
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 6.dp),
                style = jText(72, 800, Color.White.copy(.9f)).copy(drawStyle = Stroke(2.5f)),
            )
        }
        val capA by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "cap")
        Column(Modifier.width((EP_W + 40).dp).graphicsLayer { alpha = capA; translationY = (EP_H + 26).toFloat() }) {
            BasicText("E${e.number} · ${e.title}", style = jText(26, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.minutes?.let { BasicText(DetailsRepository.minutes(it), Modifier.padding(top = 4.dp), style = jText(24, 500, Color.White.copy(.6f))) }
        }
    }
}

@Composable
private fun SimilarCard(item: HeroItem, focused: Boolean) {
    FocusCard(focused, 400.dp, 225.dp) {
        val img by rememberPoster(item.poster, 400)
        val t = img
        if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else ArtCanvas(item.art, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(.75f))))
        BasicText(item.title, Modifier.align(Alignment.BottomStart).padding(18.dp), style = jText(26, 800), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
