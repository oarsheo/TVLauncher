package com.arshe.tvos.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import com.arshe.tvos.ui.Arts

class SourcesRepository(private val context: Context) {

    fun load(): List<Source> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val inputs = tim.tvInputList
        // CEC devices show up as child inputs of the physical HDMI port; use their names.
        val children = inputs.filter { it.parentId != null }.groupBy { it.parentId }
        val hdmi = inputs
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.id }
            .mapIndexed { i, info ->
                val port = "HDMI ${i + 1}"
                val child = children[info.id]?.firstOrNull()
                val custom = (child ?: info).loadCustomLabel(context)?.toString()
                val label = custom ?: child?.loadLabel(context)?.toString()
                val state = tim.getInputState((child ?: info).id)
                val live = state == TvInputManager.INPUT_STATE_CONNECTED
                val name = label?.takeIf { it.isNotBlank() && !it.equals(port, ignoreCase = true) } ?: port
                Source(
                    id = info.id,
                    port = port,
                    name = name,
                    status = when (state) {
                        TvInputManager.INPUT_STATE_CONNECTED -> "Активен"
                        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Готов"
                        else -> "Нет устройства"
                    },
                    live = live,
                    desc = if (name != port) "HDMI-CEC · управление пультом телевизора"
                    else "Подключите устройство — его имя подтянется по HDMI-CEC",
                    art = if (live || name != port) Arts.hdmiActive else Arts.hdmiIdle,
                    action = Action.Launch(
                        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id)),
                    ),
                )
            }
        val tuner = context.packageManager.getLeanbackLaunchIntentForPackage("org.droidtv.playtv")?.let {
            Source("tuner", "ТЮНЕР", "Эфир", "ТВ", true, "Эфирные, кабельные и спутниковые каналы", Arts.tuner, Action.Launch(it))
        }
        return hdmi + listOfNotNull(tuner)
    }
}
