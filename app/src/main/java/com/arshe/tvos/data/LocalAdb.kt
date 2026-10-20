package com.arshe.tvos.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Minimal ADB client talking to the TV's own adbd over 127.0.0.1:5555 (network ADB is on).
 * It gives the launcher shell-level powers an app can't have (e.g. reboot). The first
 * connection makes the TV ask once to allow this launcher's key.
 */
object LocalAdb {
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 4096
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    // PKCS#1 v1.5 type-1 padding + SHA-1 DigestInfo prefix; adbd sends a 20-byte token to sign.
    private val PADDING = ByteArray(236).also { p ->
        p[0] = 0; p[1] = 1
        for (i in 2 until 220) p[i] = 0xff.toByte()
        p[220] = 0
        byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
            .copyInto(p, 221)
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Runs a shell command and returns its output, or null if the connection was refused. */
    suspend fun shell(context: Context, command: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
                sock.soTimeout = 60_000 // the first time the user has to accept the key on screen
                val input = DataInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                send(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
                val keys = keys(context)
                var signed = false
                while (true) {
                    val m = read(input)
                    if (m.cmd == CNXN) break
                    if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) continue
                    if (!signed) {
                        signed = true
                        val c = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.private) }
                        c.update(PADDING)
                        send(out, AUTH, AUTH_SIGNATURE, 0, c.doFinal(m.data))
                    } else {
                        send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(keys.public as RSAPublicKey))
                    }
                }
                sock.soTimeout = 15_000
                send(out, OPEN, 1, 0, "shell:$command\u0000".toByteArray())
                val result = StringBuilder()
                while (true) {
                    val m = read(input)
                    when (m.cmd) {
                        WRTE -> { result.append(String(m.data)); send(out, OKAY, 1, m.arg0, ByteArray(0)) }
                        CLSE -> break
                    }
                }
                result.toString()
            }
        }.onFailure { Log.w("TVOS", "local adb: $command", it) }.getOrNull()
    }

    private fun send(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(data)
        out.write(b.array())
        out.flush()
    }

    private fun read(input: DataInputStream): Msg {
        val h = ByteArray(24).also { input.readFully(it) }
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len).also { input.readFully(it) }
        return Msg(cmd, a0, a1, data)
    }

    private fun keys(context: Context): KeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    /** adbd's own public-key format: little-endian modulus words plus Montgomery helpers. */
    private fun publicKey(key: RSAPublicKey): ByteArray {
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        var n = key.modulus
        val rr = BigInteger.ONE.shiftLeft(4096).mod(key.modulus)
        val n0inv = key.modulus.mod(r32).modInverse(r32).negate().mod(r32)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv.toInt())
        repeat(words) { b.putInt(n.mod(r32).toInt()); n = n.shiftRight(32) }
        var r = rr
        repeat(words) { b.putInt(r.mod(r32).toInt()); r = r.shiftRight(32) }
        b.putInt(key.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvos@launcher\u0000").toByteArray()
    }
}
