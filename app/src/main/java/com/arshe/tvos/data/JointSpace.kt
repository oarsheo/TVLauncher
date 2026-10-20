package com.arshe.tvos.data

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Ambilight / TV controls exposed by the Philips JointSpace API (v6) on the TV itself. */
data class AmbiState(
    val power: Boolean,
    /** Index in [AMBI_MODES]; -1 when the TV reports something else (e.g. OFF while powered down). */
    val mode: Int,
    val brightness: Int,
    val sleepMin: Int,
)

/** Ambilight styles shown in quick settings, as (label, styleName, menuSetting). */
val AMBI_MODES = listOf(
    Triple("Видео", "FOLLOW_VIDEO", "STANDARD"),
    Triple("Кино", "FOLLOW_VIDEO", "IMMERSIVE"),
    Triple("Игра", "FOLLOW_VIDEO", "GAME"),
    Triple("Музыка", "FOLLOW_AUDIO", "ENERGY_ADAPTIVE_BRIGHTNESS"),
    Triple("Лава", "FOLLOW_COLOR", "HOT_LAVA"),
    Triple("Океан", "FOLLOW_COLOR", "DEEP_WATER"),
    Triple("Природа", "FOLLOW_COLOR", "FRESH_NATURE"),
)

val SLEEP_STEPS = listOf(0, 30, 60, 90, 120)

/**
 * Tiny JointSpace client: loopback HTTPS with the TV's self-signed certificate and
 * HTTP digest auth using the credentials from pairing (scripts/philips-pair.py).
 */
object JointSpace {
    private const val BASE = "https://127.0.0.1:1926/6"
    private const val NODE_AMBI_BRIGHTNESS = 2130968780
    private const val NODE_SLEEP = 2130968712

    val configured: Boolean get() = Secrets.jointspaceUser.isNotBlank()

    suspend fun state(): AmbiState? = withContext(Dispatchers.IO) {
        runCatching {
            // The TV's API server is slow; ask for everything at once.
            val powerQ = async { get("ambilight/power") }
            val cfgQ = async { get("ambilight/currentconfiguration") }
            val valuesQ = async {
                post("menuitems/settings/current", JSONObject().put("nodes", JSONArray()
                    .put(JSONObject().put("nodeid", NODE_AMBI_BRIGHTNESS))
                    .put(JSONObject().put("nodeid", NODE_SLEEP))))
            }
            val power = powerQ.await()?.optString("power") == "On"
            val cfg = cfgQ.await()
            val mode = AMBI_MODES.indexOfFirst { it.second == cfg?.optString("styleName") && it.third == cfg.optString("menuSetting") }
            val values = valuesQ.await()?.optJSONArray("values")
            fun value(i: Int) = values?.optJSONObject(i)?.optJSONObject("value")?.optJSONObject("data")?.optInt("value") ?: 0
            AmbiState(power, mode, value(0), value(1))
        }.onFailure { Log.w("TVOS", "jointspace state", it) }.getOrNull()
    }

    suspend fun setPower(on: Boolean) = send("ambilight/power", JSONObject().put("power", if (on) "On" else "Off"))

    suspend fun setMode(i: Int) {
        val (_, style, setting) = AMBI_MODES[i]
        send("ambilight/power", JSONObject().put("power", "On"))
        send("ambilight/currentconfiguration", JSONObject().put("styleName", style).put("isExpert", false).put("menuSetting", setting))
    }

    suspend fun setBrightness(v: Int) = setNode(NODE_AMBI_BRIGHTNESS, v)

    suspend fun setSleep(min: Int) = setNode(NODE_SLEEP, min)

    suspend fun standby() = send("input/key", JSONObject().put("key", "Standby"))

    private suspend fun setNode(id: Int, v: Int) = send(
        "menuitems/settings/update",
        JSONObject().put("values", JSONArray().put(JSONObject().put("value",
            JSONObject().put("Nodeid", id).put("Controllable", true).put("Available", true)
                .put("data", JSONObject().put("value", v))))),
    )

    private suspend fun send(path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        runCatching { post(path, body) }.onFailure { Log.w("TVOS", "jointspace $path", it) }
        Unit
    }

    private fun get(path: String) = request("GET", path, null)

    private fun post(path: String, body: JSONObject) = request("POST", path, body.toString())

    /** Last digest challenge; reused with an increasing nonce count to skip the 401 round trip. */
    @Volatile private var challenge: Map<String, String>? = null
    private val nc = java.util.concurrent.atomic.AtomicInteger(0)

    private fun request(method: String, path: String, body: String?): JSONObject? {
        val url = URL("$BASE/$path")
        var auth: String? = challenge?.let { digest(it, method, url.path) }
        repeat(2) { attempt ->
            val c = (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = tls.socketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == "127.0.0.1" }
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 5000
                auth?.let { setRequestProperty("Authorization", it) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = c.responseCode
            if (code == 401 && attempt == 0) {
                val p = parse(c.getHeaderField("WWW-Authenticate") ?: return null)
                challenge = p
                nc.set(0)
                auth = digest(p, method, url.path)
                return@repeat
            }
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) { Log.w("TVOS", "jointspace $code $path $text"); return null }
            return text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        }
        return null
    }

    private fun parse(header: String) =
        Regex("""(\w+)="?([^",]*)"?""").findAll(header.removePrefix("Digest ")).associate { it.groupValues[1] to it.groupValues[2] }

    private fun digest(p: Map<String, String>, method: String, uri: String): String {
        val realm = p["realm"].orEmpty()
        val nonce = p["nonce"].orEmpty()
        val qop = p["qop"]?.split(',')?.firstOrNull()?.trim()
        val user = Secrets.jointspaceUser
        val ha1 = md5("$user:$realm:${Secrets.jointspacePass}")
        val ha2 = md5("$method:$uri")
        val cnonce = md5(System.nanoTime().toString()).take(16)
        val nc = "%08x".format(nc.incrementAndGet())
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            p["opaque"]?.let { append(", opaque=\"$it\"") }
            p["algorithm"]?.let { append(", algorithm=$it") }
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The TV's API uses a self-signed certificate; trust it only for this loopback client. */
    @get:SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val tls: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), SecureRandom())
        }
    }
}
