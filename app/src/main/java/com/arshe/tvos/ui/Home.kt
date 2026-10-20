package com.arshe.tvos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshe.tvos.data.HERO_PX
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.arshe.tvos.data.ContentRow
import com.arshe.tvos.data.KEY_ALL
import com.arshe.tvos.data.KEY_SOURCES
import com.arshe.tvos.data.KEY_TV
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.Images
import com.arshe.tvos.data.Lookup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.arshe.tvos.data.rememberPoster
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private const val X = 96
private const val ROWS_Y = 700          // first row title when the billboard is in charge
private const val FOCUS_ROW_Y = 600     // where the focused row parks while browsing rows
private const val LAND_W = 400
private const val LAND_H = 225
private const val PORT_W = 200
private const val PORT_H = 300
private const val CAROUSEL_MS = 8000L

private fun rowHeight(r: ContentRow) = 46 + (if (r.portrait) PORT_H else LAND_H) + 56

@Composable
fun HomeScreen(l: Launcher, time: String, vpn: Boolean) {
    val hero = l.heroItem
    val rows = l.pageRows
    val inRows = l.zone == Zone.Row

    LaunchedEffect(l.zone, l.heroSel, l.tabIdx, l.screen, l.sheetOpen, rows.size) {
        delay(CAROUSEL_MS)
        l.carouselTick()
    }

    Box(Modifier.fillMaxSize()) {
        if (hero != null) Billboard(hero)

        // Everything above the moving billboard is cached as one GPU texture: while only the
        // background animates, each frame is a texture blit instead of re-drawing every
        // row, card and text.
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {

        // Copy: always visible, it describes whatever is focused.
        Box(Modifier.offset(X.dp, 170.dp).size(1100.dp, 390.dp), contentAlignment = Alignment.BottomStart) {
            HeroCopy(hero)
        }

        val btnA by animateFloatAsState(if (inRows) 0f else 1f, tween(250), label = "btnA")
        Row(
            Modifier.offset(X.dp, 590.dp).graphicsLayer { alpha = btnA },
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            l.heroButtons(hero).forEachIndexed { i, label ->
                JButton(label, focused = l.zone == Zone.Hero && l.heroBtn == i, icon = if (i == 0) { ink -> PlayIcon(ink) } else null, bold = i == 0)
            }
        }

        // Rows: parked under the billboard, the focused one slides up to FOCUS_ROW_Y.
        val tops = rows.runningFold(ROWS_Y) { acc, r -> acc + rowHeight(r) }
        val target = if (inRows) FOCUS_ROW_Y - (tops.getOrNull(l.rowFocus) ?: ROWS_Y) else 0
        val shift by animateFloatAsState(target.toFloat(), tween(480, easing = J.Glide), label = "rowsY")
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = shift }) {
            rows.forEachIndexed { r, row ->
                key(l.tabIdx, row.title) {
                    val a by animateFloatAsState(if (inRows && r < l.rowFocus) 0f else 1f, tween(300), label = "rowA")
                    Column(Modifier.offset(X.dp, tops[r].dp).graphicsLayer { alpha = a }) {
                        RowTitle(row.title, row.brand)
                        Spacer(Modifier.height(16.dp))
                        val focused = if (inRows && l.rowFocus == r) l.rowIdx[r] ?: 0 else null
                        val idx = l.rowIdx[r] ?: 0
                        Strip(
                            count = row.items.size,
                            focused = focused,
                            window = 0,
                            anchor = idx,
                            w = if (row.portrait) PORT_W else LAND_W,
                            h = if (row.portrait) PORT_H else LAND_H,
                            scale = 1.08f,
                            gap = 24,
                        ) { i, f ->
                            if (row.portrait) PosterCard(row.items[i], f) else LandscapeCard(row.items[i], f)
                        }
                    }
                }
            }
        }

        // Top bar fades over the billboard.
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(0f to Color.Black.copy(.75f), 1f to Color.Transparent)))
        TopBar(l, time, vpn)
        }
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun TopBar(l: Launcher, time: String, vpn: Boolean) {
    val nav = l.nav
    val selectedKey = l.tab?.key
    Row(
        Modifier.fillMaxWidth().padding(start = X.dp, end = X.dp, top = 44.dp).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Content apps on the left; shortcut icons pinned to the right, next to the clock.
        val tabs = nav.withIndex().filter { it.value.isTab }
        val shortcuts = nav.withIndex().filter { !it.value.isTab }
        tabs.forEach { (i, item) ->
            NavChip(item, focused = l.zone == Zone.Nav && l.navIdx == i, selected = item.tile.key == selectedKey)
        }
        Spacer(Modifier.weight(1f))
        shortcuts.forEach { (i, item) ->
            NavChip(item, focused = l.zone == Zone.Nav && l.navIdx == i, selected = false)
        }
        Spacer(Modifier.width(24.dp))
        if (vpn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 24.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(J.On))
            BasicText("VPN", style = jText(24, 700, Color.White.copy(.85f)))
        }
        BasicText(time, style = jText(28, 700, Color.White.copy(.9f)))
    }
}

/**
 * Top-bar entry. Content apps show their real icon and name; shortcuts are icon-only and
 * reveal their name while focused.
 */
@Composable
private fun NavChip(item: NavItem, focused: Boolean, selected: Boolean) {
    val s by animateFloatAsState(if (focused) 1.06f else 1f, tween(200, easing = J.ButtonSpring), label = "nav")
    val ink = when {
        focused -> Color.Black
        selected -> Color.White
        !item.isTab -> Color.White.copy(.6f)
        else -> Color.White.copy(.72f)
    }
    val showLabel = item.isTab || focused
    Box(contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.graphicsLayer { scaleX = s; scaleY = s }
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(if (focused) Color.White else Color.Transparent)
                .padding(horizontal = if (item.isTab) 18.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !item.isTab -> NavGlyph(item.tile.key, ink)
                item.tile.brand.icon != null -> Box(Modifier.graphicsLayer { alpha = if (selected || focused) 1f else .75f }) { BrandBadge(item.tile.brand, 34) }
            }
            AnimatedVisibility(showLabel, enter = fadeIn(tween(180)) + expandHorizontally(tween(220)), exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180))) {
                BasicText(item.label, style = jText(28, if (selected || focused) 800 else 600, ink))
            }
        }
        // Selected app: a short brand-coloured underline.
        val u by animateFloatAsState(if (selected && !focused) 1f else 0f, tween(250), label = "u")
        Box(Modifier.offset(y = 6.dp).graphicsLayer { alpha = u; scaleX = .4f + .6f * u }.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(item.tile.brand.color))
    }
}

/** Line icons for the top-bar shortcuts (30×30). */
@Composable
private fun NavGlyph(key: String, ink: Color) {
    Canvas(Modifier.size(30.dp)) {
        val st = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (key) {
            SEARCH_TILE.key -> {
                drawCircle(ink, 9f, Offset(13f, 13f), style = st)
                drawLine(ink, Offset(20f, 20f), Offset(27f, 27f), 2.8f, StrokeCap.Round)
            }
            KEY_TV -> {
                drawRoundRect(ink, Offset(3f, 5f), Size(24f, 16f), CornerRadius(3f), style = st)
                drawLine(ink, Offset(10f, 26f), Offset(20f, 26f), 2.6f, StrokeCap.Round)
            }
            KEY_SOURCES -> {
                drawPath(Path().apply {
                    moveTo(3f, 9f); lineTo(27f, 9f); lineTo(27f, 17f); lineTo(23f, 22f); lineTo(7f, 22f); lineTo(3f, 17f); close()
                }, ink, style = st)
                drawLine(ink, Offset(10f, 14f), Offset(20f, 14f), 2.6f, StrokeCap.Round)
            }
            KEY_ALL -> listOf(4f to 4f, 17f to 4f, 4f to 17f, 17f to 17f).forEach { (x, y) ->
                drawRoundRect(ink, Offset(x, y), Size(9f, 9f), CornerRadius(2.5f))
            }
            else -> {
                listOf(8f, 15f, 22f).forEach { y -> drawLine(ink, Offset(4f, y), Offset(26f, y), 2.6f, StrokeCap.Round) }
                listOf(10f to 8f, 20f to 15f, 13f to 22f).forEach { (x, y) ->
                    drawCircle(if (ink == Color.Black) Color.White else Color.Black, 3.2f, Offset(x, y))
                    drawCircle(ink, 3.2f, Offset(x, y), style = Stroke(2.4f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- billboard

@Composable
fun Billboard(item: HeroItem) {
    Box(Modifier.fillMaxSize()) {
        // Load first, swap after: the previous image stays until the next one is decoded, so
        // switching never flashes black or a placeholder. No artwork at all → plain black.
        val context = LocalContext.current
        val loaded by produceState<Slide?>(null, item.key, item.poster) {
            val mgId = item.key.removePrefix("mg:").takeIf { item.key.startsWith("mg:") }
            val backdrop = mgId?.let { withContext(Dispatchers.IO) { Lookup.megogoBackdrop(context, it) } }
            val url = backdrop ?: item.poster
            val bmp = url?.let { Images.load(context, it, HERO_PX) }
            value = Slide(item.key, bmp, if (backdrop != null) 0f else item.heroBias)
        }
        var shown by remember { mutableStateOf<Slide?>(null) }
        LaunchedEffect(loaded) { loaded?.let { shown = it } }
        Crossfade(shown, animationSpec = tween(600), label = "billboard") { slide ->
            val bmp = slide?.image ?: return@Crossfade
            KenBurns {
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.3f, slide.bias))
            }
        }
        // Scrims baked once: left for the copy, bottom for the rows.
        Spacer(Modifier.fillMaxSize().drawWithCache {
            val bmp = BitmapCache.get("billboard-scrim", size) {
                drawRect(Brush.horizontalGradient(0f to Color.Black.copy(.92f), .32f to Color.Black.copy(.6f), .62f to Color.Transparent))
                drawRect(Brush.verticalGradient(.42f to Color.Transparent, .66f to Color.Black.copy(.75f), .8f to Color.Black))
            }
            onDrawBehind { drawCached(bmp) }
        })
    }
}

@Composable
private fun HeroCopy(hero: HeroItem?) {
    AnimatedContent(
        targetState = hero,
        contentKey = { it?.key },
        transitionSpec = {
            (fadeIn(tween(380, 220)) + slideInVertically(tween(560, 220, J.CopyIn)) { 14 }) togetherWith fadeOut(tween(160))
        },
        contentAlignment = Alignment.BottomStart,
        label = "copy",
    ) { item ->
        if (item == null) return@AnimatedContent
        val long = item.title.length > 32
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BrandBadge(item.brand, 40)
                BasicText(item.app.uppercase(), style = jText(24, 800, Color.White.copy(.8f), tracking = 0.2.em))
                if (item.live) LivePill()
            }
            BasicText(
                item.title,
                Modifier.padding(top = 18.dp).widthIn(max = 1000.dp),
                style = jText(if (long) 64 else 92, 800, tracking = TitleTracking, lineHeight = if (long) 68.sp else 92.sp)
                    .copy(shadow = Shadow(Color.Black.copy(.5f), Offset(0f, 4f), 24f)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.meta.isNotEmpty()) BasicText(item.meta, Modifier.padding(top = 18.dp), style = jText(26, 600, Color.White.copy(.75f)), maxLines = 1)
            if (item.desc.isNotEmpty()) BasicText(
                item.desc,
                Modifier.padding(top = 14.dp).widthIn(max = 860.dp),
                style = jText(28, 500, Color.White.copy(.85f), lineHeight = 39.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class Slide(val key: String, val image: ImageBitmap?, val bias: Float)

// ---------------------------------------------------------------- rows

/**
 * Horizontal card strip. With window = 0 the focused card is pinned to the left edge
 * (Netflix-style); cards right of the focused one slide over so it never overlaps them.
 */
@Composable
fun Strip(
    count: Int,
    focused: Int?,
    window: Int,
    anchor: Int,
    w: Int,
    h: Int,
    scale: Float,
    modifier: Modifier = Modifier,
    gap: Int = 32,
    card: @Composable (Int, Boolean) -> Unit,
) {
    val step = w + gap
    val scroll = (anchor - window).coerceAtLeast(0)
    val push = w * (scale - 1f) + 6f
    val x by animateFloatAsState(-scroll * step.toFloat(), tween(380, easing = J.Glide), label = "stripX")
    Box(modifier.width(1824.dp).height((h + 24).dp)) {
        Box(Modifier.graphicsLayer { translationX = x }) {
            for (i in 0 until count) {
                if (i < scroll - 1 || i > scroll + 10) continue
                key(i) {
                    val a by animateFloatAsState(if (i < scroll) 0f else 1f, tween(250), label = "a")
                    val dx by animateFloatAsState(if (focused != null && i > focused) push else 0f, tween(220, easing = J.FocusSpring), label = "dx")
                    Box(Modifier.offset((i * step).dp, 0.dp).graphicsLayer { alpha = a; translationX = dx }) {
                        card(i, focused == i)
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeCard(item: HeroItem, focused: Boolean) {
    FocusCard(focused, LAND_W.dp, LAND_H.dp) {
        val thumb by rememberPoster(Images.cardUrl(item.poster), 400)
        val t = thumb
        if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (item.poster == null) ArtCanvas(item.art, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(.75f))))
        if (item.live) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { LivePill() }
        BasicText(
            item.title,
            Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = if (item.progress != null) 30.dp else 16.dp),
            style = jText(26, 800).copy(shadow = Shadow(Color.Black.copy(.6f), Offset(0f, 2f), 10f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.progress?.let {
            ProgressBar(it, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 14.dp).width((LAND_W - 36).dp))
        }
    }
}

@Composable
private fun PosterCard(item: HeroItem, focused: Boolean) {
    FocusCard(focused, PORT_W.dp, PORT_H.dp) {
        val img by rememberPoster(item.cover ?: item.poster, 200)
        val t = img
        if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (item.cover == null && item.poster == null) {
            ArtCanvas(item.art, Modifier.fillMaxSize())
            BasicText(
                item.title,
                Modifier.align(Alignment.Center).padding(12.dp),
                style = jText(24, 800).copy(textAlign = TextAlign.Center),
                maxLines = 4,
            )
        }
    }
}

@Composable
fun AppCard(item: HeroItem, focused: Boolean, w: Int, h: Int, scale: Float, lift: Float = 0f) {
    FocusCard(focused, w.dp, h.dp, scale = scale, lift = lift) {
        ArtCanvas(item.art, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(.14f), .4f to Color.Transparent)))
        BasicText(
            item.title,
            Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
            style = jText(if (w > 300) 32 else 28, 800, tracking = (-0.01).em).copy(
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(.45f), Offset(0f, 2f), 14f),
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Dots(n: Int, active: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until n) {
            val wDot by animateDpAsState(if (i == active) 28.dp else 8.dp, tween(300), label = "dot")
            Box(Modifier.size(wDot, 8.dp).clip(CircleShape).background(Color.White.copy(if (i == active) .9f else .35f)))
        }
    }
}
