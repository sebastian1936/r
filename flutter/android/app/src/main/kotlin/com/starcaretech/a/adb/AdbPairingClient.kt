package com.starcaretech.a.adb

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ADB 无线配对客户端（对照 AOSP pairing_connection.cpp 实现）。
 *
 * 线上协议（全部小端/大端见注释）：
 *  - PairingPacketHeader（6 字节 packed）：u8 version=1, u8 type, u32 payload（大端）
 *  - type: SPAKE2_MSG=0, PEER_INFO=1
 *  - 顺序：双方先各自发 SPAKE2 消息；随后用 SPAKE2 密钥建立 AES-128-GCM，
 *    加密交换整个 8192 字节 PeerInfo（各自先发）
 *  - 密码 = 6 位配对码（ASCII）+ TLS1.3 exporter（label="adb-label\0", 64 字节）
 *  - AES-128-GCM：HKDF-SHA256(key64, info="adb pairing_auth aes-128-gcm key") 派生 16 字节密钥，
 *    nonce = 12 字节零 + u64 小端序号（从 0 开始，收发独立计数），tag 16 字节
 *  - 我方 PeerInfo: type=ADB_RSA_PUB_KEY(0)，data = 公钥串 + NUL（整段 8192 字节）
 *  - 设备回的 PeerInfo: type=ADB_DEVICE_GUID(1)，data = 设备 guid 字符串
 */
object AdbPairingClient {

    private const val HEADER_SIZE = 6
    private const val MAX_PAYLOAD = 8192 * 2 // pairing_connection.cpp: kMaxPayloadSize
    private const val PEER_INFO_SIZE = 8192  // kMaxPeerInfoSize
    private const val GCM_TAG_LEN = 16

    private const val TYPE_SPAKE2_MSG: Int = 0
    private const val TYPE_PEER_INFO: Int = 1

    private const val PEER_TYPE_RSA_PUB_KEY: Int = 0
    private const val PEER_TYPE_DEVICE_GUID: Int = 1

    private const val EXPORT_LABEL = "adb-label\u0000" // Conscrypt 内部 US_ASCII 编码 -> "adb-label\0"（10 字节）
    private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"

    class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 执行配对。成功返回设备 guid（从对端 PeerInfo 解出）。
     *
     * @param pairingCode 系统无线调试页面显示的 6 位配对码
     */
    fun pair(identity: AdbKeyStore.Identity, host: String, port: Int, pairingCode: String): String {
        require(pairingCode.length == 6) { "配对码必须为 6 位数字" }

        val socket = AdbKeyStore.createTlsSocket(identity, host, port, 10_000)
        try {
            val ekm = Conscrypt.exportKeyingMaterial(socket, EXPORT_LABEL, null, 64)
            val password = pairingCode.toByteArray(StandardCharsets.US_ASCII) + ekm
            val spake = Spake2Client(Spake2Client.Role.CLIENT, password)

            val out = DataOutputStream(socket.getOutputStream().buffered())
            val input = DataInputStream(socket.getInputStream().buffered())

            // ---- ExchangeMsgs：先发后收（对齐 DoExchangeMsgs）----
            val myMsg = spake.generateMessage()
            writeHeader(out, TYPE_SPAKE2_MSG, myMsg.size)
            out.write(myMsg)
            out.flush()

            readHeader(input, expectedType = TYPE_SPAKE2_MSG).let { payloadLen ->
                if (payloadLen != 32) throw PairingException("SPAKE2 消息长度异常: $payloadLen")
            }
            val theirMsg = ByteArray(32).also { input.readFully(it) }
            val keyMaterial = spake.processMessage(theirMsg)

            // ---- 建立 AES-128-GCM ----
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(keyMaterial, null, HKDF_INFO.toByteArray(StandardCharsets.US_ASCII)))
            val aesKey = ByteArray(16).also { hkdf.generateBytes(it, 0, 16) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            // ---- ExchangePeerInfo：先发后收（对齐 DoExchangePeerInfo）----
            val peerInfo = buildPeerInfo(identity.publicKeyString)
            val encrypted = aesGcm(cipher, Cipher.ENCRYPT_MODE, aesKey, peerInfo, seq = 0)
            writeHeader(out, TYPE_PEER_INFO, encrypted.size)
            out.write(encrypted)
            out.flush()

            val respLen = readHeader(input, expectedType = TYPE_PEER_INFO)
            if (respLen < GCM_TAG_LEN || respLen - GCM_TAG_LEN != PEER_INFO_SIZE) {
                throw PairingException("对端 PeerInfo 长度异常: $respLen")
            }
            val respCipher = ByteArray(respLen).also { input.readFully(it) }
            val respPlain = aesGcm(cipher, Cipher.DECRYPT_MODE, aesKey, respCipher, seq = 0)
            if (respPlain.size != PEER_INFO_SIZE) throw PairingException("PeerInfo 解密结果异常")

            val respType = respPlain[0].toInt() and 0xff
            if (respType != PEER_TYPE_DEVICE_GUID) {
                throw PairingException("对端 PeerInfo 类型异常: $respType")
            }
            // data 为 NUL 结尾的 guid 字符串
            val guidBytes = ByteArrayOutputStream()
            for (i in 1 until respPlain.size) {
                if (respPlain[i] == 0.toByte()) break
                guidBytes.write(respPlain[i].toInt())
            }
            return guidBytes.toString(StandardCharsets.US_ASCII.name())
        } finally {
            runCatching { socket.close() }
        }
    }

    /** PeerInfo = u8 type + data[8191]，data 放 NUL 结尾公钥串，剩余补零 */
    private fun buildPeerInfo(publicKeyString: String): ByteArray {
        val buf = ByteArray(PEER_INFO_SIZE)
        buf[0] = PEER_TYPE_RSA_PUB_KEY.toByte()
        val keyBytes = publicKeyString.toByteArray(StandardCharsets.US_ASCII)
        if (keyBytes.size > PEER_INFO_SIZE - 2) {
            throw PairingException("公钥串过长: ${keyBytes.size}")
        }
        System.arraycopy(keyBytes, 0, buf, 1, keyBytes.size)
        // buf 默认全零：末字节天然 NUL
        return buf
    }

    private fun writeHeader(out: DataOutputStream, type: Int, payload: Int) {
        val header = ByteArray(HEADER_SIZE)
        header[0] = 1 // version
        header[1] = type.toByte()
        header[2] = (payload ushr 24).toByte()
        header[3] = (payload ushr 16).toByte()
        header[4] = (payload ushr 8).toByte()
        header[5] = payload.toByte()
        out.write(header)
    }

    /** 读取并校验 header，返回 payload 长度 */
    private fun readHeader(input: DataInputStream, expectedType: Int): Int {
        val header = ByteArray(HEADER_SIZE).also { input.readFully(it) }
        val version = header[0].toInt() and 0xff
        val type = header[1].toInt() and 0xff
        // 大端 u32（运算符不能放在行首，否则 Kotlin 会把上一行当成完整语句）
        var payload = header[2].toInt() and 0xff
        payload = (payload shl 8) or (header[3].toInt() and 0xff)
        payload = (payload shl 8) or (header[4].toInt() and 0xff)
        payload = (payload shl 8) or (header[5].toInt() and 0xff)
        if (version != 1) throw PairingException("配对协议版本不匹配: $version")
        if (type != expectedType) throw PairingException("配对包类型不匹配: 期望 $expectedType 实际 $type")
        if (payload <= 0 || payload > MAX_PAYLOAD) throw PairingException("配对包长度异常: $payload")
        return payload
    }

    /** AES-128-GCM，nonce = 12 字节零 + u64 小端序号（对齐 aes_128_gcm.cpp） */
    private fun aesGcm(cipher: Cipher, mode: Int, key: ByteArray, data: ByteArray, seq: Long): ByteArray {
        val nonce = ByteArray(12)
        var s = seq
        for (i in 0 until 8) {
            nonce[i] = (s and 0xffL).toByte()
            s = s ushr 8
        }
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
        return cipher.doFinal(data)
    }
}
