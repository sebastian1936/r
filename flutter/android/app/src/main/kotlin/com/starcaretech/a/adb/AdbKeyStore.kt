package com.starcaretech.a.adb

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

/**
 * ADB TLS 身份：RSA-2048 密钥对 + 自签证书 + Android 专用公钥串。
 *
 * 配对时把 [publicKeyString]（ANDROID_PUBKEY base64 格式）作为 PeerInfo 发给设备，
 * 设备保存为信任公钥；此后 TLS 连接（5555）出示同一张证书即可被 adbd 接受。
 */
object AdbKeyStore {

    private const val DIR = "adb_identity"
    private const val P12_FILE = "identity.p12"
    private const val P12_PASSWORD = "adb-local"

    class Identity(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val certPem: String,
        /** 形如 "base64 user@host"，即 Android adbkeys 格式（不含换行） */
        val publicKeyString: String
    )

    @Volatile
    private var cached: Identity? = null

    fun getOrCreate(context: Context): Identity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val identity = load(context) ?: create(context)
            cached = identity
            return identity
        }
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun load(context: Context): Identity? {
        val p12 = File(dir(context), P12_FILE)
        if (!p12.exists()) return null
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            p12.inputStream().use { ks.load(it, P12_PASSWORD.toCharArray()) }
            val alias = ks.aliases().nextElement()
            val cert = ks.getCertificate(alias) as X509Certificate
            val key = ks.getKey(alias, P12_PASSWORD.toCharArray()) as PrivateKey
            val keyPair = KeyPair(cert.publicKey, key)
            Identity(keyPair, cert, toPem(cert), buildPublicKeyString(keyPair))
        } catch (e: Exception) {
            null
        }
    }

    private fun create(context: Context): Identity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val cert = selfSign(keyPair)
        val identity = Identity(keyPair, cert, toPem(cert), buildPublicKeyString(keyPair))

        // 持久化为 PKCS12
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("adb", keyPair.private, P12_PASSWORD.toCharArray(), arrayOf(cert))
        val p12 = File(dir(context), P12_FILE)
        FileOutputStream(p12).use { ks.store(it, P12_PASSWORD.toCharArray()) }
        return identity
    }

    private fun selfSign(keyPair: KeyPair): X509Certificate {
        val subject = X500Name("CN=starcare-adb")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(63, SecureRandom()),
            Date(now - 24L * 3600 * 1000),
            Date(now + 30L * 365 * 24 * 3600 * 1000),
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    fun toPem(cert: X509Certificate): String {
        val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            var i = 0
            while (i < b64.length) {
                append(b64, i, minOf(i + 64, b64.length))
                append('\n')
                i += 64
            }
            append("-----END CERTIFICATE-----")
        }
    }

    /**
     * ANDROID_PUBKEY 编码（system/core/libcrypto_utils/android_pubkey.c）：
     * struct RSAPublicKey { u32 len; u32 n0inv; u8 n[256]; u8 rr[256]; u32 exponent; }（全小端）
     * 共 524 字节。
     */
    internal fun androidPubkeyEncode(keyPair: KeyPair): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent
        require(n.bitLength() == 2048)

        val words = 64
        val b32 = BigInteger.TWO.pow(32)
        // n0inv = -1 / n[0] mod 2^32
        val n0 = n.mod(b32)
        val inv = n0.modInverse(b32)
        val n0inv = b32.subtract(inv).mod(b32)
        // rr = (2^2048)^2 mod n
        val rr = BigInteger.TWO.pow(2048 * 2).mod(n)

        val out = ByteArray(3 * 4 + 2 * (words * 4))
        putU32Le(out, 0, words.toLong())
        putU32Le(out, 4, n0inv)
        copyLe(out, 8, n, 256)
        copyLe(out, 264, rr, 256)
        putU32Le(out, 520, e)
        return out
    }

    private fun putU32Le(dst: ByteArray, off: Int, v: BigInteger) {
        val be = v.toByteArray()
        var filled = 0
        var i = be.size - 1
        while (i >= 0 && filled < 4) {
            dst[off + filled] = be[i]
            filled++
            i--
        }
        while (filled < 4) {
            dst[off + filled] = 0
            filled++
        }
    }

    private fun putU32Le(dst: ByteArray, off: Int, v: Long) {
        putU32Le(dst, off, BigInteger.valueOf(v))
    }

    private fun copyLe(dst: ByteArray, off: Int, v: BigInteger, len: Int) {
        val be = v.toByteArray()
        var i = be.size - 1
        var idx = 0
        while (i >= 0 && idx < len) {
            dst[off + idx] = be[i]
            idx++
            i--
        }
        // 高位剩余字节保持 0（默认值）
    }

    private fun buildPublicKeyString(keyPair: KeyPair): String {
        val encoded = androidPubkeyEncode(keyPair)
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return "$b64 starcare@android"
    }

    /**
     * 创建与 adbd 的 TLS 1.3 连接。
     * - 强制 TLSv1.3（adbd 同样强制）
     * - 信任任意服务端证书（adbd 使用自签设备证书）
     * - 自定义 KeyManager：无视设备下发的 CA 列表，始终出示我们的证书
     *   （adbd 用授权公钥构造 CA 列表，平台 KeyManager 会因 issuer 不匹配拒绝出证，
     *    官方 adb 客户端同样是无条件出示指定证书）
     */
    fun createTlsSocket(identity: Identity, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider())
        sslContext.init(arrayOf<KeyManager>(FixedKeyManager(identity)), arrayOf<javax.net.ssl.TrustManager>(TrustAllManager()), null)
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.startHandshake()
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }

    private class FixedKeyManager(private val identity: Identity) : X509ExtendedKeyManager() {
        private val alias = "adb"

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)

        override fun getPrivateKey(alias: String?): PrivateKey = identity.keyPair.private

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
