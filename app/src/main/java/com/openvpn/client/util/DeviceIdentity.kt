package com.openvpn.client.util

import android.content.Context
import android.provider.Settings

/** Android 注册用设备标识：`Settings.Secure.ANDROID_ID` */
object DeviceIdentity {
    private const val INVALID_ANDROID_ID = "9774d56d682e549c"
    private val ANDROID_ID_RE = Regex("^[0-9a-fA-F]{16}$")

    /** 注册用设备标识：规范化后的 ANDROID_ID（16 位小写 hex） */
    fun registrationDeviceId(context: Context): String? {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals(INVALID_ANDROID_ID, ignoreCase = true)) {
            return null
        }
        if (!raw.matches(ANDROID_ID_RE)) {
            return null
        }
        return raw.lowercase()
    }
}
