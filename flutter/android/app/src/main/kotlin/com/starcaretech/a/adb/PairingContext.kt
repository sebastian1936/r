// SPDX-License-Identifier: Apache-2.0
// 源自 Shizuku (https://github.com/RikkaApps/Shizuku)，
// 原文件 manager/src/main/java/.../adb/AdbPairingClient.kt 中的文件私有类。
// 提取为独立顶层类：libstaradb.so 的 JNI_OnLoad 用
// FindClass("com/starcaretech/a/adb/PairingContext") 注册 native 方法，
// 若作为嵌套类 JVM 名会变成 "AdbPairingClient$PairingContext" 导致注册失败。
package com.starcaretech.a.adb

/**
 * ADB 无线配对的加密上下文（native 实现见 src/main/jni/adb_pairing.cpp）：
 *  - SPAKE2 over Curve25519（BoringSSL spake25519）完成密钥协商
 *  - HKDF-SHA256 派生 16 字节 AES-128-GCM 密钥
 *  - 加解密 nonce = 12 字节 0 前缀 + u64 小端收发序号
 *
 * 使用顺序：[create] → [msg] 发给对端 → 收到对端消息后 [initCipher]
 * → [encrypt] / [decrypt] → 用完 [destroy]。
 */
class PairingContext private constructor(private val nativePtr: Long) {

    /** 我方 SPAKE2 公钥消息（32 字节），native 构造时已生成 */
    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    /** 处理对端 SPAKE2 消息并初始化 AES-GCM；失败返回 false */
    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray): ByteArray? = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray): ByteArray? = nativeDecrypt(nativePtr, `in`)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        init {
            // 产物名 libstaradb.so（见 jni/CMakeLists.txt）
            System.loadLibrary("staradb")
        }

        /**
         * @param isClient true=客户端角色（spake2_role_alice，连 adbd 配对服务时用）
         * @param password 6 位配对码 ASCII + TLS1.3 exporter 64 字节
         * @return native 上下文；SPAKE2 初始化失败时返回 null
         */
        @JvmStatic
        fun create(isClient: Boolean, password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(isClient, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}
