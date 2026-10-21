package com.arshe.cams

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** What the device can decode in hardware, and how many streams at once. */
object Decoders {
    private val infos by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }

    private fun software(i: MediaCodecInfo) =
        if (Build.VERSION.SDK_INT >= 29) i.isSoftwareOnly else i.name.startsWith("OMX.google.") || i.name.startsWith("c2.android.")

    private fun find(mime: String, sw: Boolean) =
        infos.firstOrNull { i -> i.supportedTypes.any { it.equals(mime, true) } && software(i) == sw }

    /** Simultaneous hardware decoders (MediaTek TVs: 2, Amlogic boxes: 9). */
    fun hardwareSlots(mime: String = MediaFormat.MIMETYPE_VIDEO_HEVC): Int =
        runCatching { find(mime, false)?.getCapabilitiesForType(mime)?.maxSupportedInstances ?: 0 }.getOrDefault(0)

    fun softwareName(mime: String): String? = find(mime, true)?.name
}
