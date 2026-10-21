package com.arshe.cams

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

data class Camera(val id: String, val name: String, val main: String, val sub: String)

/**
 * NVR access lives in a file on the box, not in the APK:
 * /sdcard/Android/data/com.arshe.cams/files/nvr.properties (scripts/push-cams-config.sh)
 *   host=192.168.88.247  onvif.port=8080  user=…  pass=…  autostart=true
 *   weather.lat=50.45  weather.lon=30.52   (optional, Kyiv by default)
 *   night.from=23  night.to=7               (hours the wall dims, optional)
 *   alarm=Главный вход:Человек у главного входа; Гараж:Человек у гаража
 *                                           (cameras that announce a person, UTF-8)
 */
data class Nvr(
    val host: String,
    val onvifPort: Int,
    val user: String,
    val pass: String,
    val autostart: Boolean,
    val lat: Double,
    val lon: Double,
    val nightFrom: Int,
    val nightTo: Int,
    val alarms: Map<String, String>,
) {
    companion object {
        fun load(context: Context): Nvr? {
            val f = File(context.getExternalFilesDir(null), "nvr.properties")
            if (!f.exists()) return null
            val p = Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
            val host = p.getProperty("host")?.takeIf { it.isNotBlank() } ?: return null
            return Nvr(
                host,
                p.getProperty("onvif.port", "8080").toIntOrNull() ?: 8080,
                p.getProperty("user", ""),
                p.getProperty("pass", ""),
                p.getProperty("autostart", "true").toBoolean(),
                p.getProperty("weather.lat")?.toDoubleOrNull() ?: 50.45,
                p.getProperty("weather.lon")?.toDoubleOrNull() ?: 30.52,
                p.getProperty("night.from")?.toIntOrNull() ?: 23,
                p.getProperty("night.to")?.toIntOrNull() ?: 7,
                p.getProperty("alarm", "").split(';').mapNotNull { e ->
                    val (cam, phrase) = e.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    cam.trim().takeIf { it.isNotEmpty() }?.let { it to phrase.trim() }
                }.toMap(),
            )
        }
    }
}

/** Camera list over ONVIF: one main + one sub profile per camera ("<id>-main" / "<id>-sub" on Ajax). */
object Onvif {
    fun cameras(nvr: Nvr): List<Camera> {
        val device = "http://${nvr.host}:${nvr.onvifPort}/onvif/device_service"
        val caps = soap(nvr, device, "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>")
        val media = Regex("<(?:\\w+:)?Media>.*?<(?:\\w+:)?XAddr>([^<]+)<", RegexOption.DOT_MATCHES_ALL).find(caps)?.groupValues?.get(1)
            ?: "http://${nvr.host}:${nvr.onvifPort}/onvif/media_service"
        val profiles = soap(nvr, media, "<trt:GetProfiles/>")

        val starts = Regex("<(?:\\w+:)?Profiles\\b[^>]*\\btoken=\"([^\"]+)\"").findAll(profiles).toList()
        data class Profile(val token: String, val name: String, val uri: String)
        val list = starts.mapIndexed { i, m ->
            val seg = profiles.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: profiles.length)
            val token = m.groupValues[1]
            val name = Regex("<(?:\\w+:)?Name>([^<]*)<").find(seg)?.groupValues?.get(1)?.let(::unescape) ?: token
            val uri = soap(nvr, media,
                "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                    "<trt:ProfileToken>$token</trt:ProfileToken></trt:GetStreamUri>",
            ).let { Regex("<(?:\\w+:)?Uri>([^<]+)<").find(it)?.groupValues?.get(1)?.let(::unescape) }
            Profile(token, name, uri ?: "")
        }.filter { it.uri.isNotEmpty() }

        fun isSub(p: Profile) = p.token.endsWith("-sub") || p.name.endsWith("_sub", true)
        fun base(p: Profile) = p.token.removeSuffix("-main").removeSuffix("-sub")
        return list.groupBy(::base).map { (id, ps) ->
            val main = ps.firstOrNull { !isSub(it) } ?: ps.first()
            val sub = ps.firstOrNull(::isSub) ?: main
            Camera(id, main.name.removeSuffix("_main").removeSuffix("_sub"), main.uri, sub.uri)
        }.sortedWith(compareBy({ numbered(it.name) != null }, { numbered(it.name) ?: 0 }, { it.name.lowercase() }))
    }

    // "Камера 3" goes after the cameras somebody bothered to name.
    private fun numbered(name: String) = Regex("^(?:камера|camera|cam)\\s*(\\d+)$", RegexOption.IGNORE_CASE).find(name.trim())?.groupValues?.get(1)?.toInt()

    fun soap(nvr: Nvr, url: String, body: String, header: String = "", timeout: Int = 8000): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val digest = MessageDigest.getInstance("SHA-1").run {
            update(nonce); update(created.toByteArray()); update(nvr.pass.toByteArray()); digest()
        }
        val env = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tev="http://www.onvif.org/ver10/events/wsdl" xmlns:a="http://www.w3.org/2005/08/addressing"><s:Header>$header<Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${nvr.user}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${b64(digest)}</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${b64(nonce)}</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header><s:Body>$body</s:Body></s:Envelope>"""
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = timeout
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        c.outputStream.use { it.write(env.toByteArray()) }
        if (c.responseCode != 200) error("onvif ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** Last known camera list, so the wall comes up even if the NVR boots slower than the box. */
object CameraCache {
    fun load(context: Context): List<Camera> = runCatching {
        val a = JSONArray(prefs(context).getString("cameras", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).run { Camera(getString("id"), getString("name"), getString("main"), getString("sub")) } }
    }.getOrDefault(emptyList())

    fun save(context: Context, cams: List<Camera>) {
        val a = JSONArray(cams.map { JSONObject().put("id", it.id).put("name", it.name).put("main", it.main).put("sub", it.sub) })
        prefs(context).edit().putString("cameras", a.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("cams", Context.MODE_PRIVATE)
}
