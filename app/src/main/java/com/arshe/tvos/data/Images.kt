package com.arshe.tvos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal poster loader: content://, android.resource:// and http(s). Decodes to the exact
 * width asked for and keeps pixels only in GPU memory (HARDWARE bitmaps), so a poster costs
 * one texture instead of a texture plus a heap copy.
 */
/** Billboard decode width: the UI renders at 1080p and the hero sits behind scrims. */
const val HERO_PX = 1600

object Images {
    private val cache = object : LruCache<String, ImageBitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }

    /**
     * The same artwork in a size fit for a ~400 px card. Sources serve resized copies, so a
     * card downloads and decodes ~10x fewer pixels than the billboard version.
     */
    fun cardUrl(url: String?): String? = url?.let {
        when {
            "images.justwatch.com/" in it -> it.replace(Regex("/s\\d+/"), "/s640/")
            "i.ytimg.com/vi/" in it -> it.replace(Regex("/[a-z]*default(_live)?\\.jpg"), "/hqdefault$1.jpg")
            "vcdn.biz/static/" in it -> it.replace(Regex("/pt/r\\d+x\\d+"), "/pt/r400x0")
            else -> it
        }
    }

    /** Drop decoded images (the disk cache stays); called when the launcher goes to the background. */
    fun clear() = cache.evictAll()

    suspend fun load(context: Context, uri: String, maxWidth: Int): ImageBitmap? {
        val key = "$uri@$maxWidth"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = open(context, uri)?.use { it.readBytes() } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
                if (bmp.width > maxWidth) {
                    val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
                    bmp = Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
                }
                (bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp).also { if (it !== bmp) bmp.recycle() }.asImageBitmap()
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    private fun open(context: Context, uri: String): InputStream? = when {
        uri.startsWith("http") -> {
            // Remote artwork is kept on disk so launches and the catalog refresh don't re-download it.
            val dir = File(context.cacheDir, "img").apply { mkdirs() }
            val f = File(dir, Integer.toHexString(uri.hashCode()) + "_" + uri.length)
            if (!f.exists()) {
                val tmp = File(dir, f.name + ".part")
                (URL(uri).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                }.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(f)
                trim(dir)
            }
            f.inputStream()
        }
        else -> context.contentResolver.openInputStream(Uri.parse(uri))
    }

    private fun trim(dir: File, maxBytes: Long = 120L * 1024 * 1024) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}

@Composable
fun rememberPoster(uri: String?, maxWidth: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, uri) {
        value = uri?.let { Images.load(context, it, maxWidth) }
    }
}
