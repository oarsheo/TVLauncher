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
import com.arshe.tvos.data.AppsRepository
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
import com.arshe.tvos.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Home, Apps, Sources }

/** Nav = top app bar, Hero = buttons under the billboard, Row = content rows / apps grid / inputs, Rail = left menu. */
enum class Zone { Rail, Nav, Hero, Row }

data class SheetRow(val label: String, val value: String? = null, val toggle: Boolean? = null, val danger: Boolean = false, val run: () -> Unit)

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

    private fun recentItems(owner: HeroItem): List<HeroItem> = recents.filter { it.pkg == owner.key }.map { r ->
        val left = if (r.duration > 0 && r.position > 0) ((r.duration - r.position) / 60_000).coerceAtLeast(1) else null
        HeroItem(
            key = "np:${r.pkg}:${r.title}",
            app = owner.app,
            brand = owner.brand,
            title = r.title,
            meta = listOfNotNull("Вы смотрели", r.subtitle.ifBlank { null }, left?.let { "осталось $it мин" }).joinToString(" · "),
            desc = "",
            art = owner.art,
            poster = r.art,
            progress = if (r.duration > 0) (r.position.toFloat() / r.duration).coerceIn(0f, 1f) else null,
            primary = "Продолжить",
            action = owner.action,
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
        else -> listOf(item.primary, "Открыть ${item.app}")
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
                K.Ok -> heroItem?.let { item -> run(if (heroBtn == 1 || item === tab) tab?.action ?: item.action else item.action) }
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
            K.Ok -> sheetRows.getOrNull(sheetFocus)?.run?.invoke()
            K.Back -> sheetOpen = false
            else -> Unit
        }
    }

    private fun toggleSheet() {
        sheetOpen = !sheetOpen
        if (sheetOpen) sheetFocus = 0
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
