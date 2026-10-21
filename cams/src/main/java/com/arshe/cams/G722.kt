package com.arshe.cams

/**
 * ITU-T G.722 decoder, 64 kbit/s mode: one octet in, two 16 kHz PCM samples out.
 * Android has no G.722 codec; this follows the ITU reference (as in spandsp / ffmpeg).
 */
class G722 {
    private class Band {
        var s = 0; var sp = 0; var sz = 0
        val r = IntArray(3); val a = IntArray(3); val ap = IntArray(3); val p = IntArray(3)
        val d = IntArray(7); val b = IntArray(7); val bp = IntArray(7); val sg = IntArray(7)
        var nb = 0; var det = 0
    }

    private val band = arrayOf(Band().apply { det = 32 }, Band().apply { det = 8 })
    private val x = IntArray(24)

    /** Decodes [len] octets of [src] from [off] into [out]; returns the number of samples. */
    fun decode(src: ByteArray, off: Int, len: Int, out: ShortArray): Int {
        var n = 0
        for (k in off until off + len) {
            val code = src[k].toInt() and 0xff
            var wd1 = code and 0x3f
            val ihigh = (code shr 6) and 0x03

            // Low band
            val lo = band[0]
            var wd2 = QM6[wd1]
            wd1 = wd1 shr 2
            var rlow = lo.s + ((lo.det * wd2) shr 15)
            rlow = rlow.coerceIn(-16384, 16383)
            wd2 = QM4[wd1]
            val dlow = (lo.det * wd2) shr 15
            wd2 = RL42[wd1]
            wd1 = ((lo.nb * 127) shr 7) + WL[wd2]
            lo.nb = wd1.coerceIn(0, 18432)
            wd1 = (lo.nb shr 6) and 31
            wd2 = 8 - (lo.nb shr 11)
            lo.det = (if (wd2 < 0) ILB[wd1] shl -wd2 else ILB[wd1] shr wd2) shl 2
            block4(lo, dlow)

            // High band
            val hi = band[1]
            val dhigh = (hi.det * QM2[ihigh]) shr 15
            val rhigh = (dhigh + hi.s).coerceIn(-16384, 16383)
            wd1 = ((hi.nb * 127) shr 7) + WH[RH2[ihigh]]
            hi.nb = wd1.coerceIn(0, 22528)
            wd1 = (hi.nb shr 6) and 31
            wd2 = 10 - (hi.nb shr 11)
            hi.det = (if (wd2 < 0) ILB[wd1] shl -wd2 else ILB[wd1] shr wd2) shl 2
            block4(hi, dhigh)

            // Receive QMF
            System.arraycopy(x, 2, x, 0, 22)
            x[22] = rlow + rhigh
            x[23] = rlow - rhigh
            var out1 = 0
            var out2 = 0
            for (i in 0 until 12) {
                out2 += x[2 * i] * QMF[i]
                out1 += x[2 * i + 1] * QMF[11 - i]
            }
            out[n++] = sat(out1 shr 11).toShort()
            out[n++] = sat(out2 shr 11).toShort()
        }
        return n
    }

    private fun block4(b: Band, d: Int) {
        // RECONS, PARREC
        b.d[0] = d
        b.r[0] = sat(b.s + d)
        b.p[0] = sat(b.sz + d)
        // UPPOL2
        for (i in 0 until 3) b.sg[i] = b.p[i] shr 15
        var wd1 = sat(b.a[1] shl 2)
        var wd2 = if (b.sg[0] == b.sg[1]) -wd1 else wd1
        if (wd2 > 32767) wd2 = 32767
        var wd3 = (wd2 shr 7) + if (b.sg[0] == b.sg[2]) 128 else -128
        wd3 += (b.a[2] * 32512) shr 15
        b.ap[2] = wd3.coerceIn(-12288, 12288)
        // UPPOL1
        b.sg[0] = b.p[0] shr 15
        b.sg[1] = b.p[1] shr 15
        wd1 = if (b.sg[0] == b.sg[1]) 192 else -192
        wd2 = (b.a[1] * 32640) shr 15
        b.ap[1] = sat(wd1 + wd2)
        wd3 = sat(15360 - b.ap[2])
        b.ap[1] = b.ap[1].coerceIn(-wd3, wd3)
        // UPZERO
        wd1 = if (d == 0) 0 else 128
        b.sg[0] = d shr 15
        for (i in 1 until 7) {
            b.sg[i] = b.d[i] shr 15
            wd2 = if (b.sg[i] == b.sg[0]) wd1 else -wd1
            wd3 = (b.b[i] * 32640) shr 15
            b.bp[i] = sat(wd2 + wd3)
        }
        // DELAYA
        for (i in 6 downTo 1) { b.d[i] = b.d[i - 1]; b.b[i] = b.bp[i] }
        for (i in 2 downTo 1) { b.r[i] = b.r[i - 1]; b.p[i] = b.p[i - 1]; b.a[i] = b.ap[i] }
        // FILTEP
        wd1 = (b.a[1] * sat(b.r[1] + b.r[1])) shr 15
        wd2 = (b.a[2] * sat(b.r[2] + b.r[2])) shr 15
        b.sp = sat(wd1 + wd2)
        // FILTEZ
        var sz = 0
        for (i in 6 downTo 1) sz += (b.b[i] * sat(b.d[i] + b.d[i])) shr 15
        b.sz = sat(sz)
        // PREDIC
        b.s = sat(b.sp + b.sz)
    }

    private fun sat(v: Int) = v.coerceIn(-32768, 32767)

    private companion object {
        val WL = intArrayOf(-60, -30, 58, 172, 334, 538, 1198, 3042)
        val RL42 = intArrayOf(0, 7, 6, 5, 4, 3, 2, 1, 7, 6, 5, 4, 3, 2, 1, 0)
        val ILB = intArrayOf(
            2048, 2093, 2139, 2186, 2233, 2282, 2332, 2383, 2435, 2489, 2543, 2599, 2656, 2714, 2774, 2834,
            2896, 2960, 3025, 3091, 3158, 3228, 3298, 3371, 3444, 3520, 3597, 3676, 3756, 3838, 3922, 4008,
        )
        val WH = intArrayOf(0, -214, 798)
        val RH2 = intArrayOf(2, 1, 2, 1)
        val QM2 = intArrayOf(-7408, -1616, 7408, 1616)
        val QM4 = intArrayOf(0, -20456, -12896, -8968, -6288, -4240, -2584, -1200, 20456, 12896, 8968, 6288, 4240, 2584, 1200, 0)
        val QM6 = intArrayOf(
            -136, -136, -136, -136, -24808, -21904, -19008, -16704, -14984, -13512, -12280, -11192, -10232, -9360, -8576, -7856,
            -7192, -6576, -6000, -5456, -4944, -4464, -4008, -3576, -3168, -2776, -2400, -2032, -1688, -1360, -1040, -728,
            24808, 21904, 19008, 16704, 14984, 13512, 12280, 11192, 10232, 9360, 8576, 7856, 7192, 6576, 6000, 5456,
            4944, 4464, 4008, 3576, 3168, 2776, 2400, 2032, 1688, 1360, 1040, 728, 432, 136, -432, -136,
        )
        val QMF = intArrayOf(3, -11, 12, 32, -210, 951, 3876, -805, 362, -156, 53, -11)
    }
}
