package com.starcaretech.a

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Base64
import android.util.Log

/**
 * MediaProjection 授权结果 Intent 的持久化（best-effort 静默续期）。
 *
 * 背景：普通 App 每次新建 MediaProjection 会话都必须经过系统的用户确认弹窗，
 * 没有任何安全权限/AppOps 可以绕过。这里把用户确认后拿到的结果 Intent
 * marshall 成 Base64 存进 SharedPreferences，在 Service 重建/进程重启后尝试
 * 直接 [android.media.projection.MediaProjectionManager.getMediaProjection]，
 * 省掉重复弹窗。
 *
 * 可行性边界（务必知悉）：
 * - 结果 Intent 里带的是指向 system_server 会话的 Binder token。Parcel 原始
 *   marshall 跨进程恢复 Binder 在多数版本/ROM 上会失败；同一开机周期内部分
 *   Android 11~13 设备可成功。因此调用方必须 try/catch，失败就回退到弹窗。
 * - 重启手机后 system_server 已重建，旧 token 必然失效——此时只能弹一次窗，
 *   这对任何无 root 方案都成立。
 * - targetSdk 必须保持 33：Android 14（targetSdk 34）起 token 强制一次性，
 *   本机制彻底无效。
 */
object MediaProjectionTokenStore {

    private const val TAG = "MPTokenStore"
    private const val PREFS = "media_projection_token"
    private const val KEY_TOKEN = "result_intent_b64"
    private const val KEY_SAVED_AT = "saved_at"

    fun save(context: Context, resultIntent: Intent) {
        try {
            val parcel = Parcel.obtain()
            try {
                resultIntent.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TOKEN, b64)
                    .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "授权 Intent 已缓存 (${bytes.size} bytes)")
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "缓存授权 Intent 失败", e)
        }
    }

    /**
     * 读取并反序列化缓存的授权 Intent。
     * 数据损坏/反序列化失败（含 Binder 不可恢复的 ROM）时返回 null，调用方应回退到弹窗。
     */
    fun load(context: Context): Intent? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b64 = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                Intent.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "反序列化授权 Intent 失败，清除缓存", e)
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
