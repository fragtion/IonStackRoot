package com.ionstack.root

import android.content.Context
import android.util.Base64
import java.io.*
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AdbShell(private val context: Context, private val log: ((String) -> Unit)? = null) {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_WRTE = 0x45545257
        private const val A_CLSE = 0x45534c43
        private const val A_STLS = 0x534c5453

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024

        private const val KEY_PREFS = "adb_keys"
        private const val PRIV_KEY_PREF = "private_key"
        private const val PUB_KEY_PREF = "public_key"

        private fun cmdName(cmd: Int): String = when (cmd) {
            A_CNXN -> "CNXN"; A_AUTH -> "AUTH"; A_OPEN -> "OPEN"
            A_OKAY -> "OKAY"; A_WRTE -> "WRTE"; A_CLSE -> "CLSE"
            A_STLS -> "STLS"; else -> "0x${cmd.toString(16)}"
        }
    }

    private data class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private var socket: Socket? = null
    private var inp: DataInputStream? = null
    private var out: OutputStream? = null
    private var nextId = 1

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed != true

    fun connect(host: String = "127.0.0.1", port: Int = 5555,
                promptAuth: Boolean = true) {
        log?.invoke("[adb] connecting to $host:$port\n")
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), 10_000)
        sock.soTimeout = 30_000
        sock.tcpNoDelay = true
        socket = sock
        inp = DataInputStream(sock.getInputStream())
        out = sock.getOutputStream()

        log?.invoke("[adb] TCP connected, sending CNXN\n")
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())

        val msg = recv()
        log?.invoke("[adb] got ${cmdName(msg.cmd)} arg0=${msg.arg0}\n")

        when {
            msg.cmd == A_CNXN -> log?.invoke("[adb] connected (no auth required)\n")
            msg.cmd == A_AUTH && msg.arg0 == AUTH_TOKEN -> doAuth(msg.data, promptAuth)
            msg.cmd == A_STLS -> throw IOException(
                "ADB daemon requires TLS (STLS). Disable Wireless Debugging " +
                "in developer options and use 'adb tcpip 5555' instead.")
            else -> throw IOException("Unexpected ADB response: ${cmdName(msg.cmd)}")
        }
    }

    fun shellExec(command: String, onOutput: (String) -> Unit) {
        val localId = nextId++
        send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())

        var opened = false
        while (true) {
            val msg = recv()
            when (msg.cmd) {
                A_OKAY -> opened = true
                A_WRTE -> {
                    onOutput(String(msg.data, Charsets.UTF_8))
                    send(A_OKAY, localId, msg.arg0)
                }
                A_CLSE -> return
                else -> if (!opened) throw IOException(
                    "Shell rejected: ${cmdName(msg.cmd)}")
            }
        }
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null; inp = null; out = null
    }

    // -- ADB wire protocol --------------------------------------------------

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(data.size)
        buf.putInt(data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) })
        buf.putInt(cmd xor 0xFFFFFFFF.toInt())
        buf.put(data)
        val bytes = buf.array()
        out!!.write(bytes)
        out!!.flush()
    }

    private fun recv(): Msg {
        val hdr = ByteArray(24)
        inp!!.readFully(hdr)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val len = buf.int
        buf.int  // checksum
        buf.int  // magic

        if (len < 0 || len > MAX_PAYLOAD)
            throw IOException("Bad ADB payload length: $len")

        val data = if (len > 0) ByteArray(len).also { inp!!.readFully(it) }
                   else ByteArray(0)
        return Msg(cmd, arg0, arg1, data)
    }

    // -- ADB auth ------------------------------------------------------------

    private fun doAuth(token: ByteArray, promptAuth: Boolean) {
        val kp = getOrCreateKeyPair()
        log?.invoke("[adb] signing auth token\n")

        val sig = Signature.getInstance("SHA1withRSA").run {
            initSign(kp.private)
            update(token)
            sign()
        }
        send(A_AUTH, AUTH_SIGNATURE, 0, sig)

        var msg = recv()
        log?.invoke("[adb] auth response: ${cmdName(msg.cmd)}\n")
        if (msg.cmd == A_CNXN) return

        if (msg.cmd == A_AUTH) {
            if (!promptAuth) {
                disconnect()
                throw IOException("Key not yet authorized (silent check)")
            }
            log?.invoke("[adb] sending public key — tap 'Allow' on device\n")
            send(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                encodeAdbPublicKey(kp.public as RSAPublicKey))
            socket!!.soTimeout = 120_000
            msg = recv()
            socket!!.soTimeout = 30_000
            log?.invoke("[adb] post-auth: ${cmdName(msg.cmd)}\n")
            if (msg.cmd == A_CNXN) return
        }
        throw IOException("ADB authentication rejected (${cmdName(msg.cmd)})")
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        val privB64 = prefs.getString(PRIV_KEY_PREF, null)
        val pubB64 = prefs.getString(PUB_KEY_PREF, null)

        if (privB64 != null && pubB64 != null) {
            log?.invoke("[adb] loaded existing RSA keypair\n")
            val kf = KeyFactory.getInstance("RSA")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
            )
        }

        log?.invoke("[adb] generating RSA keypair\n")
        val kp = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }
        prefs.edit()
            .putString(PRIV_KEY_PREF, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(PUB_KEY_PREF, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    // -- ADB RSA public key encoding -----------------------------------------

    private fun encodeAdbPublicKey(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val keyBytes = (n.bitLength() + 7) / 8
        val numWords = keyBytes / 4

        val mask32 = BigInteger.ONE.shiftLeft(32)
        val n0 = n.mod(mask32)
        val n0inv = n0.modInverse(mask32).negate().mod(mask32)

        val r = BigInteger.ONE.shiftLeft(numWords * 32)
        val rr = r.multiply(r).mod(n)

        val struct = ByteBuffer.allocate(4 + 4 + keyBytes + keyBytes + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        struct.putInt(numWords)
        struct.putInt(n0inv.toInt())
        struct.put(bigIntToLE(n, keyBytes))
        struct.put(bigIntToLE(rr, keyBytes))
        struct.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(struct.array(), Base64.NO_WRAP)
        return "$b64 ionstack@root\u0000".toByteArray()
    }

    private fun bigIntToLE(value: BigInteger, length: Int): ByteArray {
        val result = ByteArray(length)
        val be = value.toByteArray()
        val skip = if (be.isNotEmpty() && be[0].toInt() == 0) 1 else 0
        for (i in skip until be.size) {
            val leIdx = be.size - 1 - i
            if (leIdx in result.indices) result[leIdx] = be[i]
        }
        return result
    }
}
