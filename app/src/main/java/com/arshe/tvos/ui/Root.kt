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
import com.arshe.tvos.data.AppsRepository
import com.arshe.tvos.data.rememberClock
import com.arshe.tvos.data.rememberNetStatus

private const val PKG_NORD = "com.nordvpn.android"

@Composable
fun LauncherRoot(l: Launcher) {
    val net by rememberNetStatus()
    val time = rememberClock()
    val focus = remember { FocusRequester() }

    SideEffect {
        l.sheetRows = buildList {
            add(SheetRow("Все настройки", "Philips") { l.sheetOpen = false; l.launch(AppsRepository.settingsIntent()) })
            add(SheetRow("Сеть и интернет", if (net.online) "Подключено" else "Нет сети") { l.sheetOpen = false; l.launch(AppsRepository.networkIntent()) })
            l.tiles.firstOrNull { it.key == PKG_NORD }?.let { nord ->
                add(SheetRow(if (net.vpn) "NordVPN · подключён" else "NordVPN · выключен", toggle = net.vpn) {
                    l.sheetOpen = false; l.run(nord.action)
                })
            }
            add(SheetRow("Источники", "HDMI и тюнер") { l.sheetOpen = false; l.openSources() })
            add(SheetRow("Все приложения", "${l.tiles.size}") { l.sheetOpen = false; l.openApps() })
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
