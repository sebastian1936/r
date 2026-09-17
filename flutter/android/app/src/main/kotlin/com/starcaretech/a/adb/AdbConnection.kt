package com.starcaretech.a.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * ADB 协议（TLS 之上的 CNXN/OPEN/OKAY/WRTE/CLSE 帧），对照 AOSP types.h/adb.h/protocol.md：
 *  - amessage：24 字节，6 个 u32 全小端：cmd, arg0, arg1, data_length, data_check, magic
 *  - data_check = 数据字节和（mod 2^32）；magic = cmd ^ 0xFFFFFFFF
 *  - A_VERSION 0x01000001（支持 skip-checksum，但发送方仍填充校验和以保证兼容）
 *  - 连接：CNXN(arg0=version, arg1=max_payload, data=system identity string) → 对端回 CNXN
 *  - 执行命令：OPEN(arg0=localId, data="shell:xxx") → 对端 OKAY(arg0=remoteId, arg1=localId)
 *    → 对端 WRTE(arg0=remoteId, arg1=localId) 携带输出 → CLSE 结束
 */
object AdbConnection {

    private const val A_CNXN: Long = 0x4e584e43L
    private const val A_OPEN: Long = 0x4e45504fL
    private const val A_OKAY: Long = 0x59414b4fL
    private const val A_CLSE: Long = 0x45534c43L
    private const val A_WRTE: Long = 0x45545257L
    private const val A_AUTH: Long = 0x48545541L
    private const val A_STLS: Long = 0x534c5453L

    private const val A_VERSION: Long = 0x01000001L
    private const val MAX_PAYLOAD: Long = 256L * 1024

    class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 在已配对设备上执行 shell 命令，返回命令输出。
     * @param host 设备地址（本机回环场景即 127.0.0.1）
     * @param port adbd 无线调试 TLS 端口（mDNS `_adb-tls-connect._tcp` 解析得到）
     */
    fun shell(identity: AdbKeyStore.Identity, host: String, port: Int, command: String, timeoutMs: Int = 10_000): String {
        val socket = AdbKeyStore.createTlsSocket(identity, host, port, timeoutMs)
        try {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val input = DataInputStream(socket.getInputStream().buffered())

            // ---- CNXN 握手 ----
            val identityString = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,shell_v2"
            send(out, Frame(A_CNXN, A_VERSION, MAX_PAYLOAD, identityString.toByteArray(StandardCharsets.US_ASCII)))

            var maxPayload = MAX_PAYLOAD
            while (true) {
                val frame = readFrame(input)
                when (frame.cmd) {
                    A_CNXN -> {
                        maxPayload = frame.arg1
                        break
                    }
                    A_AUTH, A_STLS -> throw AdbException("adbd 要求额外的认证/升级（当前环境不应出现）")
                    else -> throw AdbException("CNXN 阶段收到意外命令: 0x${frame.cmd.toString(16)}")
                }
            }

            // ---- OPEN shell ----
            val localId = 1L
            send(out, Frame(A_OPEN, localId, 0L, command.toByteArray(StandardCharsets.US_ASCII)))

            var remoteId = 0L
            val output = ByteArrayOutputStream()
            while (true) {
                val frame = readFrame(input)
                when (frame.cmd) {
                    A_OKAY -> {
                        remoteId = frame.arg0
                    }
                    A_WRTE -> {
                        if (remoteId != 0L && frame.arg1 != localId) {
                            throw AdbException("WRTE arg1 不匹配: ${frame.arg1} != $localId")
                        }
                        send(out, Frame(A_OKAY, localId, frame.arg0))
                        output.write(frame.data)
                    }
                    A_CLSE -> {
                        return output.toString(StandardCharsets.UTF_8.name())
                    }
                    A_CNXN -> { /* 忽略重复 CNXN */ }
                    else -> throw AdbException("shell 阶段收到意外命令: 0x${frame.cmd.toString(16)}")
                }
            }
        } finally {
            runCatching { socket.close() }
        }
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
        // 对端在 skip-checksum 版本下可能不发校验和（为 0），仅在非 0 且不匹配时告警
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
