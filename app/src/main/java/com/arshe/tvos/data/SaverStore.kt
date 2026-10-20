package com.arshe.tvos.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.arshe.tvos.ui.Arts

/** Backdrops for the screensaver: landscape catalog art plus what the user watched recently. */
object SaverStore {
    @Volatile var items: List<HeroItem> = emptyList()
        private set

    /** Called by the launcher whenever catalog pages or recents change. */
    fun update(pages: Map<String, List<ContentRow>>, recents: List<HeroItem>) {
        items = pick(pages.values.flatten().flatMap { it.items } + recents)
    }

    /** Cold start (the dream can run without the launcher): rebuild from the disk caches. Blocking. */
    fun load(context: Context): List<HeroItem> {
        val nf = JustWatchSource(context, PKG_NETFLIX, "nfx", "Netflix", Brand(Color(0xFFE50914), "N"), Arts.red).rows()
        val atv = JustWatchSource(context, PKG_APPLETV, "atp", "Apple TV+", Brand(Color(0xFFE9EDF2), "A", Color.Black), Arts.silver).rows()
        return pick((nf + atv).flatMap { it.items }).also { items = it }
    }

    // Only true landscape artwork looks good full-screen.
    private fun pick(all: List<HeroItem>) = all
        .filter { it.poster != null && (it.poster.contains("/backdrop/") || it.poster.startsWith("file://") || it.key.startsWith("yt:")) }
        .distinctBy { it.title }
        .shuffled()
        .take(40)
}
