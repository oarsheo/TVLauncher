package com.arshe.cams

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

private val Green = Color(0xFF34C759)
private val Amber = Color(0xFFFFB020)
private val Red = Color(0xFFFF453A)

private fun manrope(weight: Int) = Font(R.font.manrope, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
private val Manrope = FontFamily(manrope(500), manrope(600), manrope(700), manrope(800))

private fun text(size: Int, weight: Int, color: Color = Color.White) =
    TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight(weight), fontFamily = Manrope, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum")

/** Screen state: the camera wall, which tile has focus and which camera (if any) is fullscreen. */
class Wall {
    var nvr by mutableStateOf<Nvr?>(null)
    var cams by mutableStateOf(emptyList<Camera>())
    var problem by mutableStateOf<String?>(null)
    var focus by mutableIntStateOf(0)
    var full by mutableStateOf<Int?>(null)
    var muted by mutableStateOf(false)
    var weather by mutableStateOf<Weather?>(null)
    var weatherAt = 0L
    var dim by mutableStateOf(false)
    var keyAt = 0L                                  // last real key press
    val alarms = mutableStateMapOf<String, Long>()  // camera id -> last person seen
    var autoFull by mutableStateOf(false)           // fullscreen opened by an alarm
    var lastEvent by mutableStateOf<String?>(null)
    var lastInput by mutableLongStateOf(0L)
    val links = mutableStateMapOf<String, Link>()

    val cols get() = ceil(sqrt(cams.size + 1.0)).toInt().coerceAtLeast(1)

    /** Cell [i] of a cols×cols grid of 16:9 tiles (the info panel takes the rest of the last row). */
    fun cell(i: Int, span: Int = 1): Modifier {
        val cw = 1920f / cols
        val ch = 1080f / cols
        return Modifier.offset((i % cols * cw).dp, (i / cols * ch).dp).size((span * cw).dp, ch.dp)
    }
    val online get() = cams.count { links[it.id] == Link.Live }
}

class MainActivity : ComponentActivity() {
    private val wall = Wall()
    private var events: Events? = null
    private var voice: Announcer? = null
    private val spoken = HashMap<String, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Video and everything drawn over it live in two views: a SurfaceView punches its hole
        // after its Compose siblings are drawn, so labels in the same view would be erased.
        setContentView(FrameLayout(this).apply {
            addView(canvas { Videos(wall) })
            addView(canvas {
                Overlay(wall)
                LaunchedEffect(Unit) { load() }
                LaunchedEffect(wall.nvr) { wall.nvr?.let { weather(it) } }
                LaunchedEffect(wall.nvr) { wall.nvr?.let { watch(it) } }
            })
        })
    }

    /** Design canvas: 1920×1080 dp regardless of the output resolution. */
    private fun canvas(content: @Composable () -> Unit) = ComposeView(this).apply {
        setContent {
            val dm = resources.displayMetrics
            CompositionLocalProvider(LocalDensity provides Density(dm.widthPixels / 1920f, 1f), content = content)
        }
    }

    private suspend fun load() {
        val nvr = withContext(Dispatchers.IO) { Nvr.load(this@MainActivity) }
        if (nvr == null) {
            wall.problem = "Нет настроек NVR\nscripts/push-cams-config.sh"
            return
        }
        wall.cams = withContext(Dispatchers.IO) { CameraCache.load(this@MainActivity) }
        wall.nvr = nvr
        while (true) {
            val fresh = withContext(Dispatchers.IO) { runCatching { Onvif.cameras(nvr) } }
            fresh.onSuccess {
                if (it.isNotEmpty()) {
                    if (it != wall.cams) wall.cams = it
                    CameraCache.save(this, it)
                    wall.problem = null
                    return
                }
            }
            if (wall.cams.isEmpty()) wall.problem = "NVR ${nvr.host} не отвечает\n${fresh.exceptionOrNull()?.message ?: "камер нет"}"
            delay(10_000)
        }
    }

    /** Person alarms on the cameras listed in nvr.alarm: voice, red tile, camera fullscreen. */
    private fun watch(nvr: Nvr) {
        if (nvr.alarms.isEmpty()) return
        voice = Announcer(this)
        events = Events(nvr) { source, confidence ->
            if (confidence >= 60) main.post { person(nvr, source) }
        }.also { it.start() }
    }

    private fun person(nvr: Nvr, source: String) {
        val w = wall
        val i = w.cams.indexOfFirst { it.id == source }
        if (i < 0) return
        val cam = w.cams[i]
        val phrase = nvr.alarms[cam.name] ?: return
        val now = System.currentTimeMillis()
        w.alarms[cam.id] = now
        w.lastEvent = "Человек · ${cam.name} · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))}"
        w.lastInput = now // wakes the wall from night dimming
        if (now - (spoken[cam.id] ?: 0L) > 60_000) {
            spoken[cam.id] = now
            voice?.say(phrase)
        }
        // Show the camera unless someone is busy with the remote.
        if (w.full != i && now - w.keyAt > 30_000) {
            w.full = i
            w.focus = i
            w.autoFull = true
        }
    }

    // Test hook: adb shell am start -n com.arshe.cams/.MainActivity --es alarm "Главный вход"
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val name = intent.getStringExtra("alarm") ?: return
        val nvr = wall.nvr ?: return
        if (voice == null) voice = Announcer(this)
        wall.cams.firstOrNull { it.name == name }?.let { person(nvr, it.id) }
    }

    override fun onDestroy() {
        events?.stop()
        voice?.shutdown()
        super.onDestroy()
    }

    private suspend fun weather(nvr: Nvr) {
        while (true) {
            val w = withContext(Dispatchers.IO) { runCatching { Weather.fetch(nvr.lat, nvr.lon) } }
            // Keep the last reading through short outages; drop it once it's an hour stale.
            w.onSuccess { wall.weather = it; wall.weatherAt = System.currentTimeMillis() }
            if (w.isFailure && System.currentTimeMillis() - wall.weatherAt > 3_600_000) wall.weather = null
            delay(if (w.isSuccess) 15 * 60_000L else 60_000L)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val w = wall
        val n = w.cams.size
        if (n == 0) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down) {
            w.lastInput = System.currentTimeMillis()
            w.keyAt = w.lastInput
            w.autoFull = false
        }
        val full = w.full
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (full == null) return super.dispatchKeyEvent(e)
                if (!down) w.full = null
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) w.full = if (full == null) w.focus.coerceIn(0, n - 1) else null
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (down) {
                    if (full != null) { w.full = (full + d + n) % n; w.focus = w.full!! }
                    else w.focus = (w.focus + d).coerceIn(0, n - 1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (full != null) {
                    if (down && e.repeatCount == 0) w.muted = !w.muted
                    return true
                }
                val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -w.cols else w.cols
                if (down && full == null) (w.focus + d).takeIf { it in 0 until n }?.let { w.focus = it }
                return true
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val i = e.keyCode - KeyEvent.KEYCODE_1
                if (down && i < n) { w.full = i; w.focus = i }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}

/** Lower layer: just the video surfaces. */
@Composable
private fun Videos(w: Wall) = Box(Modifier.fillMaxSize()) {
    val nvr = w.nvr ?: return@Box
    if (w.cams.isEmpty()) return@Box
    val full = w.full
    if (full != null) {
        val cam = w.cams[full.coerceIn(0, w.cams.size - 1)]
        key(cam.id) {
            LaunchedEffect(Unit) { w.links["full"] = Link.Connecting }
            Video(cam.main, nvr, { w.links["full"] = it }, Modifier.fillMaxSize(), sound = true, muted = w.muted)
        }
    } else {
        w.cams.forEachIndexed { i, cam ->
            key(cam.id) { Video(cam.sub, nvr, { w.links[cam.id] = it }, w.cell(i).padding(2.dp)) }
        }
    }
}

/** Upper layer: labels, focus, clock — transparent wherever video should show. */
@Composable
private fun Overlay(w: Wall) {
    Box(Modifier.fillMaxSize()) {
        val full = w.full
        when {
            w.nvr != null && w.cams.isNotEmpty() && full != null -> FullOverlay(w, w.cams[full.coerceIn(0, w.cams.size - 1)])
            w.nvr != null && w.cams.isNotEmpty() -> {
                w.cams.forEachIndexed { i, cam -> TileOverlay(w, cam, i == w.focus, w.cell(i)) }
                val n = w.cams.size
                val span = w.cols - n % w.cols
                Info(w, w.cell(n, span = span), wide = span >= 2)
            }
            else -> BasicText(
                w.problem ?: "Подключение к NVR…",
                Modifier.align(Alignment.Center),
                style = text(40, 600, Color.White.copy(.7f)).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
    // Night: dim everything (video included) so the monitor doesn't light up the room;
    // any key brings full brightness back for two minutes.
    val nvr = w.nvr
    LaunchedEffect(nvr, w.lastInput) {
        if (nvr == null) return@LaunchedEffect
        while (true) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val night = if (nvr.nightFrom > nvr.nightTo) hour >= nvr.nightFrom || hour < nvr.nightTo else hour in nvr.nightFrom until nvr.nightTo
            w.dim = night && System.currentTimeMillis() - w.lastInput > 2 * 60_000L
            delay(30_000)
        }
    }
    val shade by androidx.compose.animation.core.animateFloatAsState(if (w.dim) .6f else 0f, androidx.compose.animation.core.tween(1500), label = "night")
    if (shade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade)))

    // An alarm's fullscreen goes back to the wall 30 s after the last sighting.
    val alarmCam = w.full?.let { w.cams.getOrNull(it)?.id }
    val seen = alarmCam?.let { w.alarms[it] } ?: 0L
    LaunchedEffect(w.autoFull, seen) {
        if (!w.autoFull) return@LaunchedEffect
        delay(30_000)
        if (w.autoFull) { w.full = null; w.autoFull = false }
    }
    // Repaint tiles when their alarm frame expires.
    LaunchedEffect(w.alarms.values.maxOrNull()) {
        delay(30_000)
        val now = System.currentTimeMillis()
        w.alarms.entries.removeAll { now - it.value >= 30_000 }
    }

    // Fullscreen left alone falls back to the wall.
    LaunchedEffect(w.full, w.lastInput) {
        if (w.full != null) { delay(10 * 60_000L); w.full = null }
    }
}

@Composable
private fun TileOverlay(w: Wall, cam: Camera, focused: Boolean, modifier: Modifier) {
    val link = w.links[cam.id] ?: Link.Connecting
    Box(modifier.padding(2.dp)) {
        if (link != Link.Live) Placeholder(link)
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(.7f)))
                .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 14.dp),
        ) {
            Label(cam.name, link, if (focused) 28 else 24)
        }
        if (focused) Box(Modifier.fillMaxSize().border(4.dp, Color.White, RoundedCornerShape(4.dp)))
        if (w.alarms.containsKey(cam.id)) {
            Box(Modifier.fillMaxSize().border(6.dp, Red, RoundedCornerShape(4.dp)))
            BasicText(
                "Человек",
                Modifier.padding(14.dp).background(Red, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                style = text(24, 800),
            )
        }
    }
}

@Composable
private fun Placeholder(link: Link) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0F12)), contentAlignment = Alignment.Center) {
        BasicText(
            if (link == Link.Offline) "Нет сигнала" else "Подключение…",
            style = text(26, 600, if (link == Link.Offline) Red.copy(.9f) else Color.White.copy(.45f)),
        )
    }
}

@Composable
private fun Label(name: String, link: Link, size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val dot = when (link) { Link.Live -> Green; Link.Connecting -> Amber; Link.Offline -> Red }
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        BasicText(name, style = text(size, 700))
    }
}

@Composable
private fun Info(w: Wall, modifier: Modifier, wide: Boolean) {
    val now by clock()
    Row(modifier.padding(start = 48.dp, top = 28.dp, end = 48.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), style = text(112, 800).copy(lineHeight = 112.sp))
            BasicText(SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date(now)).replaceFirstChar { it.uppercase() }, style = text(34, 600, Color.White.copy(.75f)))
            val on = w.online
            val all = w.cams.size
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(14.dp).background(if (on == all) Green else Amber, CircleShape))
                BasicText("$on из $all камер в сети", style = text(30, 700))
            }
            BasicText("OK — на весь экран  ·  1–${all.coerceAtMost(9)} — камера", Modifier.padding(top = 6.dp), style = text(24, 600, Color.White.copy(.4f)))
            w.lastEvent?.let { BasicText(it, Modifier.padding(top = 4.dp), style = text(24, 700, Red.copy(.85f))) }
        }
        val weather = w.weather
        if (wide && weather != null) {
            Box(Modifier.weight(1f))
            WeatherBlock(weather)
        }
    }
}

@Composable
private fun WeatherBlock(x: Weather) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            BasicText(x.icon, style = TextStyle(fontSize = 84.sp))
            BasicText("${signed(x.temp)}°", style = text(112, 800).copy(lineHeight = 112.sp))
        }
        BasicText(x.text, style = text(34, 600, Color.White.copy(.75f)))
        BasicText("днём ${signed(x.max)}°  ·  ночью ${signed(x.min)}°", Modifier.padding(top = 14.dp), style = text(30, 700))
        BasicText(
            "ощущается ${signed(x.feels)}°  ·  ветер ${x.wind} м/с  ·  осадки ${x.rain}%",
            Modifier.padding(top = 6.dp),
            style = text(24, 600, Color.White.copy(.4f)),
        )
    }
}

private fun signed(t: Int) = if (t > 0) "+$t" else "$t"

@Composable
private fun FullOverlay(w: Wall, cam: Camera) {
    val link = w.links["full"] ?: Link.Connecting
    val now by clock()
    Box(Modifier.fillMaxSize()) {
        if (link != Link.Live) Placeholder(link)
        Box(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Black.copy(.6f), 1f to Color.Transparent))
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Label(cam.name, link, 36)
                if (w.alarms.containsKey(cam.id)) BasicText(
                    "Человек",
                    Modifier.background(Red, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
                    style = text(26, 800),
                )
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                BasicText(if (w.muted) "Звук выкл" else "Звук вкл", style = text(26, 700, if (w.muted) Red.copy(.9f) else Color.White.copy(.6f)))
                BasicText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), style = text(36, 700))
            }
        }
        BasicText(
            "← →  камеры  ·  ↑ ↓  звук  ·  OK — сетка",
            Modifier.align(Alignment.BottomEnd).padding(32.dp),
            style = text(22, 600, Color.White.copy(.35f)),
        )
    }
}

@Composable
private fun clock() = remember { mutableLongStateOf(System.currentTimeMillis()) }.also { s ->
    LaunchedEffect(Unit) {
        while (true) {
            s.longValue = System.currentTimeMillis()
            delay(60_000 - s.longValue % 60_000)
        }
    }
}

private val main = Handler(Looper.getMainLooper())

/** Streams [url] onto a surface for as long as the surface exists. */
private class Player(
    private val url: String,
    private val nvr: Nvr,
    private val sound: Boolean,
    private val report: (Link) -> Unit,
) : SurfaceHolder.Callback {
    private var stream: RtspStream? = null
    var muted = false
        set(v) { field = v; stream?.muted = v }

    override fun surfaceCreated(h: SurfaceHolder) {
        main.post { report(Link.Connecting) }
        stream = RtspStream(url, nvr.user, nvr.pass, h.surface, sound) { l -> main.post { report(l) } }
            .also { it.muted = muted; it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        stream?.stop()
        stream = null
    }
}

@Composable
private fun Video(url: String, nvr: Nvr, onLink: (Link) -> Unit, modifier: Modifier, sound: Boolean = false, muted: Boolean = false) {
    val report by rememberUpdatedState(onLink)
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                val p = Player(url, nvr, sound) { report(it) }.also { it.muted = muted }
                holder.addCallback(p)
                tag = p
            }
        },
        update = { (it.tag as Player).muted = muted },
        modifier = modifier,
    )
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Nvr.load(context)?.autostart != true) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
