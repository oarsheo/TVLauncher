package com.arshe.tvos.ui

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.arshe.tvos.data.AMBI_MODES
import com.arshe.tvos.data.AppsRepository
import com.arshe.tvos.data.JointSpace
import com.arshe.tvos.data.SLEEP_STEPS
import com.arshe.tvos.data.rememberClock
import com.arshe.tvos.data.rememberNetStatus

private const val PKG_NORD = "com.nordvpn.android"

@Composable
fun LauncherRoot(l: Launcher) {
    val net by rememberNetStatus()
    val time = rememberClock()
    val focus = remember { FocusRequester() }

    // Read here (not inside SideEffect) so the rows rebuild whenever the TV state changes.
    val a = l.ambi
    SideEffect {
        l.sheetRows = buildList {
            if (a != null) {
                // Actions read the live state at press time, never the snapshot the row was built from.
                add(SheetRow("Ambilight", toggle = a.power, run = {
                    val on = !(l.ambi?.power ?: return@SheetRow)
                    l.ambiUpdate({ it.copy(power = on) }) { JointSpace.setPower(on) }
                }))
                fun mode(d: Int) {
                    val cur = l.ambi ?: return
                    val m = Math.floorMod(cur.mode + d, AMBI_MODES.size)
                    l.ambiUpdate({ it.copy(mode = m, power = true) }) { JointSpace.setMode(m) }
                }
                add(SheetRow("Режим", value = AMBI_MODES[a.mode].first, adjust = ::mode, run = { mode(1) }))
                add(SheetRow("Яркость", slider = a.brightness to 10, adjust = { d ->
                    val v = ((l.ambi ?: return@SheetRow).brightness + d).coerceIn(0, 10)
                    l.ambiUpdate({ it.copy(brightness = v) }) { JointSpace.setBrightness(v) }
                }))
                val step = SLEEP_STEPS.indexOfFirst { it >= a.sleepMin }.coerceAtLeast(0)
                add(SheetRow("Таймер сна", chips = listOf("Выкл", "30", "60", "90", "120"), chip = step, gapBefore = true, adjust = { d ->
                    val curStep = SLEEP_STEPS.indexOfFirst { it >= (l.ambi ?: return@SheetRow).sleepMin }.coerceAtLeast(0)
                    val v = SLEEP_STEPS[(curStep + d).coerceIn(0, SLEEP_STEPS.size - 1)]
                    l.ambiUpdate({ it.copy(sleepMin = v) }) { JointSpace.setSleep(v) }
                }))
            }
            add(SheetRow("Все настройки", "Philips", gapBefore = a != null) { l.sheetOpen = false; l.launch(AppsRepository.settingsIntent()) })
            add(SheetRow("Сеть и интернет", if (net.online) "Подключено" else "Нет сети") { l.sheetOpen = false; l.launch(AppsRepository.networkIntent()) })
            l.tiles.firstOrNull { it.key == PKG_NORD }?.let { nord ->
                add(SheetRow(if (net.vpn) "NordVPN · подключён" else "NordVPN · выключен", toggle = net.vpn) {
                    l.sheetOpen = false; l.run(nord.action)
                })
            }
            if (JointSpace.configured) add(SheetRow("Выключить ТВ", danger = true, gapBefore = true) {
                l.sheetOpen = false
                l.ambiUpdate({ it }) { JointSpace.standby() }
            })
        }
    }

    DesignCanvas {
        Box(
            Modifier.fillMaxSize()
                .focusRequester(focus)
                .onPreviewKeyEvent { l.onKey(it) }
                .focusable(),
        ) {
            Crossfade(l.screen, animationSpec = tween(250), label = "screen") { s ->
                when (s) {
                    Screen.Home -> HomeScreen(l, time, net.vpn)
                    Screen.Apps -> AppsScreen(l)
                    Screen.Sources -> SourcesScreen(l)
                }
            }
            if (l.screen != Screen.Home) Rail(
                open = l.zone == Zone.Rail && !l.sheetOpen,
                focus = l.railFocus,
                active = when (l.screen) {
                    Screen.Home -> RailItem.Home
                    Screen.Apps -> RailItem.Apps
                    Screen.Sources -> RailItem.Sources
                },
            )
            if (l.screen != Screen.Home) StatusBar(time, net.vpn, net.online, Modifier.align(Alignment.TopEnd))
            ControlSheet(l, time)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

fun Intent.isHomeIntent() = action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME)
