package com.arshe.cams

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class Link { Connecting, Live, Offline }

/**
 * Minimal RTSP client: RTP interleaved over TCP, H.265/H.264 depacketizing, frames straight
 * into the hardware decoder and onto [surface] as soon as they're decoded (no A/V sync, no
 * jitter buffer: lowest latency). Ajax NVRs announce no sprop-* parameter sets in the SDP,
 * which ExoPlayer refuses; here they're picked up in-band before the first keyframe.
 * With [sound], the G.722 audio track plays too. With [thumbs], only keyframes are decoded, in
 * software (about one picture a second): for grids on devices with few hardware decoders, which
 * stay free for the fullscreen camera. Reconnects on its own until [stop].
 */
class RtspStream(
    private val url: String,
    private val user: String,
    private val pass: String,
    private val surface: Surface,
    private val sound: Boolean = false,
    private val thumbs: Boolean = false,
    private val onLink: (Link) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile var muted = false
    private val stopped = CountDownLatch(1)
    private var thread: Thread? = null
    private var video: Video? = null
    private var audio: Audio? = null

    fun start() {
        running = true
        thread = Thread(::loop, "rtsp").apply { start() }
    }

    /** Blocks until the decoder is released, so the surface can go away right after. */
    fun stop() {
        running = false
        stopped.countDown()
        runCatching { socket?.close() }
        thread?.join(3000)
        thread = null
    }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            try {
                t0 = System.currentTimeMillis()
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress(URI(url).host, URI(url).port.takeIf { it > 0 } ?: 554), 5000)
                    s.soTimeout = 10_000
                    s.tcpNoDelay = true
                    session(s)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "${url.substringAfterLast('/')}: $e")
            } finally {
                if (video?.live == true) backoff = 1000L
                video?.release()
                video = null
                audio?.release()
                audio = null
            }
            if (!running) break
            onLink(Link.Offline)
            if (stopped.await(backoff, TimeUnit.MILLISECONDS)) break
            backoff = (backoff * 2).coerceAtMost(15_000)
        }
    }

    private val tag get() = url.substringAfterLast('/')
    private var t0 = 0L
    private fun mark(what: String) = Log.i(TAG, "$tag: $what +${System.currentTimeMillis() - t0}ms")

    private fun session(s: Socket) {
        mark("connected")
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val rtsp = Rtsp(input, s.getOutputStream())

        val desc = rtsp.request("DESCRIBE", url, "Accept: application/sdp\r\n")
        val tracks = Sdp.tracks(desc.body)
        val track = tracks.firstOrNull { it.codec == "H265" || it.codec == "HEVC" || it.codec == "H264" } ?: error("no video track")
        val base = desc.header("content-base") ?: url
        fun trackUrl(control: String) = when {
            control.startsWith("rtsp://") -> control
            control == "*" || control.isEmpty() -> base
            else -> base.trimEnd('/') + "/" + control
        }
        fun channel(r: Response, default: Int) =
            r.header("transport")?.let { Regex("interleaved=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() } ?: default

        val setup = rtsp.request("SETUP", trackUrl(track.control), "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        rtsp.session = setup.header("session")?.substringBefore(';')?.trim()
        val channel = channel(setup, 0)
        var audioChannel = -1
        val sound = tracks.firstOrNull { it.codec == "G722" }?.takeIf { sound }
        if (sound != null) {
            audioChannel = channel(rtsp.request("SETUP", trackUrl(sound.control), "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n"), 2)
            audio = Audio()
        }
        rtsp.request("PLAY", base, "Range: npt=0.000-\r\n")
        mark("playing")

        val video = Video(track.codec != "H264").also { this.video = it }
        val buf = ByteArray(1 shl 16)
        var keepAlive = System.currentTimeMillis()
        while (running) {
            val b = input.readUnsignedByte()
            if (b == '$'.code) {
                val ch = input.readUnsignedByte()
                val len = input.readUnsignedShort()
                input.readFully(buf, 0, len)
                if (ch == channel) video.rtp(buf, len)
                else if (ch == audioChannel) audio?.rtp(buf, len)
            } else {
                rtsp.skipResponse(b) // reply to a keep-alive
            }
            val now = System.currentTimeMillis()
            if (now - keepAlive > 20_000) {
                rtsp.send("OPTIONS", url, "")
                keepAlive = now
            }
        }
    }

    // --- RTSP ---------------------------------------------------------------------------

    private class Response(val code: Int, val headers: List<Pair<String, String>>, val body: String) {
        fun header(name: String) = headers.firstOrNull { it.first == name }?.second
    }

    private inner class Rtsp(private val input: DataInputStream, private val out: OutputStream) {
        private var cseq = 1
        private var realm: String? = null
        private var nonce: String? = null
        private var basic = false
        var session: String? = null

        fun request(method: String, uri: String, extra: String): Response {
            send(method, uri, extra)
            var r = read()
            if (r.code == 401) {
                val auth = r.headers.filter { it.first == "www-authenticate" }.map { it.second }
                val digest = auth.firstOrNull { it.startsWith("Digest", true) }
                if (digest != null) {
                    realm = Regex("realm=\"([^\"]*)\"").find(digest)?.groupValues?.get(1)
                    nonce = Regex("nonce=\"([^\"]*)\"").find(digest)?.groupValues?.get(1)
                } else {
                    basic = true
                }
                send(method, uri, extra)
                r = read()
            }
            if (r.code != 200) error("$method ${r.code}")
            return r
        }

        fun send(method: String, uri: String, extra: String) {
            val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: Cams\r\n")
            if (nonce != null) {
                val ha1 = md5("$user:$realm:$pass")
                val ha2 = md5("$method:$uri")
                sb.append("Authorization: Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"${md5("$ha1:$nonce:$ha2")}\"\r\n")
            } else if (basic) {
                sb.append("Authorization: Basic ${android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)}\r\n")
            }
            session?.let { sb.append("Session: $it\r\n") }
            sb.append(extra).append("\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
        }

        private fun read(): Response {
            var first = input.readUnsignedByte()
            // Skip RTP that may already be flowing ahead of a reply.
            while (first == '$'.code) {
                input.readUnsignedByte()
                input.skipBytes(input.readUnsignedShort())
                first = input.readUnsignedByte()
            }
            return readFrom(first)
        }

        fun skipResponse(first: Int) { readFrom(first) }

        private fun readFrom(first: Int): Response {
            val status = Char(first) + line()
            val headers = mutableListOf<Pair<String, String>>()
            while (true) {
                val l = line()
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers += l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }
            val len = headers.firstOrNull { it.first == "content-length" }?.second?.toIntOrNull() ?: 0
            val body = ByteArray(len).also { input.readFully(it) }
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return Response(code, headers, String(body))
        }

        private fun line(): String {
            val sb = ByteArrayOutputStream()
            while (true) {
                val c = input.readUnsignedByte()
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.write(c)
            }
            return sb.toString("UTF-8")
        }
    }

    private object Sdp {
        class Track(val codec: String, val control: String)

        fun tracks(sdp: String): List<Track> {
            val out = mutableListOf<Track>()
            var pt: String? = null
            var codec: String? = null
            var control = ""
            var inMedia = false
            fun close() { if (inMedia && codec != null) out += Track(codec!!, control) }
            for (raw in sdp.lineSequence()) {
                val l = raw.trim()
                if (l.startsWith("m=")) {
                    close()
                    inMedia = true
                    pt = l.split(' ').getOrNull(3)
                    codec = null
                    control = ""
                } else if (inMedia && l.startsWith("a=rtpmap:$pt ")) {
                    codec = l.substringAfter(' ').substringBefore('/').uppercase()
                } else if (inMedia && l.startsWith("a=control:")) {
                    control = l.removePrefix("a=control:")
                }
            }
            close()
            return out
        }
    }

    // --- Audio ---------------------------------------------------------------------------

    /** G.722 → 16 kHz PCM with a gentle automatic gain (the cameras' microphones are quiet). */
    private inner class Audio {
        private val decoder = G722()
        private val pcm = ShortArray(4096)
        private var gain = 4f
        private var peak = 1000f
        private val track: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, 16000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        fun rtp(b: ByteArray, len: Int) {
            if (len < 12) return
            var off = 12 + (b[0].toInt() and 0x0f) * 4
            if (b[0].toInt() and 0x10 != 0) off += 4 + u16(b, off + 2) * 4
            var end = len
            if (b[0].toInt() and 0x20 != 0) end -= b[len - 1].toInt() and 0xff
            if (off >= end || (end - off) * 2 > pcm.size) return
            val n = decoder.decode(b, off, end - off, pcm)
            if (muted) return

            var p = 0
            for (i in 0 until n) p = maxOf(p, kotlin.math.abs(pcm[i].toInt()))
            peak = if (p > peak) p.toFloat() else peak * 0.998f + p * 0.002f
            val target = (12000f / peak.coerceAtLeast(300f)).coerceIn(1f, MAX_GAIN)
            gain += (target - gain) * (if (target < gain) 0.5f else 0.02f) // duck fast, rise slowly
            for (i in 0 until n) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            track.write(pcm, 0, n, AudioTrack.WRITE_NON_BLOCKING) // never stall the video
        }

        fun release() {
            runCatching { track.stop() }
            track.release()
        }
    }

    // --- Video ---------------------------------------------------------------------------

    private inner class Video(private val hevc: Boolean) {
        private var codec: MediaCodec? = null
        private var drain: Thread? = null
        @Volatile private var draining = false
        @Volatile var live = false

        private val au = ByteArrayOutputStream(1 shl 18)
        private var auKey = false
        private var auTs = -1L
        private val fu = ByteArrayOutputStream(1 shl 18)
        private var fuOk = false
        private var lastSeq = -1
        private var lastTs = -1L
        private var clock = 0L
        private var needKey = true
        private var vps: ByteArray? = null
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null

        fun rtp(b: ByteArray, len: Int) {
            if (len < 12) return
            val cc = b[0].toInt() and 0x0f
            val seq = u16(b, 2)
            val ts = u32(b, 4)
            var off = 12 + cc * 4
            if (b[0].toInt() and 0x10 != 0) off += 4 + u16(b, off + 2) * 4
            var end = len
            if (b[0].toInt() and 0x20 != 0) end -= b[len - 1].toInt() and 0xff
            if (off >= end) return
            if (lastSeq >= 0 && seq != (lastSeq + 1) and 0xffff) fuOk = false
            lastSeq = seq
            if (ts != auTs && au.size() > 0) flush()
            auTs = ts
            if (hevc) hevcPayload(b, off, end) else avcPayload(b, off, end)
            // A frame ends where the timestamp changes: some cameras set the marker on every
            // slice, and flushing on it would feed the decoder partial pictures.
        }

        private fun hevcPayload(b: ByteArray, off: Int, end: Int) {
            when ((b[off].toInt() shr 1) and 0x3f) {
                48 -> { // aggregation packet
                    var p = off + 2
                    while (p + 2 <= end) {
                        val n = u16(b, p); p += 2
                        if (p + n > end) break
                        nal(b, p, n); p += n
                    }
                }
                49 -> { // fragmentation unit
                    if (end - off < 3) return
                    val h = b[off + 2].toInt()
                    if (h and 0x80 != 0) {
                        fu.reset()
                        fu.write((b[off].toInt() and 0x81) or ((h and 0x3f) shl 1))
                        fu.write(b[off + 1].toInt())
                        fuOk = true
                    }
                    if (!fuOk) return
                    fu.write(b, off + 3, end - off - 3)
                    if (h and 0x40 != 0) { fuOk = false; fu.toByteArray().let { nal(it, 0, it.size) } }
                }
                else -> nal(b, off, end - off)
            }
        }

        private fun avcPayload(b: ByteArray, off: Int, end: Int) {
            when (b[off].toInt() and 0x1f) {
                24 -> { // STAP-A
                    var p = off + 1
                    while (p + 2 <= end) {
                        val n = u16(b, p); p += 2
                        if (p + n > end) break
                        nal(b, p, n); p += n
                    }
                }
                28 -> { // FU-A
                    if (end - off < 2) return
                    val h = b[off + 1].toInt()
                    if (h and 0x80 != 0) {
                        fu.reset()
                        fu.write((b[off].toInt() and 0xe0) or (h and 0x1f))
                        fuOk = true
                    }
                    if (!fuOk) return
                    fu.write(b, off + 2, end - off - 2)
                    if (h and 0x40 != 0) { fuOk = false; fu.toByteArray().let { nal(it, 0, it.size) } }
                }
                else -> nal(b, off, end - off)
            }
        }

        private fun nal(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (hevc) {
                when (val t = (b[off].toInt() shr 1) and 0x3f) {
                    32 -> vps = b.copyOfRange(off, off + len)
                    33 -> sps = b.copyOfRange(off, off + len)
                    34 -> pps = b.copyOfRange(off, off + len)
                    in 16..21 -> auKey = true
                    else -> if (t >= 41) return // reserved/unspecified: SEI-like vendor data
                }
            } else {
                when (b[off].toInt() and 0x1f) {
                    7 -> sps = b.copyOfRange(off, off + len)
                    8 -> pps = b.copyOfRange(off, off + len)
                    5 -> auKey = true
                }
            }
            au.write(START)
            au.write(b, off, len)
        }

        private fun flush() {
            val data = au.toByteArray()
            val key = auKey
            au.reset()
            auKey = false
            val pts = pts(auTs)
            if (codec == null && (!key || !configure())) return
            if ((needKey || thumbs) && !key) return
            needKey = false
            val c = codec ?: return
            val i = c.dequeueInputBuffer(200_000)
            if (i < 0) { Log.w(TAG, "${url.substringAfterLast('/')}: no input buffer, key=$key"); needKey = true; return }
            val ib = c.getInputBuffer(i) ?: return
            if (data.size > ib.capacity()) { Log.w(TAG, "${url.substringAfterLast('/')}: frame ${data.size} > buffer ${ib.capacity()}"); c.queueInputBuffer(i, 0, 0, pts, 0); needKey = true; return }
            ib.clear()
            ib.put(data)
            c.queueInputBuffer(i, 0, data.size, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }

        private fun pts(ts: Long): Long {
            if (lastTs >= 0) clock += ((ts - lastTs).toInt()).toLong() // signed 32-bit delta survives wraparound
            lastTs = ts
            return clock * 100 / 9 // 90 kHz -> µs
        }

        private fun configure(): Boolean {
            val sps = sps ?: return false
            val pps = pps ?: return false
            val vps = vps
            if (hevc && vps == null) return false
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val (w, h) = H26x.size(hevc, sps) ?: (1920 to 1080)
            val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 shl 20)
                if (hevc) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START + vps!! + START + sps + START + pps))
                } else {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
                }
            }
            return try {
                val c = (if (thumbs) Decoders.softwareName(mime) else null)?.let { MediaCodec.createByCodecName(it) }
                    ?: MediaCodec.createDecoderByType(mime)
                c.configure(fmt, surface, null, 0)
                c.start()
                codec = c
                draining = true
                drain = Thread({ drain(c) }, "rtsp-out").apply { start() }
                mark("${c.name} ${w}x$h")
                true
            } catch (e: Exception) {
                Log.w(TAG, "decoder", e)
                false
            }
        }

        private fun drain(c: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (draining) {
                val i = try { c.dequeueOutputBuffer(info, 100_000) } catch (_: IllegalStateException) { break }
                if (i >= 0) {
                    c.releaseOutputBuffer(i, true)
                    if (!live) { live = true; mark("first frame"); onLink(Link.Live) }
                }
            }
        }

        fun release() {
            draining = false
            runCatching { drain?.join(1000) }
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            codec = null
        }
    }

    companion object {
        private const val TAG = "Cams"
        private val START = byteArrayOf(0, 0, 0, 1)
        private const val MAX_GAIN = 10f

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Picture size from an SPS, so the decoder is configured for the stream it gets. */
object H26x {
    fun size(hevc: Boolean, nal: ByteArray): Pair<Int, Int>? = runCatching {
        val r = Bits(unescape(nal))
        if (hevc) hevcSize(r) else avcSize(r)
    }.getOrNull()

    private fun hevcSize(r: Bits): Pair<Int, Int> {
        r.skip(16) // NAL header
        r.skip(4)
        val maxSub = r.u(3)
        r.skip(1)
        r.skip(88 + 8) // general profile + level
        val profile = BooleanArray(maxSub)
        val level = BooleanArray(maxSub)
        for (i in 0 until maxSub) { profile[i] = r.u(1) == 1; level[i] = r.u(1) == 1 }
        if (maxSub > 0) for (i in maxSub until 8) r.skip(2)
        for (i in 0 until maxSub) { if (profile[i]) r.skip(88); if (level[i]) r.skip(8) }
        r.ue() // sps id
        if (r.ue() == 3) r.skip(1)
        return r.ue() to r.ue()
    }

    private fun avcSize(r: Bits): Pair<Int, Int> {
        r.skip(8)
        val profile = r.u(8)
        r.skip(16)
        r.ue()
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            if (r.ue() == 3) r.skip(1)
            r.ue(); r.ue(); r.skip(1)
            if (r.u(1) == 1) error("scaling matrix")
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.skip(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.skip(1)
        val w = (r.ue() + 1) * 16
        val h = r.ue() + 1
        val frameOnly = r.u(1)
        return w to (2 - frameOnly) * h * 16
    }

    private fun unescape(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xff
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            zeros = if (v == 0) zeros + 1 else 0
            out.write(v)
        }
        return out.toByteArray()
    }

    private class Bits(private val b: ByteArray) {
        private var pos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun skip(n: Int) { pos += n }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) zeros++
            return ((1 shl zeros) - 1) + u(zeros)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }
}
