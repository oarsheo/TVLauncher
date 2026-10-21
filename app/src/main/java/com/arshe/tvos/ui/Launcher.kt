package com.arshe.tvos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import com.arshe.tvos.data.Action
import androidx.compose.ui.graphics.Color
import com.arshe.tvos.data.AmbiState
import com.arshe.tvos.data.Brand
import com.arshe.tvos.data.AppsRepository
import com.arshe.tvos.data.JointSpace
import com.arshe.tvos.data.LocalAdb
import com.arshe.tvos.data.Maintenance
import com.arshe.tvos.data.Updater
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.ContentRow
import com.arshe.tvos.data.KEY_SETTINGS
import com.arshe.tvos.data.KEY_SOURCES
import com.arshe.tvos.data.KEY_TV
import com.arshe.tvos.data.PKG_APPLETV
import com.arshe.tvos.data.PKG_MEGOGO
import com.arshe.tvos.data.PKG_NETFLIX
import com.arshe.tvos.data.PKG_YOUTUBE
import com.arshe.tvos.data.Recent
import com.arshe.tvos.data.show
import android.net.Uri
import com.arshe.tvos.data.Source
import com.arshe.tvos.data.JustWatchSource
import com.arshe.tvos.data.MegogoSource
import com.arshe.tvos.data.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Home, Apps, Sources, Search }

/** Nav = top app bar, Hero = buttons under the billboard, Row = content rows / apps grid / inputs, Rail = left menu. */
enum class Zone { Rail, Nav, Hero, Row }

/** A quick-settings row. ←/→ call [adjust]; OK calls [run]. */
data class SheetRow(
    val label: String,
    val value: String? = null,
    val toggle: Boolean? = null,
    val danger: Boolean = false,
    val slider: Pair<Int, Int>? = null,
    val chips: List<String>? = null,
    val chip: Int = -1,
    val gapBefore: Boolean = false,
    val adjust: ((Int) -> Unit)? = null,
    val run: () -> Unit = {},
)

/**
 * An entry of the top bar: a content app (switches the page) or a shortcut (runs on OK).
 * A [labeled] shortcut sits with the tabs on the left and always shows its name.
 */
data class NavItem(val label: String, val tile: HeroItem, val isTab: Boolean, val labeled: Boolean = false)

/** Our camera wall app (cams module); gets its own entry in the top bar when installed. */
const val PKG_CAMS = "com.arshe.cams"

val CONTENT_APPS = listOf(PKG_NETFLIX, PKG_YOUTUBE, PKG_MEGOGO, PKG_APPLETV)

val SEARCH_TILE = HeroItem(
    key = "special:search", app = "Поиск", brand = Brand(Color.White, "⌕", Color.Black),
    title = "Поиск", meta = "", desc = "", art = Arts.graphite, action = Action.OpenSearch,
)

/** On-screen keyboards, 6 columns; the last cells are controls. */
val KEYBOARDS = listOf(
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ".map { it.toString() },
    "abcdefghijklmnopqrstuvwxyz-'&:".map { it.toString() },
    "1234567890.,!?-:".map { it.toString() },
).map { it + listOf(KEY_SPACE, KEY_DEL, KEY_CLEAR, KEY_LANG, KEY_NUM) }
const val KEY_SPACE = "␣"
const val KEY_DEL = "⌫"
const val KEY_CLEAR = "✕"
const val KEY_LANG = "lang"
const val KEY_NUM = "num"
const val KB_COLS = 6

/** Pseudo-app for the aggregated "Главная" page. */
val HOME_TAB = HeroItem(
    key = "special:home", app = "Главная", brand = Brand(Color.White, "★", Color.Black),
    title = "Главная", meta = "", desc = "", art = Arts.graphite, action = Action.OpenApps,
)

/** Whole launcher state + D-pad handling. */
class Launcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: AppsRepository,
) {
    // ---- data ----
    var tiles by mutableStateOf(emptyList<HeroItem>())
    var continueItems by mutableStateOf(emptyList<HeroItem>())
    var recents by mutableStateOf(emptyList<Recent>())
    var sources by mutableStateOf(emptyList<Source>())

    /** Catalog rows per content app package. */
    var pages by mutableStateOf(mapOf<String, List<ContentRow>>())

    val nav: List<NavItem>
        get() {
            val by = tiles.associateBy { it.key }
            // Content apps follow the user's order from "Все приложения".
            val tabs = listOf(NavItem("Главная", HOME_TAB, isTab = true)) +
                contentTiles.map { NavItem(it.title, it, isTab = true) }
            val cams = listOfNotNull(by[PKG_CAMS]?.let { NavItem("Камеры", it, isTab = false, labeled = true) })
            val shortcuts = listOfNotNull(
                NavItem("Поиск", SEARCH_TILE, false),
                by[KEY_TV]?.let { NavItem("ТВ", it, false) },
                by[KEY_SOURCES]?.let { NavItem("Входы", it, false) },
                NavItem("Приложения", AppsRepository.allAppsTile, false),
                by[KEY_SETTINGS]?.let { NavItem("Настройки", it, false) },
            )
            return tabs + cams + shortcuts
        }

    val tabCount: Int get() = nav.count { it.isTab }
    val tab: HeroItem? get() = nav.filter { it.isTab }.getOrNull(tabIdx)?.tile

    private fun recentItem(r: Recent, owner: HeroItem): HeroItem {
        val left = if (r.duration > 0 && r.position > 0) ((r.duration - r.position) / 60_000).coerceAtLeast(1) else null
        val episode = r.title.takeIf { it != r.show }
        return HeroItem(
            key = "np:${r.pkg}:${r.show}",
            app = owner.app,
            brand = owner.brand,
            title = r.show,
            meta = listOfNotNull("Вы смотрели", episode ?: r.subtitle.ifBlank { null }, left?.let { "осталось $it мин" }).joinToString(" · "),
            desc = "",
            art = owner.art,
            poster = r.art,
            progress = if (r.duration > 0) (r.position.toFloat() / r.duration).coerceIn(0f, 1f) else null,
            primary = "Продолжить",
            action = r.link?.let { Action.Launch(Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(r.pkg)) } ?: owner.action,
        )
    }

    /** Recents are stored newest first; one card per show. */
    private fun recentItems(owner: HeroItem?): List<HeroItem> {
        val by = tiles.associateBy { it.key }
        return recents.filter { owner == null || it.pkg == owner.key }
            .mapNotNull { r -> by[r.pkg]?.let { recentItem(r, it) } }
            .distinctBy { it.key }
    }

    /** Installed content apps in the user's order. */
    private val contentTiles: List<HeroItem> get() = tiles.filter { it.key in CONTENT_APPS }

    /** "Главная": everything you were watching, then the head of every service (in the user's order). */
    private fun homeRows(): List<ContentRow> {
        val heads = contentTiles.mapNotNull { t ->
            val first = pages[t.key]?.firstOrNull() ?: return@mapNotNull null
            ContentRow("${t.title} · ${first.title.substringBefore(" на ")}", first.items.take(12), first.portrait, t.brand)
        }
        val mine = recentItems(null)
        return listOfNotNull(mine.takeIf { it.isNotEmpty() }?.let { ContentRow("Продолжить просмотр", it) }) + heads
    }

    /** Rows of the selected app: the user's own "continue" first, then the catalog. */
    val pageRows: List<ContentRow>
        get() {
            val t = tab ?: return emptyList()
            if (t === HOME_TAB) return homeRows()
            val mine = (recentItems(t) + continueItems.filter { it.app == t.app }).distinctBy { it.title }
            return listOfNotNull(mine.takeIf { it.isNotEmpty() }?.let { ContentRow("Продолжить просмотр", it) }) +
                pages[t.key].orEmpty()
        }

    /** What the billboard rotates through while the user is in the top bar. */
    private val heroPool: List<HeroItem>
        get() {
            val rows = pageRows
            if (tab === HOME_TAB) {
                // Two highlights from each source, landscape art only.
                return rows.flatMap { r -> r.items.filter { !r.portrait || it.key.startsWith("mg:") }.take(2) }.distinctBy { it.key }.take(10)
            }
            val catalog = rows.firstOrNull { it.title != "Продолжить просмотр" }?.items.orEmpty()
            return (rows.firstOrNull()?.items?.take(1).orEmpty() + catalog.take(6)).distinctBy { it.key }
        }

    /** False while another app is on screen: the UI is torn down so its images can be freed. */
    var visible by mutableStateOf(true)

    // ---- navigation ----
    var screen by mutableStateOf(Screen.Home)
    var zone by mutableStateOf(Zone.Nav)
    var railFocus by mutableIntStateOf(0)

    var navIdx by mutableIntStateOf(0)
    var tabIdx by mutableIntStateOf(0)
    var heroBtn by mutableIntStateOf(0)
    var heroSel by mutableIntStateOf(0)
    var rowFocus by mutableIntStateOf(0)
    val rowIdx = mutableStateMapOf<Int, Int>()

    var appsFocus by mutableIntStateOf(0)
    var moveMode by mutableStateOf(false)
    var appsOrder by mutableStateOf(emptyList<String>())
    private var snapshot: List<String>? = null
    private var holdJob: Job? = null

    var srcIdx by mutableIntStateOf(0)

    var sheetOpen by mutableStateOf(false)
    var sheetFocus by mutableIntStateOf(0)
    var sheetRows by mutableStateOf(emptyList<SheetRow>())
    var ambi by mutableStateOf<AmbiState?>(null)
    /** What we believe the TV currently has; diffed against [ambi] to send only what changed. */
    private var ambiSent: AmbiState? = null
    private var ambiJob: Job? = null

    fun refreshAmbi() {
        if (!JointSpace.configured || ambiJob?.isActive == true) return
        scope.launch { JointSpace.state()?.let { merge(it) } }
    }

    /** A powered-down TV reports no style; keep the user's last mode so "on" restores it. */
    private fun merge(read: AmbiState) {
        val mode = if (read.mode >= 0) read.mode else ambi?.mode?.takeIf { it >= 0 } ?: 0
        ambi = read.copy(mode = mode)
        ambiSent = ambi
    }

    /**
     * Optimistic change. Rapid presses are coalesced: only the latest state is sent 250 ms
     * after the last press, and the TV is re-read once things have been quiet for a while.
     */
    fun ambiSet(change: (AmbiState) -> AmbiState) {
        val cur = ambi ?: return
        ambi = change(cur)
        ambiJob?.cancel()
        ambiJob = scope.launch {
            delay(250)
            val want = ambi ?: return@launch
            val was = ambiSent ?: cur
            when {
                want.power != was.power && want.power -> JointSpace.setMode(want.mode.coerceAtLeast(0))
                want.power != was.power -> JointSpace.setPower(false)
                want.power && want.mode != was.mode -> JointSpace.setMode(want.mode.coerceAtLeast(0))
            }
            if (want.brightness != was.brightness) JointSpace.setBrightness(want.brightness)
            if (want.sleepMin != was.sleepMin) JointSpace.setSleep(want.sleepMin)
            ambiSent = want
            delay(1500)
            JointSpace.state()?.let { merge(it) }
        }
    }

    fun ambiCall(call: suspend () -> Unit) { scope.launch { call() } }

    // ---------------- search ----------------

    var query by mutableStateOf("")
    var results by mutableStateOf(emptyList<ContentRow>())
    var searching by mutableStateOf(false)
    var kb by mutableIntStateOf(0)
    var kbIdx by mutableIntStateOf(0)
    var sRow by mutableIntStateOf(0)
    val sIdx = mutableStateMapOf<Int, Int>()
    private var searchJob: Job? = null

    fun openSearch() {
        screen = Screen.Search
        zone = Zone.Hero
    }

    private fun press(key: String) {
        when (key) {
            KEY_SPACE -> if (query.isNotEmpty() && !query.endsWith(" ")) updateQuery("$query ")
            KEY_DEL -> updateQuery(query.dropLast(1))
            KEY_CLEAR -> updateQuery("")
            KEY_LANG -> { kb = if (kb == 0) 1 else 0; kbIdx = kbIdx.coerceAtMost(KEYBOARDS[kb].size - 1) }
            KEY_NUM -> { kb = if (kb == 2) 0 else 2; kbIdx = kbIdx.coerceAtMost(KEYBOARDS[kb].size - 1) }
            else -> updateQuery(query + key)
        }
    }

    /** Results follow the typing, 600 ms after the last key (YouTube search costs quota). */
    private fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        val text = q.trim()
        if (text.length < 2) { results = emptyList(); searching = false; return }
        searchJob = scope.launch {
            delay(600)
            searching = true
            val by = tiles.associateBy { it.key }
            val found = withContext(Dispatchers.IO) {
                listOfNotNull(
                    by[PKG_NETFLIX]?.let { t -> async { ContentRow("Netflix", JustWatchSource(context, PKG_NETFLIX, "nfx", "Netflix", t.brand, t.art).search(text), brand = t.brand) } },
                    by[PKG_MEGOGO]?.let { t -> async { ContentRow("MEGOGO", MegogoSource(context, t.brand, t.art).search(text), portrait = true, brand = t.brand) } },
                    by[PKG_APPLETV]?.let { t -> async { ContentRow("Apple TV+", JustWatchSource(context, PKG_APPLETV, "atp", "Apple TV+", t.brand, t.art).search(text), brand = t.brand) } },
                    by[PKG_YOUTUBE]?.let { t -> async { ContentRow("YouTube", YouTubeSource(context, t.brand).search(text), brand = t.brand) } },
                ).map { it.await() }.filter { it.items.isNotEmpty() }
            }
            results = found
            sRow = 0
            sIdx.clear()
            searching = false
        }
    }

    private fun searchKey(k: K) {
        val keys = KEYBOARDS[kb]
        when (zone) {
            Zone.Row -> {
                val row = results.getOrNull(sRow) ?: run { zone = Zone.Hero; return }
                val i = sIdx[sRow] ?: 0
                when (k) {
                    K.Left -> if (i > 0) sIdx[sRow] = i - 1 else zone = Zone.Hero
                    K.Right -> if (i < row.items.size - 1) sIdx[sRow] = i + 1
                    K.Up -> if (sRow > 0) sRow--
                    K.Down -> if (sRow < results.size - 1) sRow++
                    K.Ok -> run(row.items[i].action)
                    K.Back -> zone = Zone.Hero
                    else -> Unit
                }
            }
            else -> {
                val c = kbIdx % KB_COLS
                when (k) {
                    K.Left -> if (c > 0) kbIdx--
                    K.Right -> if (c < KB_COLS - 1 && kbIdx < keys.size - 1) kbIdx++ else if (results.isNotEmpty()) zone = Zone.Row
                    K.Up -> if (kbIdx >= KB_COLS) kbIdx -= KB_COLS
                    K.Down -> kbIdx = (kbIdx + KB_COLS).coerceAtMost(keys.size - 1)
                    K.Ok -> press(keys[kbIdx])
                    K.Back -> if (query.isNotEmpty()) updateQuery(query.dropLast(1)) else goHome()
                    else -> Unit
                }
            }
        }
    }

    /** Remote digit keys and the delete key type straight into the query. */
    private fun searchTyping(code: Int): Boolean {
        if (screen != Screen.Search || sheetOpen) return false
        when (code) {
            in AKey.KEYCODE_0..AKey.KEYCODE_9 -> updateQuery(query + (code - AKey.KEYCODE_0))
            AKey.KEYCODE_DEL -> updateQuery(query.dropLast(1))
            else -> return false
        }
        return true
    }

    var updateText by mutableStateOf("Проверить")

    fun checkUpdates(installNow: Boolean) {
        scope.launch {
            updateText = "Проверяю…"
            val ready = Updater.check(context)
            val st = Updater.status
            updateText = when (st.state) {
                Updater.State.UpToDate -> "Актуальная · v${com.arshe.tvos.BuildConfig.VERSION_CODE}"
                Updater.State.Ready -> "Есть v${st.version}"
                Updater.State.Failed -> "Не удалось проверить"
                else -> "Проверить"
            }
            if (ready && installNow) { updateText = "Устанавливаю v${st.version}…"; Updater.install(context) }
        }
    }

    /** In-launcher screensaver, for systems where apps can't start the system one. */
    var saverOn by mutableStateOf(false)
    var saverItem: HeroItem? = null
    private val saverDowns = mutableSetOf<Int>()

    fun startScreensaver() {
        // SystemUI's public "start dreaming now" entry point (gone on newer Android TV builds).
        val dream = Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
        if (context.packageManager.resolveActivity(dream, 0) != null) { launch(dream); return }
        saverOn = true
        if (Maintenance.due(context, 20)) scope.launch { Maintenance.run(context) }
    }

    /** Needs shell rights: goes through the TV's own adbd (asks once to allow the key). */
    fun reboot() {
        scope.launch { LocalAdb.shell(context, "svc power reboot") }
    }

    val heroItem: HeroItem?
        get() = when (zone) {
            Zone.Row -> pageRows.getOrNull(rowFocus)?.let { it.items.getOrNull(rowIdx[rowFocus] ?: 0) }
            else -> heroPool.let { p -> p.getOrNull(heroSel % p.size.coerceAtLeast(1)) }
        } ?: tab

    val orderedTiles: List<HeroItem>
        get() {
            val by = tiles.associateBy { it.key }
            return appsOrder.mapNotNull { by[it] }
        }

    fun reload(apps: List<HeroItem>, cont: List<HeroItem>, src: List<Source>) {
        tiles = apps
        continueItems = cont
        sources = src
        if (!moveMode) appsOrder = apps.map { it.key }
        tabIdx = tabIdx.coerceIn(0, (tabCount - 1).coerceAtLeast(0))
        navIdx = navIdx.coerceIn(0, (nav.size - 1).coerceAtLeast(0))
        srcIdx = srcIdx.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
    }

    /** Home button pressed while we are already the foreground launcher. */
    fun goHome() {
        sheetOpen = false
        moveMode = false
        screen = Screen.Home
        zone = Zone.Nav
        navIdx = tabIdx
        resetPage()
    }

    private fun resetPage() {
        rowFocus = 0
        rowIdx.clear()
        heroSel = 0
        heroBtn = 0
    }

    /** Last remote press; the carousel rests after a couple of idle minutes so the CPU can too. */
    private var lastInput = SystemClock.elapsedRealtime()
    val carouselActive: Boolean get() = SystemClock.elapsedRealtime() - lastInput < 120_000

    /** Auto-advance the billboard while the user sits in the top bar or on its buttons. */
    fun carouselTick() {
        if (screen != Screen.Home || zone == Zone.Row || sheetOpen || !carouselActive) return
        val n = heroPool.size
        if (n > 1) heroSel = (heroSel + 1) % n
    }

    fun heroButtons(item: HeroItem?): List<String> = when {
        item == null -> emptyList()
        item === tab -> listOf("Открыть ${item.title}")
        else -> listOf(item.primary, "Открыть ${item.app}")
    }

    /** The content app an item belongs to (labels differ: "Apple TV+" vs "Apple TV", "Megogo" vs "MEGOGO"). */
    fun ownerOf(item: HeroItem): HeroItem? =
        tiles.firstOrNull { it.key in CONTENT_APPS && it.title.lowercase().take(5) == item.app.lowercase().take(5) }

    // ---------------- keys ----------------

    fun onKey(e: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        val code = e.key.nativeKeyCode
        val down = e.type == KeyEventType.KeyDown
        val repeat = e.nativeKeyEvent.repeatCount > 0
        if (down && searchTyping(code)) return true
        val k = when (code) {
            AKey.KEYCODE_DPAD_LEFT -> K.Left
            AKey.KEYCODE_DPAD_RIGHT -> K.Right
            AKey.KEYCODE_DPAD_UP -> K.Up
            AKey.KEYCODE_DPAD_DOWN -> K.Down
            AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER, AKey.KEYCODE_BUTTON_A -> K.Ok
            AKey.KEYCODE_BACK, AKey.KEYCODE_ESCAPE -> K.Back
            AKey.KEYCODE_MENU, AKey.KEYCODE_SETTINGS -> K.Menu
            else -> return false
        }
        // OK on the apps grid needs press/release to tell a tap from a long-press.
        if (k == K.Ok && screen == Screen.Apps && !sheetOpen && zone == Zone.Row && !moveMode) {
            if (down && !repeat) {
                holdJob?.cancel()
                holdJob = scope.launch {
                    delay(550)
                    holdJob = null
                    snapshot = appsOrder
                    moveMode = true
                }
            } else if (!down && holdJob != null) {
                holdJob?.cancel(); holdJob = null
                orderedTiles.getOrNull(appsFocus)?.let { run(it.action) }
            }
            return true
        }
        if (saverOn) {
            // Any key wakes the in-app screensaver; OK also opens what it shows. Only keys
            // pressed while it is up count (the OK that started it must not close it).
            if (down) saverDowns += code
            else if (saverDowns.remove(code)) {
                saverOn = false
                saverDowns.clear()
                if (k == K.Ok) (saverItem?.action as? Action.Launch)?.let { run(it) }
            }
            return true
        }
        if (!down) return true
        if (repeat && (k == K.Ok || k == K.Back || k == K.Menu)) return true
        when {
            k == K.Menu -> toggleSheet()
            sheetOpen -> sheetKey(k)
            screen == Screen.Home -> homeKey(k)
            screen == Screen.Apps -> appsKey(k)
            screen == Screen.Sources -> sourcesKey(k)
            screen == Screen.Search -> searchKey(k)
        }
        return true
    }

    private enum class K { Left, Right, Up, Down, Ok, Back, Menu }

    private fun railKey(k: K) {
        when (k) {
            K.Up -> railFocus = (railFocus - 1).coerceAtLeast(0)
            K.Down -> railFocus = (railFocus + 1).coerceAtMost(RailItem.entries.size - 1)
            K.Right -> zone = Zone.Row
            K.Back -> goHome()
            K.Ok -> when (RailItem.entries[railFocus]) {
                RailItem.Home -> goHome()
                RailItem.Apps -> openApps()
                RailItem.Sources -> openSources()
                RailItem.Settings -> { zone = Zone.Row; toggleSheet() }
            }
            else -> Unit
        }
    }

    private fun openRail(active: RailItem) {
        zone = Zone.Rail
        railFocus = active.ordinal
    }

    private fun homeKey(k: K) {
        val rows = pageRows
        when (zone) {
            Zone.Nav -> {
                val items = nav
                when (k) {
                    K.Left -> if (navIdx > 0) selectNav(navIdx - 1)
                    K.Right -> if (navIdx < items.size - 1) selectNav(navIdx + 1)
                    K.Down -> { zone = Zone.Hero; heroBtn = 0 }
                    K.Ok -> items.getOrNull(navIdx)?.let { run(it.tile.action) }
                    else -> Unit
                }
            }
            Zone.Hero -> when (k) {
                K.Left -> if (heroBtn > 0) heroBtn--
                K.Right -> heroBtn = (heroBtn + 1).coerceAtMost(heroButtons(heroItem).size - 1)
                K.Up, K.Back -> zone = Zone.Nav
                K.Down -> if (rows.isNotEmpty()) { zone = Zone.Row; rowFocus = 0 }
                K.Ok -> heroItem?.let { item ->
                    when {
                        item === tab -> run(item.action)
                        heroBtn == 1 -> run(ownerOf(item)?.action ?: tab?.action ?: item.action)
                        else -> run(item.action)
                    }
                }
                else -> Unit
            }
            Zone.Row -> {
                val row = rows.getOrNull(rowFocus) ?: run { zone = Zone.Hero; return }
                val i = rowIdx[rowFocus] ?: 0
                when (k) {
                    K.Left -> if (i > 0) rowIdx[rowFocus] = i - 1
                    K.Right -> if (i < row.items.size - 1) rowIdx[rowFocus] = i + 1
                    K.Up -> if (rowFocus == 0) { zone = Zone.Hero; heroBtn = 0 } else rowFocus--
                    K.Down -> if (rowFocus < rows.size - 1) rowFocus++
                    K.Ok -> run(row.items[i].action)
                    K.Back -> { zone = Zone.Nav; navIdx = tabIdx; resetPage() }
                    else -> Unit
                }
            }
            Zone.Rail -> zone = Zone.Nav
        }
    }

    private fun selectNav(i: Int) {
        navIdx = i
        val item = nav.getOrNull(i) ?: return
        if (item.isTab) {
            val t = nav.filter { it.isTab }.indexOf(item)
            if (t != tabIdx) { tabIdx = t; resetPage() }
        }
    }

    /** Apps screen: Row = the list, Hero = the action buttons next to the preview. */
    var appsBtn by mutableIntStateOf(0)

    private fun appsKey(k: K) {
        if (zone == Zone.Rail) { railKey(k); return }
        val n = appsOrder.size
        if (zone == Zone.Hero) {
            val app = orderedTiles.getOrNull(appsFocus) ?: return
            when (k) {
                K.Left -> if (appsBtn > 0) appsBtn-- else zone = Zone.Row
                K.Right -> appsBtn = (appsBtn + 1).coerceAtMost(APP_ACTIONS.size - 1)
                K.Back -> zone = Zone.Row
                K.Ok -> when (appsBtn) {
                    0 -> run(app.action)
                    1 -> { zone = Zone.Row; snapshot = appsOrder; moveMode = true }
                    else -> if (app.key.contains('.')) launch(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.key}")),
                    )
                }
                else -> Unit
            }
            return
        }
        val t = when (k) {
            K.Left -> { if (!moveMode) openRail(RailItem.Apps); return }
            K.Right -> { if (!moveMode) { zone = Zone.Hero; appsBtn = 0 }; return }
            K.Up -> (appsFocus - 1).takeIf { it >= 0 }
            K.Down -> (appsFocus + 1).takeIf { it < n }
            K.Ok -> { if (moveMode) commitOrder(); return }
            K.Back -> {
                if (moveMode) { moveMode = false; snapshot?.let { appsOrder = it }; snapshot = null } else goHome()
                return
            }
            else -> null
        } ?: return
        if (moveMode) {
            val o = appsOrder.toMutableList()
            val tmp = o[appsFocus]; o[appsFocus] = o[t]; o[t] = tmp
            appsOrder = o
        }
        appsFocus = t
    }

    /** Save a new app order; the top bar keeps the same tab selected even if it moved. */
    private fun commitOrder() {
        val selected = tab?.key
        moveMode = false
        snapshot = null
        repo.saveOrder(appsOrder)
        tiles = orderedTiles
        tabIdx = nav.filter { it.isTab }.indexOfFirst { it.tile.key == selected }.coerceAtLeast(0)
        navIdx = tabIdx
    }

    /** Preview art for an app: what you watched there, else the head of its catalog. */
    fun previewOf(app: HeroItem): HeroItem? =
        recentItems(app).firstOrNull { it.poster != null } ?: pages[app.key]?.firstOrNull()?.items?.firstOrNull()

    private fun sourcesKey(k: K) {
        val n = sources.size
        when (zone) {
            Zone.Rail -> railKey(k)
            Zone.Hero -> when (k) {
                K.Left -> openRail(RailItem.Sources)
                K.Down -> zone = Zone.Row
                K.Ok -> sources.getOrNull(srcIdx)?.let { run(it.action) }
                K.Back -> goHome()
                else -> Unit
            }
            else -> when (k) {
                K.Left -> if (srcIdx == 0) openRail(RailItem.Sources) else srcIdx--
                K.Right -> srcIdx = (srcIdx + 1).coerceAtMost(n - 1)
                K.Up -> zone = Zone.Hero
                K.Ok -> sources.getOrNull(srcIdx)?.let { run(it.action) }
                K.Back -> goHome()
                else -> Unit
            }
        }
    }

    private fun sheetKey(k: K) {
        when (k) {
            K.Up -> sheetFocus = (sheetFocus - 1).coerceAtLeast(0)
            K.Down -> sheetFocus = (sheetFocus + 1).coerceAtMost(sheetRows.size - 1)
            K.Left -> sheetRows.getOrNull(sheetFocus)?.adjust?.invoke(-1)
            K.Right -> sheetRows.getOrNull(sheetFocus)?.adjust?.invoke(1)
            K.Ok -> sheetRows.getOrNull(sheetFocus)?.run?.invoke()
            K.Back -> sheetOpen = false
            else -> Unit
        }
    }

    private fun toggleSheet() {
        sheetOpen = !sheetOpen
        if (sheetOpen) { sheetFocus = 0; refreshAmbi() }
    }

    fun openApps() {
        screen = Screen.Apps
        zone = Zone.Row
        appsFocus = 0
        appsBtn = 0
        moveMode = false
    }

    fun openSources() {
        screen = Screen.Sources
        zone = Zone.Row
        srcIdx = 0
    }

    fun run(action: Action) {
        when (action) {
            is Action.Launch -> launch(action.intent)
            Action.OpenApps -> openApps()
            Action.OpenSources -> openSources()
            Action.OpenSettings -> toggleSheet()
            Action.OpenSearch -> openSearch()
        }
    }

    fun launch(intent: Intent) {
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("TVOS", "cannot launch $intent", e)
        } catch (e: SecurityException) {
            Log.w("TVOS", "not allowed to launch $intent", e)
        }
    }

    companion object {
        val APP_ACTIONS = listOf("Открыть", "Переставить", "О приложении")
    }
}
