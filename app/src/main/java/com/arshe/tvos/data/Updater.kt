package com.arshe.tvos.data

import android.content.Context
import android.util.Log
import com.arshe.tvos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates from GitHub Releases: tag "v<versionCode>" with an .apk asset.
 * The APK is installed through [LocalAdb] (shell can install silently) and AOT-compiled
 * right after, so an update never leaves the launcher running in slow JIT mode.
 */
object Updater {
    enum class State { Idle, Checking, UpToDate, Downloading, Ready, Installing, Failed }

    data class Status(val state: State, val version: Int? = null)

    @Volatile var status = Status(State.Idle)
        private set

    private val repo get() = BuildConfig.UPDATE_REPO
    private val token get() = BuildConfig.UPDATE_TOKEN

    val enabled: Boolean get() = repo.isNotBlank()

    /** Checks the latest release and downloads it when newer. Returns true when an update is ready to install. */
    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        apk(context).takeIf { it.exists() && status.state == State.Ready }?.let { return@withContext true }
        status = Status(State.Checking)
        runCatching {
            val rel = JSONObject(get("https://api.github.com/repos/$repo/releases/latest") ?: error("no release"))
            val version = rel.optString("tag_name").removePrefix("v").toIntOrNull() ?: error("bad tag")
            if (version <= BuildConfig.VERSION_CODE) { status = Status(State.UpToDate, version); return@runCatching false }
            val assets = rel.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.optString("name").endsWith(".apk") }
            status = Status(State.Downloading, version)
            download(asset.getString("url"), apk(context))
            status = Status(State.Ready, version)
            true
        }.getOrElse {
            Log.w("TVOS", "update check", it)
            status = Status(State.Failed)
            false
        }
    }

    /**
     * Installs the downloaded APK. The command is detached (nohup) because installing
     * replaces this very process; it then recompiles and brings the launcher back.
     */
    suspend fun install(context: Context) {
        val f = apk(context).takeIf { it.exists() } ?: return
        status = Status(State.Installing, status.version)
        val pkg = context.packageName
        LocalAdb.shell(
            context,
            "nohup sh -c 'pm install -r \"${f.absolutePath}\" && cmd package compile -m speed -f $pkg; " +
                "rm -f \"${f.absolutePath}\"; am start -a android.intent.action.MAIN -c android.intent.category.HOME' >/dev/null 2>&1 &",
        )
    }

    // Shell (which runs pm install) can read app-specific external storage, not our private dir.
    private fun apk(context: Context) = File(context.getExternalFilesDir("update"), "TVLauncher.apk")

    private fun get(url: String): String? {
        val c = open(url, "application/vnd.github+json")
        return if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null.also { Log.w("TVOS", "github ${c.responseCode} $url") }
    }

    private fun download(url: String, dest: File) {
        var c = open(url, "application/octet-stream")
        c.instanceFollowRedirects = false
        // Assets redirect to a signed storage URL that must be fetched without our token.
        if (c.responseCode in 300..399) c = (URL(c.getHeaderField("Location")).openConnection() as HttpURLConnection)
        if (c.responseCode != 200) error("download ${c.responseCode}")
        val tmp = File(dest.parentFile, dest.name + ".part")
        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(dest)
    }

    private fun open(url: String, accept: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 30000
        instanceFollowRedirects = false
        setRequestProperty("Accept", accept)
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
}
