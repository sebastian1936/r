package com.starcaretech.a.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over Curve25519 —— 与 AOSP adbd 配对服务端使用的 BoringSSL spake25519.cc 完全一致。
 *
 * 本类只实现客户端角色（spake2_role_alice）；adbd 的配对服务端是 bob。
 * 协议要点（全部对照源码核实）：
 *  - 临时标量 = 64 随机字节（小端）mod L，再 x8（清 cofactor）
 *  - password_hash = SHA512(password) 完整 64 字节（进入 transcript）
 *  - password_scalar = SHA512(password)（小端）mod L（源码中的 +l/+2l/+4l hack 与其数学等价）
 *  - client: P = k*B + s*M；server: P = k*B + s*N
 *  - unmask: Q = their_msg - s*(N / M)；dh = k*Q
 *  - transcript = SHA512( 逐段加 8 字节小端长度前缀：
 *      client_name, server_name, client_msg, server_msg, dh(32), password_hash(64) )
 *  - key = transcript 的 SHA512 摘要（64 字节）
 *  - name 常量（含 NUL，各 16 字节）："adb pair client\0" / "adb pair server\0"
 */
internal class Spake2Client(
    role: Role,
    password: ByteArray,
    random: SecureRandom = SecureRandom()
) {
    enum class Role { CLIENT, SERVER }

    private val isClient = role == Role.CLIENT
    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val passwordScalar: BigInteger = Edwards25519.fromBytesLe(passwordHash).mod(Edwards25519.L)
    private val privateKey: BigInteger = Edwards25519.randomEphemeralScalar(random)
    private val myMsg: ByteArray

    init {
        val maskBase = if (isClient) POINT_M else POINT_N
        val p = Edwards25519.scalarMul(privateKey, Edwards25519.BASE_POINT)
        val mask = Edwards25519.scalarMul(passwordScalar, maskBase)
        myMsg = Edwards25519.encode(Edwards25519.add(p, mask))
    }

    /** 我方的 SPAKE2 消息（32 字节，即 PairingPacket SPAKE2_MSG 载荷） */
    fun generateMessage(): ByteArray = myMsg.copyOf()

    /**
     * 处理对端 32 字节消息，派生 64 字节共享密钥。
     * 密码错误会导致双方 transcript/dh 不一致，后续 AES-GCM 解密失败 —— 这本身就是校验手段。
     */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        require(theirMsg.size == 32) { "spake2 msg must be 32 bytes" }
        val qStar = Edwards25519.decodePoint(theirMsg)

        // unmask：client 减 s*N，server 减 s*M
        val peersMaskBase = if (isClient) POINT_N else POINT_M
        val peersMask = Edwards25519.scalarMul(passwordScalar, peersMaskBase)
        val q = Edwards25519.add(qStar, Edwards25519.negate(peersMask))

        val dhShared = Edwards25519.encode(Edwards25519.scalarMul(privateKey, q))

        val md = MessageDigest.getInstance("SHA-512")
        val clientName = CLIENT_NAME
        val serverName = SERVER_NAME
        val clientMsg: ByteArray
        val serverMsg: ByteArray
        if (isClient) {
            clientMsg = myMsg
            serverMsg = theirMsg
        } else {
            clientMsg = theirMsg
            serverMsg = myMsg
        }
        updateWithLengthPrefix(md, clientName)
        updateWithLengthPrefix(md, serverName)
        updateWithLengthPrefix(md, clientMsg)
        updateWithLengthPrefix(md, serverMsg)
        updateWithLengthPrefix(md, dhShared)
        updateWithLengthPrefix(md, passwordHash)
        return md.digest() // 64 字节
    }

    companion object {
        // pairing_auth.cpp: kClientName[] = "adb pair client"（sizeof 含 NUL = 16）
        //                   kServerName[] = "adb pair server"（sizeof 含 NUL = 16）
        val CLIENT_NAME: ByteArray = "adb pair client".toByteArray(Charsets.US_ASCII) + 0
        val SERVER_NAME: ByteArray = "adb pair server".toByteArray(Charsets.US_ASCII) + 0

        // spake25519.cc 生成的 M/N 点（Edwards 编码，32 字节小端）
        private const val M_HEX = "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"
        private const val N_HEX = "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"

        val POINT_M: Edwards25519.Point = Edwards25519.decodePoint(hexToBytes(M_HEX))
        val POINT_N: Edwards25519.Point = Edwards25519.decodePoint(hexToBytes(N_HEX))

        private fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(hex[i * 2], 16) shl 4)
                        or Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        private fun updateWithLengthPrefix(md: MessageDigest, data: ByteArray) {
            var len = data.size.toLong()
            val prefix = ByteArray(8)
            for (i in 0 until 8) {
                prefix[i] = (len and 0xffL).toByte()
                len = len ushr 8
            }
            md.update(prefix)
            md.update(data)
        }
    }
}
