package com.starcaretech.a.adb

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Curve25519（Edwards 形式，RFC 8032）的纯 Kotlin 点运算实现，仅供 ADB 无线配对的
 * SPAKE2 协议使用（对齐 BoringSSL spake25519.cc）。
 *
 * 使用 BigInteger + 扩展齐次坐标 (X:Y:Z:T)，统一加法公式（a=-1, k=2d）对任意两点
 * （含 doubling 与 identity）均成立。
 */
internal object Edwards25519 {

    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger =
        BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))

    /** d = -121665/121666 mod p */
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** 统一加法公式使用的 k = 2d */
    private val D2: BigInteger = D.shiftLeft(1).mod(P)

    /** sqrt(-1) mod p = 2^((p-1)/4) */
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(
        P.subtract(BigInteger.ONE).shiftRight(2), P
    )

    /** Ed25519 标准基点 */
    val BASE_POINT: Point = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    )

    /** 单位点（中性元） */
    val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE)

    /** 扩展坐标点 (X:Y:Z:T)，affine x = X/Z, y = Y/Z，恒有 T = XY/Z */
    class Point internal constructor(
        val x: BigInteger, val y: BigInteger,
        internal val z: BigInteger, internal val t: BigInteger
    ) {
        internal constructor(x: BigInteger, y: BigInteger) : this(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun modMul(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(P)
    private fun modSub(a: BigInteger, b: BigInteger): BigInteger = a.subtract(b).mod(P)
    private fun modAdd(a: BigInteger, b: BigInteger): BigInteger = a.add(b).mod(P)

    /** 统一加法公式（add-2008-hwcd-3，a=-1, k=2d），对任意输入成立 */
    fun add(p1: Point, p2: Point): Point {
        val a = modMul(p1.y.subtract(p1.x).mod(P), p2.y.subtract(p2.x).mod(P))
        val b = modMul(p1.y.add(p1.x).mod(P), p2.y.add(p2.x).mod(P))
        val c = modMul(p1.t, modMul(D2, p2.t))
        val d = modMul(p1.z, p2.z).shiftLeft(1).mod(P)
        val e = modSub(b, a)
        val f = modSub(d, c)
        val g = modAdd(d, c)
        val h = modAdd(b, a)
        return Point(modMul(e, f), modMul(g, h), modMul(f, g), modMul(e, h))
    }

    fun double(p: Point): Point = add(p, p)

    fun negate(p: Point): Point = Point(p.x.negate().mod(P), p.y, p.z, p.t.negate().mod(P))

    /** 从高位到低位的 double-and-add 标量乘 */
    fun scalarMul(k: BigInteger, p: Point): Point {
        require(k.signum() >= 0)
        if (k.signum() == 0) return IDENTITY
        var result = IDENTITY
        var base = p
        var i = k.bitLength() - 1
        while (i >= 0) {
            result = double(result)
            if (k.testBit(i)) result = add(result, base)
            i--
        }
        return result
    }

    /**
     * RFC 8032 §5.1.3 点解码：32 字节小端 y，最高位为 x 的符号位。
     * 非法点抛出 IllegalArgumentException。
     */
    fun decodePoint(encoded: ByteArray): Point {
        require(encoded.size == 32) { "point must be 32 bytes" }
        val copy = encoded.copyOf()
        val xSign = (copy[31].toInt() and 0x80) != 0
        copy[31] = (copy[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, reverse(copy))
        require(y < P) { "y out of field" }

        // x^2 = (y^2 - 1) / (d*y^2 + 1) mod p
        val y2 = modMul(y, y)
        val u = modSub(y2, BigInteger.ONE)
        val v = modAdd(modMul(D, y2), BigInteger.ONE)
        var x = candidateRoot(u, v)

        // RFC 8032 第二分支：u*v^7 非二次剩余时，候选根满足 v*x^2 = -u，需乘 sqrt(-1)
        val check = v.multiply(x).multiply(x).mod(P)
        if (check != u && check == u.negate().mod(P)) {
            x = modMul(x, SQRT_M1)
        }
        require(v.multiply(x).multiply(x).mod(P) == u) { "point not on curve" }
        require(!(x.signum() == 0 && xSign)) { "invalid sign for identity x" }
        if (x.testBit(0) != xSign) x = P.subtract(x)
        return Point(x, y)
    }

    /** RFC 8032 平方根候选：x = u*v^3 * (u*v^7)^((p-5)/8) */
    private fun candidateRoot(u: BigInteger, v: BigInteger): BigInteger {
        val v3 = modMul(v, modMul(v, v))
        val v7 = modMul(v3, modMul(v3, v))
        val exp = P.subtract(BigInteger.valueOf(5)).shiftRight(3)
        return modMul(modMul(u, v3), modMul(u, v7).modPow(exp, P))
    }

    /** 编码为 32 字节：y 小端，最高位 = x 的最低位 */
    fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = toBytesLe(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** BigInteger -> 32 字节小端 */
    fun toBytesLe(v: BigInteger): ByteArray {
        require(v.signum() >= 0)
        val be = v.toByteArray()
        // 非负 BigInteger 首字节可能是符号填充 0
        var start = 0
        while (start < be.size - 1 && be[start] == 0.toByte()) start++
        val mag = be.copyOfRange(start, be.size)
        val out = ByteArray(32)
        var idx = 0
        for (i in mag.size - 1 downTo 0) {
            if (idx >= 32) break
            out[idx++] = mag[i]
        }
        return out
    }

    /** 32 字节小端 -> 非负 BigInteger */
    fun fromBytesLe(bytes: ByteArray): BigInteger {
        val be = reverse(bytes)
        return BigInteger(1, be)
    }

    private fun reverse(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) out[i] = src[src.size - 1 - i]
        return out
    }

    /** 均匀随机标量：64 随机字节 mod L 再乘 8（清 cofactor），对齐 spake25519 GenerateKey */
    fun randomEphemeralScalar(random: SecureRandom): BigInteger {
        val buf = ByteArray(64)
        random.nextBytes(buf)
        val reduced = fromBytesLe(buf).mod(L)
        return reduced.shiftLeft(3).mod(L)
    }
}
