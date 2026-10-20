package com.arshe.tvos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import com.arshe.tvos.data.AmbiState
import com.arshe.tvos.data.AppsRepository
import com.arshe.tvos.data.JointSpace
import com.arshe.tvos.data.LocalAdb
import com.arshe.tvos.data.HeroItem
import com.arshe.tvos.data.ContentRow
import com.arshe.tvos.data.Detail
import com.arshe.tvos.data.DetailsRepository
import com.arshe.tvos.data.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Home, Apps, Sources, Detail }

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

/** An entry of the top bar: a content app (switches the page) or a shortcut (runs on OK). */
data class NavItem(val label: String, val tile: HeroItem, val isTab: Boolean)

val CONTENT_APPS = listOf(PKG_NETFLIX, PKG_YOUTUBE, PKG_MEGOGO, PKG_APPLETV)

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
            val tabs = CONTENT_APPS.mapNotNull { by[it] }.map { NavItem(it.title, it, isTab = true) }
            val shortcuts = listOfNotNull(
                by[KEY_TV]?.let { NavItem("ТВ", it, false) },
                by[KEY_SOURCES]?.let { NavItem("Входы", it, false) },
                NavItem("Приложения", AppsRepository.allAppsTile, false),
                by[KEY_SETTINGS]?.let { NavItem("Настройки", it, false) },
            )
            return tabs + shortcuts
        }

    val tabCount: Int get() = nav.count { it.isTab }
    val tab: HeroItem? get() = nav.filter { it.isTab }.getOrNull(tabIdx)?.tile

    private fun recentItems(owner: HeroItem): List<HeroItem> = recents.filter { it.pkg == owner.key }.distinctBy { it.show }.map { r ->
        val left = if (r.duration > 0 && r.position > 0) ((r.duration - r.position) / 60_000).coerceAtLeast(1) else null
        val episode = r.title.takeIf { it != r.show }
        HeroItem(
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

    /** Rows of the selected app: the user's own "continue" first, then the catalog. */
    val pageRows: List<ContentRow>
        get() {
            val t = tab ?: return emptyList()
            val mine = (recentItems(t) + continueItems.filter { it.app == t.app }).distinctBy { it.title }
            return listOfNotNull(mine.takeIf { it.isNotEmpty() }?.let { ContentRow("Продолжить просмотр", it) }) +
                pages[t.key].orEmpty()
        }

    /** What the billboard rotates through while the user is in the top bar. */
    private val heroPool: List<HeroItem>
        get() {
            val rows = pageRows
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

    fun startScreensaver() {
        // SystemUI's public "start dreaming now" entry point.
        launch(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
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

    /** Auto-advance the billboard while the user sits in the top bar or on its buttons. */
    fun carouselTick() {
        if (screen != Screen.Home || zone == Zone.Row || sheetOpen) return
        val n = heroPool.size
        if (n > 1) heroSel = (heroSel + 1) % n
    }

    fun heroButtons(item: HeroItem?): List<String> = when {
        item == null -> emptyList()
        item === tab -> listOf("Открыть ${item.title}")
        hasDetails(item) -> listOf(item.primary, "Подробнее")
        else -> listOf(item.primary, "Открыть ${item.app}")
    }

    // ---------------- details ----------------

    private val detailsRepo = DetailsRepository(context)
    var detailItem by mutableStateOf<HeroItem?>(null)
    var detail by mutableStateOf<Detail?>(null)
    var detailLoading by mutableStateOf(false)
    var episodes by mutableStateOf(emptyList<Episode>())
    /** 0 = buttons, 1 = season chips, 2 = episodes / similar row. */
    var dZone by mutableIntStateOf(0)
    var dBtn by mutableIntStateOf(0)
    var dSeason by mutableIntStateOf(0)
    var dIdx by mutableIntStateOf(0)
    private var detailFrom = Screen.Home
    private var seasonJob: Job? = null

    /** The content app an item belongs to (labels differ: "Apple TV+" vs "Apple TV", "Megogo" vs "MEGOGO"). */
    fun ownerOf(item: HeroItem): HeroItem? =
        tiles.firstOrNull { it.key in CONTENT_APPS && it.title.lowercase().take(5) == item.app.lowercase().take(5) }

    private fun hasDetails(item: HeroItem): Boolean = item.ref != null ||
        (item.key.startsWith("np:") && ownerOf(item)?.key in setOf(PKG_NETFLIX, PKG_MEGOGO, PKG_APPLETV))

    val detailRow: List<Any>
        get() = if (detail?.seasons?.isNotEmpty() == true) episodes else detail?.similar.orEmpty()

    fun openDetail(item: HeroItem) {
        if (screen != Screen.Detail) detailFrom = screen
        detailItem = item
        detail = null
        episodes = emptyList()
        dZone = 0; dBtn = 0; dSeason = 0; dIdx = 0
        screen = Screen.Detail
        val owner = ownerOf(item) ?: return
        detailLoading = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { detailsRepo.load(item, owner.key, owner.brand, owner.art) }
            if (detailItem !== item) return@launch
            detail = d
            episodes = d?.firstEpisodes.orEmpty()
            detailLoading = false
        }
    }

    private fun closeDetail() {
        screen = detailFrom
        detailItem = null
    }

    private fun selectSeason(i: Int) {
        val d = detail ?: return
        val season = d.seasons.getOrNull(i) ?: return
        dSeason = i
        dIdx = 0
        val owner = detailItem?.let { ownerOf(it) } ?: return
        seasonJob?.cancel()
        seasonJob = scope.launch {
            episodes = emptyList()
            episodes = withContext(Dispatchers.IO) { detailsRepo.episodes(season, owner.key) }
        }
    }

    private fun detailKey(k: K) {
        val item = detailItem ?: return
        val d = detail
        val zones = listOfNotNull(0, 1.takeIf { (d?.seasons?.size ?: 0) > 1 }, 2.takeIf { detailRow.isNotEmpty() })
        fun move(dir: Int) { zones.getOrNull(zones.indexOf(dZone) + dir)?.let { dZone = it } }
        when (dZone) {
            0 -> when (k) {
                K.Left -> if (dBtn > 0) dBtn--
                K.Right -> if (dBtn < 1) dBtn++
                K.Down -> move(1)
                K.Ok -> run(if (dBtn == 0) item.action else ownerOf(item)?.action ?: item.action)
                K.Back -> closeDetail()
                else -> Unit
            }
            1 -> when (k) {
                K.Left -> if (dSeason > 0) selectSeason(dSeason - 1)
                K.Right -> if (dSeason < (d?.seasons?.size ?: 0) - 1) selectSeason(dSeason + 1)
                K.Up -> move(-1)
                K.Down -> move(1)
                K.Back -> closeDetail()
                else -> Unit
            }
            else -> {
                val row = detailRow
                when (k) {
                    K.Left -> if (dIdx > 0) dIdx--
                    K.Right -> if (dIdx < row.size - 1) dIdx++
                    K.Up -> move(-1)
                    K.Back -> closeDetail()
                    K.Ok -> when (val x = row.getOrNull(dIdx)) {
                        is Episode -> run(x.action ?: item.action)
                        is HeroItem -> openDetail(x)
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---------------- keys ----------------

    fun onKey(e: KeyEvent): Boolean {
        val code = e.key.nativeKeyCode
        val down = e.type == KeyEventType.KeyDown
        val repeat = e.nativeKeyEvent.repeatCount > 0
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
        if (k == K.Ok && screen == Screen.Apps && !sheetOpen && zone != Zone.Rail && !moveMode) {
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
        if (!down) return true
        if (repeat && (k == K.Ok || k == K.Back || k == K.Menu)) return true
        when {
            k == K.Menu -> toggleSheet()
            sheetOpen -> sheetKey(k)
            screen == Screen.Home -> homeKey(k)
            screen == Screen.Apps -> appsKey(k)
            screen == Screen.Sources -> sourcesKey(k)
            screen == Screen.Detail -> detailKey(k)
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
                        heroBtn == 1 && hasDetails(item) -> openDetail(item)
                        heroBtn == 1 -> run(tab?.action ?: item.action)
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
                    K.Ok -> row.items[i].let { if (it.ref != null) openDetail(it) else run(it.action) }
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

    private fun appsKey(k: K) {
        if (zone == Zone.Rail) { railKey(k); return }
        val n = appsOrder.size
        val c = appsFocus % APPS_COLS
        fun target(dx: Int, dy: Int): Int? {
            if (dx != 0) { if (c + dx < 0 || c + dx >= APPS_COLS) return null; return (appsFocus + dx).takeIf { it in 0 until n } }
            return (appsFocus + dy * APPS_COLS).takeIf { it in 0 until n }
        }
        val t = when (k) {
            K.Left -> if (c == 0 && !moveMode) { openRail(RailItem.Apps); return } else target(-1, 0)
            K.Right -> target(1, 0)
            K.Up -> target(0, -1)
            K.Down -> target(0, 1) ?: if (appsFocus / APPS_COLS < (n - 1) / APPS_COLS) n - 1 else null
            K.Ok -> { if (moveMode) { moveMode = false; snapshot = null; repo.saveOrder(appsOrder); tiles = orderedTiles }; return }
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
        const val APPS_COLS = 5
    }
}
