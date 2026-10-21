package com.arshe.cams

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** What the user picks on the settings screen; seeded once from nvr.properties, then kept on the box. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var voice by mutableStateOf(sp.getString("voice", DEFAULT_VOICE)!!)
    var nightFrom by mutableIntStateOf(sp.getInt("nightFrom", 23))
    var nightTo by mutableIntStateOf(sp.getInt("nightTo", 7))
    var dimAtNight by mutableStateOf(sp.getBoolean("dimAtNight", true))
    var nightGuard by mutableStateOf(sp.getBoolean("nightGuard", false))
    var autoOpen by mutableStateOf(sp.getBoolean("autoOpen", true))
    val alarmCams = mutableStateListOf<String>().apply { addAll(sp.getStringSet("alarmCams", emptySet())!!) }

    fun seed(nvr: Nvr) {
        if (sp.getBoolean("seeded", false)) return
        nvr.voice?.let { voice = it }
        nightFrom = nvr.nightFrom
        nightTo = nvr.nightTo
        alarmCams.clear()
        alarmCams.addAll(nvr.alarms.keys)
        sp.edit().putBoolean("seeded", true).apply()
        save()
    }

    fun save() {
        sp.edit()
            .putString("voice", voice)
            .putInt("nightFrom", nightFrom)
            .putInt("nightTo", nightTo)
            .putBoolean("dimAtNight", dimAtNight)
            .putBoolean("nightGuard", nightGuard)
            .putBoolean("autoOpen", autoOpen)
            .putStringSet("alarmCams", alarmCams.toSet())
            .apply()
    }

    fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (nightFrom > nightTo) h >= nightFrom || h < nightTo else h in nightFrom until nightTo
    }

    /** Person alarms: the chosen cameras always, every camera at night in guard mode. */
    fun alarmOn(cam: Camera) = cam.name in alarmCams || (nightGuard && isNight())

    companion object {
        const val DEFAULT_VOICE = "ru-ru-x-ruc-local"
    }
}

/** Settings sheet driven by the remote: ↑↓ rows, ←→ change, OK toggle / listen, Back closes. */
class SettingsScreen(private val prefs: Prefs, private val cams: () -> List<Camera>, private val voices: () -> List<String>) {
    var open by mutableStateOf(false)
    var sel by mutableIntStateOf(0)

    sealed class Item {
        data object Voice : Item()
        data object From : Item()
        data object To : Item()
        data object Dim : Item()
        data object Guard : Item()
        data object AutoOpen : Item()
        data class Cam(val name: String) : Item()
        data object Test : Item()
    }

    val items: List<Item> get() = listOf(Item.Voice, Item.From, Item.To, Item.Dim, Item.Guard, Item.AutoOpen) +
        cams().map { Item.Cam(it.name) } + Item.Test

    /** Returns true when the key was handled; [listen] plays the voice, [test] fires a sample alarm. */
    fun key(e: KeyEvent, listen: (String) -> Unit, test: () -> Unit): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val list = items
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (!down) { open = false; prefs.save() } }
            KeyEvent.KEYCODE_DPAD_UP -> if (down) sel = (sel - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) sel = (sel + 1).coerceAtMost(list.size - 1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                when (val it = list[sel]) {
                    Item.Voice -> voices().takeIf { v -> v.isNotEmpty() }?.let { v ->
                        prefs.voice = v[(v.indexOf(prefs.voice).coerceAtLeast(0) + d + v.size) % v.size]
                        listen(prefs.voice)
                    }
                    Item.From -> prefs.nightFrom = (prefs.nightFrom + d + 24) % 24
                    Item.To -> prefs.nightTo = (prefs.nightTo + d + 24) % 24
                    else -> toggle(it, listen, test)
                }
                prefs.save()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (down && e.repeatCount == 0) { toggle(list[sel], listen, test); prefs.save() }
            else -> return false
        }
        return true
    }

    private fun toggle(item: Item, listen: (String) -> Unit, test: () -> Unit) {
        when (item) {
            Item.Voice -> listen(prefs.voice)
            Item.Dim -> prefs.dimAtNight = !prefs.dimAtNight
            Item.Guard -> prefs.nightGuard = !prefs.nightGuard
            Item.AutoOpen -> prefs.autoOpen = !prefs.autoOpen
            is Item.Cam -> if (!prefs.alarmCams.remove(item.name)) prefs.alarmCams.add(item.name)
            Item.Test -> { open = false; test() }
            else -> {}
        }
    }

    fun voiceLabel(): String {
        val v = voices()
        val i = v.indexOf(prefs.voice)
        return if (i >= 0) "Голос ${i + 1}" else "—"
    }
}

private const val ROW = 64
private const val GAP = 6
private const val TOP = 150
private const val VISIBLE = 11

@Composable
fun SettingsSheet(s: SettingsScreen, prefs: Prefs) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(.45f)))
    Box(Modifier.offset(1100.dp, 0.dp).width(820.dp).fillMaxHeight().background(Color(0xF2121418))) {
        BasicText("Настройки", Modifier.offset(56.dp, 56.dp), style = text(52, 800))
        val items = s.items
        val first = (s.sel - (VISIBLE - 3)).coerceIn(0, (items.size - VISIBLE + 1).coerceAtLeast(0))
        Box(Modifier.offset(40.dp, TOP.dp).width(740.dp).height(((ROW + GAP) * VISIBLE).dp).clipToBounds()) {
            // Unbounded: the list is taller than its window and slides inside it.
            Column(
                Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(0.dp, (-(first * (ROW + GAP))).dp),
                verticalArrangement = Arrangement.spacedBy(GAP.dp),
            ) {
                items.forEachIndexed { i, item ->
                    val (label, value) = when (item) {
                        SettingsScreen.Item.Voice -> "Голос объявлений" to "‹  ${s.voiceLabel()}  ›"
                        SettingsScreen.Item.From -> "Ночь с" to "‹  %02d:00  ›".format(prefs.nightFrom)
                        SettingsScreen.Item.To -> "Ночь до" to "‹  %02d:00  ›".format(prefs.nightTo)
                        SettingsScreen.Item.Dim -> "Затемнять экран ночью" to onOff(prefs.dimAtNight)
                        SettingsScreen.Item.Guard -> "Ночная охрана: все камеры" to onOff(prefs.nightGuard)
                        SettingsScreen.Item.AutoOpen -> "Открывать камеру при тревоге" to onOff(prefs.autoOpen)
                        is SettingsScreen.Item.Cam -> item.name to onOff(item.name in prefs.alarmCams)
                        SettingsScreen.Item.Test -> "Проверить тревогу" to "OK"
                    }
                    if (item is SettingsScreen.Item.Cam && items.getOrNull(i - 1) !is SettingsScreen.Item.Cam) {
                        BasicText("ЧЕЛОВЕК НА КАМЕРАХ", Modifier.padding(start = 16.dp, top = 10.dp), style = text(20, 800, Color.White.copy(.4f)))
                    }
                    SettingRow(label, value, on = i == s.sel)
                }
            }
        }
        BasicText(
            "↑ ↓ выбор  ·  ← → изменить  ·  OK — вкл/выкл, прослушать  ·  Назад — закрыть",
            Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 40.dp),
            style = text(20, 600, Color.White.copy(.4f)),
        )
    }
}

private fun onOff(v: Boolean) = if (v) "Вкл" else "Выкл"

@Composable
private fun SettingRow(label: String, value: String, on: Boolean) {
    val ink = if (on) Color.Black else Color.White
    Row(
        Modifier.fillMaxWidth().height(ROW.dp)
            .background(if (on) Color.White else Color.White.copy(.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = text(28, 700, ink))
        val accent = when (value) { "Вкл" -> if (on) Color(0xFF1E8E3E) else Green; "Выкл" -> ink.copy(.45f); else -> ink.copy(.8f) }
        BasicText(value, style = text(26, 800, accent))
    }
}
