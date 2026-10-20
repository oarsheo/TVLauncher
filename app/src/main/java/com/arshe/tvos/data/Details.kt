package com.arshe.tvos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.util.Log
import org.json.JSONObject

data class Episode(
    val number: Int,
    val title: String,
    val desc: String,
    val minutes: Int?,
    val image: String?,
    val action: Action?,
)

data class Season(val title: String, val ref: String)

data class Detail(
    val kind: String,
    val chips: List<String>,
    val imdb: String?,
    val genres: String,
    val desc: String,
    val cast: String?,
    val backdrop: String?,
    val seasons: List<Season>,
    /** Episodes of the season the page opened with (others load on demand). */
    val firstEpisodes: List<Episode>,
    val similar: List<HeroItem>,
)

/** Blocking loaders for the details screen; call off the main thread. */
class DetailsRepository(private val context: Context) {

    fun load(item: HeroItem, pkg: String, brand: Brand, art: com.arshe.tvos.ui.Art): Detail? {
        val ref = item.ref ?: resolveRef(item, pkg) ?: return null
        return runCatching {
            when {
                ref.startsWith("jw:") -> justWatch(ref.removePrefix("jw:"), pkg, brand, art)
                ref.startsWith("mg:") -> megogo(ref.removePrefix("mg:"))
                else -> null
            }
        }.onFailure { Log.w("TVOS", "details $ref", it) }.getOrNull()
    }

    fun episodes(season: Season, pkg: String): List<Episode> = runCatching {
        when {
            season.ref.startsWith("jw:") -> jwEpisodes(season.ref.removePrefix("jw:"), pkg)
            season.ref.startsWith("mg:") -> megogoEpisodes(http("https://megogo.net/ua/view/${season.ref.removePrefix("mg:")}") ?: return emptyList())
            else -> emptyList()
        }
    }.onFailure { Log.w("TVOS", "episodes ${season.ref}", it) }.getOrDefault(emptyList())

    /** Items from media sessions only know a name: find them in the catalog. */
    private fun resolveRef(item: HeroItem, pkg: String): String? {
        (item.action as? Action.Launch)?.intent?.data?.toString()?.let { link ->
            Regex("""megogo://video/(\d+)""").find(link)?.let { return "mg:${it.groupValues[1]}" }
        }
        return Lookup.find(context, pkg, item.title)?.ref
    }

    // ------------------------------------------------------------ JustWatch

    private fun gql(query: String, vars: JSONObject): JSONObject? {
        val body = JSONObject().put("query", query).put("variables", vars.put("country", "UA").put("language", "ru"))
        val key = "jwd_" + Integer.toHexString(body.toString().hashCode())
        val json = cachedFetch(context, key) { http("https://apis.justwatch.com/graphql", body.toString()) } ?: return null
        return JSONObject(json).optJSONObject("data")
    }

    private fun justWatch(id: String, pkg: String, brand: Brand, art: com.arshe.tvos.ui.Art): Detail? {
        val q = "query(\$id:ID!,\$country:Country!,\$language:Language!){node(id:\$id){__typename " +
            "... on MovieOrShow{content(country:\$country,language:\$language){shortDescription originalReleaseYear runtime ageCertification " +
            "scoring{imdbScore} genres{shortName} backdrops{backdropUrl} credits(role:ACTOR,first:6){name}} " +
            "similarTitlesV2(country:\$country,first:12){edges{node{${JustWatchSource.NODE}}}}} " +
            "... on Show{totalSeasonCount seasons{id content(country:\$country,language:\$language){seasonNumber title}}}}}"
        val node = gql(q, JSONObject().put("id", id))?.optJSONObject("node") ?: return null
        val c = node.getJSONObject("content")
        val show = node.optString("__typename") == "Show"
        val code = if (pkg == PKG_APPLETV) "atp" else "nfx"
        val app = if (pkg == PKG_APPLETV) "Apple TV+" else "Netflix"
        val similar = node.optJSONObject("similarTitlesV2")?.optJSONArray("edges")?.let {
            JustWatchSource(context, pkg, code, app, brand, art).nodes(it)
        }.orEmpty().filter { (it.action as? Action.Launch)?.intent?.data != null }
        val seasons = node.optJSONArray("seasons")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                val sc = it.optJSONObject("content")
                Season("Сезон ${sc?.optInt("seasonNumber") ?: ""}".trim(), "jw:" + it.getString("id"))
            }
        }.orEmpty()
        val runtime = c.optInt("runtime").takeIf { it > 0 }
        val chips = listOfNotNull(
            c.optInt("originalReleaseYear").takeIf { it > 0 }?.toString(),
            c.optString("ageCertification").takeIf { it.isNotBlank() && it != "null" },
            if (show) node.optInt("totalSeasonCount").takeIf { it > 0 }?.let { plural(it, "сезон", "сезона", "сезонов") }
            else runtime?.let { minutes(it) },
        )
        val genres = c.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { GENRE_NAMES[g.getJSONObject(it).optString("shortName")] } }.orEmpty()
        val cast = c.optJSONArray("credits")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
        return Detail(
            kind = if (show) "Сериал" else "Фильм",
            chips = chips,
            imdb = c.optJSONObject("scoring")?.optDouble("imdbScore")?.takeIf { !it.isNaN() && it > 0 }?.let { "%.1f".format(it) },
            genres = genres.joinToString(", "),
            desc = c.optString("shortDescription"),
            cast = cast.takeIf { it.isNotEmpty() }?.joinToString(", "),
            backdrop = c.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")?.let {
                "https://images.justwatch.com" + it.replace("{profile}", "s1440").replace("/.{format}", "/i.webp")
            },
            seasons = seasons,
            firstEpisodes = seasons.firstOrNull()?.let { jwEpisodes(it.ref.removePrefix("jw:"), pkg) }.orEmpty(),
            similar = similar,
        )
    }

    private fun jwEpisodes(seasonId: String, pkg: String): List<Episode> {
        val q = "query(\$id:ID!,\$country:Country!,\$language:Language!){node(id:\$id){... on Season{episodes{" +
            "content(country:\$country,language:\$language){title episodeNumber shortDescription runtime}}}}}"
        val eps = gql(q, JSONObject().put("id", seasonId))?.optJSONObject("node")?.optJSONArray("episodes") ?: return emptyList()
        return (0 until eps.length()).map { eps.getJSONObject(it).getJSONObject("content") }.map {
            Episode(it.optInt("episodeNumber"), it.optString("title"), it.optString("shortDescription"), it.optInt("runtime").takeIf { r -> r > 0 }, null, null)
        }
    }

    // ------------------------------------------------------------ Megogo

    private fun megogo(id: String): Detail? {
        val html = http("https://megogo.net/ua/view/$id") ?: return null
        fun text(s: String) = Html.fromHtml(s, 0).toString().replace(Regex("\\s+"), " ").trim()
        val info = Regex("""class="video-info">([\s\S]*?)</div>""").find(html)?.groupValues?.get(1)?.let(::text).orEmpty()
        val desc = Regex("""class="video-description">[\s\S]*?<p>([\s\S]*?)</p>""").find(html)?.groupValues?.get(1)?.let(::text).orEmpty()
        val imdb = Regex("""<span class="key">IMDb</span>\s*<span class="value">\s*([\d.,]+)""").find(html)?.groupValues?.get(1)?.trimEnd(',')
        val seasons = Regex("""data-season="\{&quot;id&quot;: (\d+)\}"\s+data-season-title="([^"]+)"""").findAll(html)
            .map { Season(Html.fromHtml(it.groupValues[2], 0).toString(), "mg:" + it.groupValues[1]) }.distinctBy { it.ref }.toList()
        val year = Regex("""^\d{4}(\s*-\s*\d{4})?""").find(info)?.value
        val genres = info.removePrefix(year.orEmpty()).trim(' ', ',')
        return Detail(
            kind = if (seasons.isNotEmpty()) "Серіал" else "Фільм",
            chips = listOfNotNull(year, seasons.size.takeIf { it > 0 }?.let { plural(it, "сезон", "сезони", "сезонів") }),
            imdb = imdb,
            genres = genres,
            desc = desc,
            cast = null,
            backdrop = Regex("""data-bs-backdrop="(https://[^"]+?)"""").find(html)?.groupValues?.get(1)?.let { "$it/pt/r1600x0" },
            seasons = seasons,
            firstEpisodes = megogoEpisodes(html),
            similar = emptyList(),
        )
    }

    private fun megogoEpisodes(html: String): List<Episode> =
        Regex("""videoItem-(\d+)[^>]*data-episode-title="([^"]+)"[\s\S]{0,900}?<img src="(https://[^"]+?)/pt/[^"]*"""").findAll(html)
            .map { m ->
                val title = Html.fromHtml(m.groupValues[2], 0).toString()
                Episode(
                    number = Regex("""\d+""").find(title)?.value?.toIntOrNull() ?: 0,
                    title = title.replace(Regex("""^Серія\s*\d+\.\s*"""), ""),
                    desc = "",
                    minutes = null,
                    image = m.groupValues[3] + "/pt/r640x0",
                    action = Action.Launch(Intent(Intent.ACTION_VIEW, Uri.parse("megogo://video/${m.groupValues[1]}")).setPackage(PKG_MEGOGO)),
                )
            }.distinctBy { it.action.toString() }.toList()

    companion object {
        private val GENRE_NAMES = mapOf(
            "act" to "Боевик", "ani" to "Анимация", "cmy" to "Комедия", "crm" to "Криминал", "doc" to "Документальный",
            "drm" to "Драма", "fml" to "Семейный", "fnt" to "Фэнтези", "hst" to "История", "hrr" to "Ужасы",
            "msc" to "Музыка", "trl" to "Триллер", "rma" to "Мелодрама", "scf" to "Фантастика", "spt" to "Спорт",
            "war" to "Военный", "wsn" to "Вестерн", "rly" to "Реалити",
        )

        fun minutes(m: Int) = if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"

        fun plural(n: Int, one: String, few: String, many: String): String {
            val w = when {
                n % 100 in 11..14 -> many
                n % 10 == 1 -> one
                n % 10 in 2..4 -> few
                else -> many
            }
            return "$n $w"
        }
    }
}
