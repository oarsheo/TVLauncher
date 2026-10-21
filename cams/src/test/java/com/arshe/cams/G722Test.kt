package com.arshe.cams

import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes a 440 Hz + 3 kHz tone encoded by ffmpeg and compares with ffmpeg's own decode. */
class G722Test {
    @Test
    fun matchesFfmpeg() {
        val g722 = javaClass.getResourceAsStream("/tone.g722")!!.readBytes()
        val ref = javaClass.getResourceAsStream("/tone_ref.pcm")!!.readBytes()
        val out = ShortArray(g722.size * 2)
        val n = G722().decode(g722, 0, g722.size, out)
        var maxDiff = 0
        for (i in 0 until n) {
            val r = ((ref[2 * i + 1].toInt() shl 8) or (ref[2 * i].toInt() and 0xff)).toShort().toInt()
            maxDiff = maxOf(maxDiff, kotlin.math.abs(r - out[i]))
        }
        println("samples=$n maxDiff=$maxDiff")
        assertTrue("max diff $maxDiff", maxDiff <= 2)
    }
}
