package com.starcaretech.a.adb

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.Signature
import javax.net.ssl.SSLSocket

/**
 * ADB 协议客户端（对照 Shizuku AdbClient.kt 与 AOSP adb）。
 *
 * 重要：无线调试 connect 端口（_adb-tls-connect._tcp）不是"一连上就是 TLS"，
 * 它和传统 ADB 端口一样先走明文，再由 adbd 发 A_STLS 通知升级：
 *
 *  1. 明文 TCP 连接
 *  2. 明文发 A_CNXN(version=0x01000000, maxdata=4096, "host::")
 *  3. 对端回 A_STLS  → 我方回 A_STLS(0x01000000) → 在同一 socket 上升级 TLSv1.3
 *     （配对端口才是 implicit TLS，见 AdbPairingClient；两条路径不要混淆）
 *  4. 升级后等 A_CNXN；若收到 A_AUTH TOKEN 则用私钥签名应答（SHA1withRSA），
 *     必要时再发公钥
 *  5. OPEN shell:xxx → OKAY → WRTE 输出 → CLSE
 *
 * amessage：24 字节，6 个 u32 全小端：cmd, arg0, arg1, data_length, data_check, magic。
 */
object AdbConnection {

    private const val TAG = "AdbConnection"

    private const val A_CNXN: Long = 0x4e584e43L
    private const val A_OPEN: Long = 0x4e45504fL
    private const val A_OKAY: Long = 0x59414b4fL
    private const val A_CLSE: Long = 0x45534c43L
    private const val A_WRTE: Long = 0x45545257L
    private const val A_AUTH: Long = 0x48545541L
    private const val A_STLS: Long = 0x534c5453L

    // 注意必须是 0x01000000（Shizuku/AOSP 实测值），不是 0x01000001
    private const val A_VERSION: Long = 0x01000000L
    private const val A_STLS_VERSION: Long = 0x01000000L
    private const val A_MAXDATA: Long = 4096L
    private const val MAX_PAYLOAD: Long = 256L * 1024

    private const val ADB_AUTH_TOKEN = 1L
    private const val ADB_AUTH_SIGNATURE = 2L
    private const val ADB_AUTH_RSAPUBLICKEY = 3L

    class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 在已配对设备上执行 shell 命令，返回命令输出。
     * @param host 设备地址（本机回环场景即 127.0.0.1）
     * @param port adbd 无线调试 TLS 端口（mDNS `_adb-tls-connect._tcp` 解析得到）
     */
    fun shell(identity: AdbKeyStore.Identity, host: String, port: Int, command: String, timeoutMs: Int = 10_000): String {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.soTimeout = timeoutMs
        try {
            plain.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw AdbException("阶段1-TCP连接失败 ${host}:${port}：${e.javaClass.simpleName}: ${e.message}", e)
        }

        try {
            var input = DataInputStream(plain.getInputStream())
            var out = DataOutputStream(plain.getOutputStream())

            // ---- 明文 CNXN ----
            send(out, Frame(A_CNXN, A_VERSION, A_MAXDATA, "host::".toByteArray(StandardCharsets.US_ASCII)))

            var frame = readFrameOrFail(input, "等待 adbd 应答（CNXN/STLS/AUTH）")
            if (frame.cmd == A_STLS) {
                // 对端要求升级 TLS：回一帧 A_STLS，然后把同一 socket 包成 TLS
                send(out, Frame(A_STLS, A_STLS_VERSION, 0L, ByteArray(0)))
                val ssl: SSLSocket = try {
                    AdbKeyStore.wrapTlsSocket(identity, plain, host, port, timeoutMs)
                } catch (e: Exception) {
                    throw AdbException("阶段2-TLS握手失败：${e.javaClass.simpleName}: ${e.message}", e)
                }
                input = DataInputStream(ssl.inputStream)
                out = DataOutputStream(ssl.outputStream)
                frame = readFrameOrFail(input, "TLS 升级后等待 CNXN")
            }

            // ---- CNXN / A_AUTH 认证循环 ----
            var authRound = 0
            while (frame.cmd != A_CNXN) {
                if (frame.cmd == A_AUTH && frame.arg0 == ADB_AUTH_TOKEN && authRound == 0) {
                    // 用配对时同一把私钥对 20 字节 token 做 SHA1withRSA 签名
                    val sig = try {
                        val s = Signature.getInstance("SHA1withRSA")
                        s.initSign(identity.keyPair.private)
                        s.update(frame.data)
                        s.sign()
                    } catch (e: Exception) {
                        throw AdbException("阶段3-AUTH签名失败：${e.javaClass.simpleName}: ${e.message}", e)
                    }
                    send(out, Frame(A_AUTH, ADB_AUTH_SIGNATURE, 0L, sig))
                    frame = readFrameOrFail(input, "签名后等待 CNXN")
                    authRound++
                } else if (frame.cmd == A_AUTH && frame.arg0 == ADB_AUTH_TOKEN && authRound == 1) {
                    // 签名未被接受，再发公钥（通常会触发系统授权弹窗；本机无线调试一般走不到）
                    send(out, Frame(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0L,
                        identity.publicKeyString.toByteArray(StandardCharsets.US_ASCII)))
                    frame = readFrameOrFail(input, "公钥应答后等待 CNXN")
                    authRound++
                } else {
                    throw AdbException(
                        "阶段3-连接被拒绝：收到 0x${frame.cmd.toString(16)}（arg0=${frame.arg0}）。" +
                        "配对可能未被系统接受，请关闭无线调试后重新配对")
                }
            }

            // ---- OPEN shell ----
            val localId = 1L
            try {
                send(out, Frame(A_OPEN, localId, 0L, command.toByteArray(StandardCharsets.US_ASCII)))
            } catch (e: Exception) {
                throw AdbException("阶段4-发送命令失败：${e.javaClass.simpleName}: ${e.message}", e)
            }

            var remoteId = 0L
            val output = ByteArrayOutputStream()
            while (true) {
                val f = readFrameOrFail(input, "等待命令输出")
                when (f.cmd) {
                    A_OKAY -> {
                        // Shizuku: write(A_OKAY, localId, remoteId=frame.arg0)
                        remoteId = f.arg0
                    }
                    A_WRTE -> {
                        send(out, Frame(A_OKAY, localId, f.arg0))
                        output.write(f.data)
                    }
                    A_CLSE -> {
                        // 礼貌回一帧 CLSE（Shizuku 同样会回）
                        runCatching { send(out, Frame(A_CLSE, localId, f.arg0)) }
                        return output.toString(StandardCharsets.UTF_8.name())
                    }
                    A_CNXN -> { /* 忽略重复 CNXN */ }
                    else -> throw AdbException("shell 阶段收到意外命令: 0x${f.cmd.toString(16)}")
                }
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun readFrameOrFail(input: DataInputStream, stage: String): Frame =
        try {
            readFrame(input)
        } catch (e: AdbException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$stage 失败", e)
            throw AdbException("$stage 失败：${e.javaClass.simpleName}: ${e.message}", e)
        }

    private class Frame(val cmd: Long, val arg0: Long, val arg1: Long, val data: ByteArray = ByteArray(0))

    private fun send(out: DataOutputStream, frame: Frame) {
        val header = ByteArray(24)
        putU32Le(header, 0, frame.cmd)
        putU32Le(header, 4, frame.arg0)
        putU32Le(header, 8, frame.arg1)
        putU32Le(header, 12, frame.data.size.toLong())
        putU32Le(header, 16, checksum(frame.data))
        putU32Le(header, 20, frame.cmd xor 0xffffffffL)
        out.write(header)
        if (frame.data.isNotEmpty()) out.write(frame.data)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): Frame {
        val header = ByteArray(24).also { input.readFully(it) }
        val cmd = getU32Le(header, 0)
        val arg0 = getU32Le(header, 4)
        val arg1 = getU32Le(header, 8)
        val dataLen = getU32Le(header, 12)
        val dataCheck = getU32Le(header, 16)
        val magic = getU32Le(header, 20)

        if (magic != (cmd xor 0xffffffffL)) throw AdbException("ADB 帧校验失败（magic 不匹配）")
        if (dataLen > MAX_PAYLOAD) throw AdbException("ADB 帧数据超长: $dataLen")

        val data = if (dataLen > 0) ByteArray(dataLen.toInt()).also { input.readFully(it) } else ByteArray(0)
        // 对端在 skip-checksum 版本下可能不发校验和（为 0），仅在非 0 且不匹配时失败
        val actual = checksum(data)
        if (dataCheck != 0L && dataCheck != actual) throw AdbException("ADB 帧校验失败（checksum 不匹配）")
        return Frame(cmd, arg0, arg1, data)
    }

    private fun checksum(data: ByteArray): Long {
        var sum = 0L
        for (b in data) sum += (b.toInt() and 0xff)
        return sum and 0xffffffffL
    }

    private fun putU32Le(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xffL).toByte()
        dst[off + 1] = ((v ushr 8) and 0xffL).toByte()
        dst[off + 2] = ((v ushr 16) and 0xffL).toByte()
        dst[off + 3] = ((v ushr 24) and 0xffL).toByte()
    }

    private fun getU32Le(src: ByteArray, off: Int): Long =
        (src[off].toLong() and 0xffL) or
                ((src[off + 1].toLong() and 0xffL) shl 8) or
                ((src[off + 2].toLong() and 0xffL) shl 16) or
                ((src[off + 3].toLong() and 0xffL) shl 24)
}
