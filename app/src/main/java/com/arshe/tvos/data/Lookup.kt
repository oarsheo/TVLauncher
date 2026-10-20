package com.arshe.tvos.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Artwork and deep link found for a title we only know by name. */
data class Found(val backdrop: String?, val link: String?)

/**
 * Resolves titles from media sessions (which carry no artwork for Megogo/Netflix) and
 * landscape backdrops for Megogo catalog items. Results are cached on disk forever.
 */
object Lookup {
    private val mem = mutableMapOf<String, Found?>()
    private var file: File? = null

    @Synchronized
    private fun cache(context: Context): MutableMap<String, Found?> {
        if (file == null) {
            file = File(context.filesDir, "lookup.json")
            runCatching {
                val o = JSONObject(file!!.readText())
                o.keys().forEach { k ->
                    val v = o.optJSONObject(k)
                    mem[k] = v?.let { Found(it.optString("b").ifEmpty { null }, it.optString("l").ifEmpty { null }) }
                }
            }
        }
        return mem
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
        mem.forEach { (k, v) -> o.put(k, v?.let { JSONObject().put("b", it.backdrop ?: "").put("l", it.link ?: "") } ?: JSONObject.NULL) }
        runCatching { file?.writeText(o.toString()) }
    }

    private fun memo(context: Context, key: String, compute: () -> Found?): Found? {
        val c = cache(context)
        if (c.containsKey(key)) return c[key]
        val v = runCatching(compute).onFailure { Log.w("TVOS", "lookup $key", it) }.getOrNull()
        synchronized(this) { c[key] = v }
        save()
        return v
    }

    /** Blocking. Title search for any supported app. */
    fun find(context: Context, pkg: String, query: String): Found? = when (pkg) {
        PKG_MEGOGO -> megogo(context, query)
        PKG_NETFLIX -> justWatch(context, "nfx", query)
        PKG_APPLETV -> justWatch(context, "atp", query)
        else -> null
    }

    /** Blocking. Landscape backdrop of a Megogo title page. */
    fun megogoBackdrop(context: Context, id: String): String? = memo(context, "mgb:$id") {
        val html = http("https://megogo.net/ua/view/$id") ?: return@memo null
        Found(BACKDROP.find(html)?.groupValues?.get(1)?.let { "$it/pt/r1600x0" }, "megogo://video/$id")
    }?.backdrop

    private fun megogo(context: Context, query: String): Found? = memo(context, "mg:$query") {
        val html = http("https://megogo.net/ua/search-extended?q=" + URLEncoder.encode(query, "UTF-8")) ?: return@memo null
        val id = CARD.find(html)?.groupValues?.get(1) ?: return@memo null
        Found(megogoBackdrop(context, id), "megogo://video/$id")
    }

    private fun justWatch(context: Context, code: String, query: String): Found? = memo(context, "jw2:$code:$query") {
        val body = JSONObject()
            .put("query", "query(\$country:Country!,\$language:Language!,\$filter:TitleFilter){popularTitles(country:\$country,first:1,filter:\$filter){edges{node{id content(country:\$country,language:\$language){backdrops{backdropUrl}} offers(country:\$country,platform:WEB){standardWebURL package{shortName}}}}}}")
            .put("variables", JSONObject().put("country", "UA").put("language", "ru")
                .put("filter", JSONObject().put("searchQuery", query).put("packages", JSONArray().put(code))))
        val json = http("https://apis.justwatch.com/graphql", body.toString()) ?: return@memo null
        val node = JSONObject(json).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges")
            .optJSONObject(0)?.getJSONObject("node") ?: return@memo null
        val bd = node.getJSONObject("content").optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")
        val offers = node.optJSONArray("offers") ?: JSONArray()
        val url = (0 until offers.length()).map { offers.getJSONObject(it) }
            .firstOrNull { it.optJSONObject("package")?.optString("shortName") == code }?.optString("standardWebURL")
        Found(bd?.let { "https://images.justwatch.com" + it.replace("{profile}", "s1440").replace("/.{format}", "/i.webp") }, url)
    }

    private val CARD = Regex("""data-obj-id="(\d+)"\s+data-title=""")
    private val BACKDROP = Regex("""data-bs-backdrop="(https://[^"]+?)"""")
}
