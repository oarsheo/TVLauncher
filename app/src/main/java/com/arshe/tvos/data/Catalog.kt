package com.arshe.tvos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.util.Log
import com.arshe.tvos.ui.Art
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One horizontal row on an app's page. */
data class ContentRow(val title: String, val items: List<HeroItem>, val portrait: Boolean = false)

const val PKG_NETFLIX = "com.netflix.ninja"
const val PKG_MEGOGO = "com.megogo.application"
const val PKG_APPLETV = "com.apple.atve.androidtv.appletv"

private const val TAG = "TVOS"
private const val TTL_MS = 3 * 60 * 60 * 1000L
private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36"

/** Disk-cached HTTP text fetch: fresh cache wins, network next, stale cache as a last resort. */
internal fun cachedFetch(context: Context, name: String, fetch: () -> String?): String? {
    val f = File(context.cacheDir, "catalog_$name")
    if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS) return f.readText()
    val body = runCatching(fetch).onFailure { Log.w(TAG, "fetch $name", it) }.getOrNull()
    if (body != null) { f.writeText(body); return body }
    return f.takeIf { it.exists() }?.readText()
}

internal fun http(url: String, post: String? = null): String? {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 7000
    c.readTimeout = 10000
    c.setRequestProperty("User-Agent", UA)
    if (post != null) {
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(post.toByteArray()) }
    }
    return if (c.responseCode == 200) c.inputStream.bufferedReader().readText()
    else { Log.w(TAG, "http ${c.responseCode} $url"); null }
}

// ---------------------------------------------------------------- JustWatch (Netflix, Apple TV+)

private val GENRES = mapOf(
    "act" to "Боевик", "ani" to "Анимация", "cmy" to "Комедия", "crm" to "Криминал", "doc" to "Документальный",
    "drm" to "Драма", "fml" to "Семейный", "fnt" to "Фэнтези", "hst" to "История", "hrr" to "Ужасы",
    "msc" to "Музыка", "trl" to "Триллер", "rma" to "Мелодрама", "scf" to "Фантастика", "spt" to "Спорт",
    "war" to "Военный", "wsn" to "Вестерн", "rly" to "Реалити", "eur" to "Европейский",
)

/** Popular titles of one streaming service in Ukraine, via JustWatch's public GraphQL. */
class JustWatchSource(
    private val context: Context,
    private val pkg: String,
    private val code: String,
    private val app: String,
    private val brand: Brand,
    private val art: Art,
) {
    fun rows(): List<ContentRow> {
        val all = query("all", null)
        val shows = query("shows", "SHOW")
        val movies = query("movies", "MOVIE")
        return listOfNotNull(
            all.takeIf { it.isNotEmpty() }?.let { ContentRow("Популярное на $app", it) },
            shows.takeIf { it.isNotEmpty() }?.let { ContentRow("Сериалы", it, portrait = true) },
            movies.takeIf { it.isNotEmpty() }?.let { ContentRow("Фильмы", it, portrait = true) },
        )
    }

    private fun query(name: String, type: String?): List<HeroItem> {
        val filter = JSONObject().put("packages", JSONArray().put(code))
        if (type != null) filter.put("objectTypes", JSONArray().put(type))
        val body = JSONObject()
            .put("query", Q)
            .put("variables", JSONObject().put("country", "UA").put("language", "ru").put("first", 20).put("filter", filter))
            .toString()
        val json = cachedFetch(context, "jw2_${code}_$name") { http("https://apis.justwatch.com/graphql", body) } ?: return emptyList()
        return runCatching { parse(json) }.onFailure { Log.w(TAG, "jw parse", it) }.getOrDefault(emptyList())
    }

    private fun parse(json: String): List<HeroItem> =
        nodes(JSONObject(json).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges"))

    /** Turns JustWatch title nodes ({edges:[{node}]}) into items; shared with "similar titles". */
    fun nodes(edges: JSONArray): List<HeroItem> {
        return (0 until edges.length()).mapNotNull { i ->
            val n = edges.getJSONObject(i).getJSONObject("node")
            val c = n.getJSONObject("content")
            val title = c.optString("title").ifBlank { return@mapNotNull null }
            val backdrop = c.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")?.let { img(it, "s1440") }
            val poster = c.optString("posterUrl").takeIf { it.isNotBlank() }?.let { img(it, "s592") }
            val offers = n.optJSONArray("offers") ?: JSONArray()
            val url = (0 until offers.length()).map { offers.getJSONObject(it) }
                .firstOrNull { it.optJSONObject("package")?.optString("shortName") == code }?.optString("standardWebURL")
            val genres = c.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { GENRES[g.getJSONObject(it).optString("shortName")] } }.orEmpty()
            val kind = if (n.optString("objectType") == "SHOW") "Сериал" else "Фильм"
            HeroItem(
                key = "jw:$code:$title",
                ref = n.optString("id").takeIf { it.isNotBlank() }?.let { "jw:$it" },
                app = app,
                brand = brand,
                title = title,
                meta = listOfNotNull(kind, c.optInt("originalReleaseYear").takeIf { it > 0 }?.toString(), genres.take(2).joinToString(", ").ifBlank { null }).joinToString(" · "),
                desc = c.optString("shortDescription"),
                art = art,
                poster = backdrop ?: poster,
                cover = poster,
                primary = "Смотреть",
                action = url?.let { Action.Launch(Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg)) }
                    ?: Action.Launch(context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: Intent()),
            )
        }
    }

    private fun img(path: String, profile: String) =
        "https://images.justwatch.com" + path.replace("{profile}", profile).replace("/.{format}", "/i.webp")

    companion object {
        /** Fields of a title node every list query asks for. */
        const val NODE = "id objectType " +
            "content(country:\$country,language:\$language){title shortDescription originalReleaseYear genres{shortName} backdrops{backdropUrl} posterUrl} " +
            "offers(country:\$country,platform:WEB){standardWebURL package{shortName}}"
        private const val Q = "query(\$country:Country!,\$language:Language!,\$first:Int!,\$filter:TitleFilter){" +
            "popularTitles(country:\$country,first:\$first,filter:\$filter){edges{node{$NODE}}}}"
    }
}

// ---------------------------------------------------------------- Megogo (own storefront)

/** Megogo's public storefront pages, parsed into the same rows the site shows. */
class MegogoSource(private val context: Context, private val brand: Brand, private val art: Art) {
    fun rows(): List<ContentRow> {
        val films = page("films", "https://megogo.net/ua/films/main")
        val series = page("series", "https://megogo.net/ua/series/main")
        // Interleave: the site's top rows first, then the rest.
        return (films.take(3) + series.take(2) + films.drop(3) + series.drop(2))
            .distinctBy { it.title }
            .take(8)
    }

    private fun page(name: String, url: String): List<ContentRow> {
        val html = cachedFetch(context, "mg_$name") { http(url) } ?: return emptyList()
        return runCatching { parse(html) }.onFailure { Log.w(TAG, "mg parse", it) }.getOrDefault(emptyList())
    }

    private fun parse(html: String): List<ContentRow> {
        val widgets = WIDGET.findAll(html).toList()
        return widgets.mapIndexedNotNull { wi, m ->
            val title = Html.fromHtml(m.groupValues[1], 0).toString().trim()
            if (title.startsWith("Розділ")) return@mapIndexedNotNull null
            val end = widgets.getOrNull(wi + 1)?.range?.first ?: html.length
            val seg = html.substring(m.range.last, end)
            val items = CARD.findAll(seg).mapNotNull { c ->
                val id = c.groupValues[1]
                val name = Html.fromHtml(c.groupValues[2], 0).toString()
                val img = IMG.find(c.value)?.groupValues?.get(1)?.substringBefore("/pt/") ?: return@mapNotNull null
                HeroItem(
                    key = "mg:$id",
                    ref = "mg:$id",
                    app = "Megogo",
                    brand = brand,
                    title = name,
                    meta = title,
                    desc = "",
                    art = art,
                    poster = img,
                    cover = "$img/pt/r386x544",
                    heroBias = -0.55f,
                    primary = "Смотреть",
                    action = Action.Launch(Intent(Intent.ACTION_VIEW, Uri.parse("megogo://video/$id")).setPackage(PKG_MEGOGO)),
                )
            }.distinctBy { it.key }.toList()
            items.takeIf { it.size >= 4 }?.let { ContentRow(title, it, portrait = true) }
        }
    }

    companion object {
        private val WIDGET = Regex("""data-feed-title="([^"]+)"""")
        private val CARD = Regex("""data-obj-id="(\d+)"\s+data-title="([^"]+)"[\s\S]*?</a>""")
        private val IMG = Regex("""data-original="(https://[^"]+)"""")
    }
}

// ---------------------------------------------------------------- YouTube

class YouTubeSource(private val context: Context) {
    private val trends = YouTubeTrends(context)

    fun rows(): List<ContentRow> {
        if (Secrets.youtubeKey.isBlank()) return emptyList()
        return listOfNotNull(
            trends.load().takeIf { it.isNotEmpty() }?.let { ContentRow("В тренде", it) },
            trends.load(category = "10").takeIf { it.isNotEmpty() }?.let { ContentRow("Музыка", it) },
            trends.load(category = "20").takeIf { it.isNotEmpty() }?.let { ContentRow("Игры", it) },
        )
    }
}
