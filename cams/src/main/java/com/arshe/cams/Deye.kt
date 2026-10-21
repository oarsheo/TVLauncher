package com.arshe.cams

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/** One reading of the house power: grid, battery, consumption, solar. Powers in W. */
data class Power(
    val grid: Boolean,
    val gridVolts: Int,
    val soc: Int,
    val battery: Int,   // + discharging, − charging
    val load: Int,
    val gridPower: Int, // + bought, − sold
    val pv: Int,
)

/**
 * A Deye hybrid inverter read over the LAN through its Solarman Wi-Fi logger: Modbus RTU wrapped
 * in the logger's "V5" frames on TCP 8899, no cloud. Register map of the three-phase hybrids.
 */
class Deye(private val host: String, private val serial: Long) {
    private var seq = 0

    fun read(): Power = Socket().use { s ->
        s.connect(InetSocketAddress(host, 8899), 5000)
        s.soTimeout = 5000
        val a = regs(s, 586, 68) // 586 … 653
        val pv = regs(s, 672, 2)
        fun r(n: Int) = a[n - 586]
        fun signed(v: Int) = if (v > 32767) v - 65536 else v
        val volts = (598..600).map { r(it) / 10 }
        Power(
            grid = volts.any { it > 150 },
            gridVolts = volts.max(),
            soc = r(588),
            battery = signed(r(590)) * 10, // the HV models count battery and PV power in tens of W
            load = signed(r(653)),
            gridPower = signed(r(625)),
            pv = pv.sum() * 10,
        )
    }

    private fun regs(s: Socket, start: Int, count: Int): IntArray {
        val mb = byteArrayOf(1, 3, (start shr 8).toByte(), start.toByte(), (count shr 8).toByte(), count.toByte())
        val crc = crc16(mb)
        val modbus = mb + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        val payload = byteArrayOf(2, 0, 0) + ByteArray(12) + modbus
        seq = (seq + 1) and 0xFF
        val head = byteArrayOf(
            0xA5.toByte(), payload.size.toByte(), (payload.size shr 8).toByte(), 0x10, 0x45, seq.toByte(), 0,
            serial.toByte(), (serial shr 8).toByte(), (serial shr 16).toByte(), (serial shr 24).toByte(),
        )
        val frame = head + payload
        val sum = frame.drop(1).sumOf { it.toInt() and 0xFF } and 0xFF
        s.getOutputStream().write(frame + byteArrayOf(sum.toByte(), 0x15))

        val inp = DataInputStream(s.getInputStream())
        val h = ByteArray(11).also { inp.readFully(it) }
        require(h[0] == 0xA5.toByte()) { "not a logger frame" }
        val len = (h[1].toInt() and 0xFF) or ((h[2].toInt() and 0xFF) shl 8)
        val body = ByteArray(len + 2).also { inp.readFully(it) }
        // Payload: type, status, three 4-byte times, then the Modbus reply.
        val m = body.copyOfRange(14, len)
        require(m.size >= 3 && m[1] == 3.toByte() && (m[2].toInt() and 0xFF) == count * 2) { "no modbus data" }
        return IntArray(count) { ((m[3 + it * 2].toInt() and 0xFF) shl 8) or (m[4 + it * 2].toInt() and 0xFF) }
    }

    private fun crc16(d: ByteArray): Int {
        var c = 0xFFFF
        for (b in d) {
            c = c xor (b.toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        }
        return c
    }
}
