package com.arshe.tvos.data

import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.arshe.tvos.ui.Art

/** How an app is marked in the UI: its real launcher icon when we have it, else a lettered tile. */
data class Brand(val color: Color, val letter: String, val ink: Color = Color.White, val icon: ImageBitmap? = null)

sealed interface Action {
    data class Launch(val intent: Intent) : Action
    data object OpenApps : Action
    data object OpenSources : Action
    data object OpenSettings : Action
    data object OpenSearch : Action
}

/** Anything that can sit in the hero: a continue-watching program or an app tile. */
data class HeroItem(
    val key: String,
    val app: String,
    val brand: Brand,
    val title: String,
    val meta: String,
    val desc: String,
    val art: Art,
    /** Landscape image (hero, wide cards). */
    val poster: String? = null,
    /** Portrait image for poster rows; falls back to [poster]. */
    val cover: String? = null,
    /** Vertical crop bias for the hero (-1 top … 1 bottom); portrait-only artwork looks best near the top. */
    val heroBias: Float = 0f,
    val progress: Float? = null,
    val live: Boolean = false,
    val primary: String = "Открыть",
    val action: Action,
)

data class HomeRow(val title: String, val items: List<HeroItem>, val wide: Boolean)

data class Source(
    val id: String,
    val port: String,
    val name: String,
    val status: String,
    val live: Boolean,
    val desc: String,
    val art: Art,
    val action: Action,
)
