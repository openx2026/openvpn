package com.openvpn.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createEncryptedPrefs(appContext)

    init {
        migrateLegacyTokenIfNeeded(appContext)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            }.apply()
        }

    fun clear() {
        token = null
    }

    companion object {
        const val KEY_TOKEN = "openvpn.android.token"
        private const val PREFS_NAME = "openvpn.android.session.encrypted"
        private const val LEGACY_PREFS_NAME = "openvpn.android.session"

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun migrateLegacyTokenIfNeeded(context: Context) {
            val encrypted = createEncryptedPrefs(context)
            if (!encrypted.getString(KEY_TOKEN, null).isNullOrBlank()) return

            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val legacyToken = legacy.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return

            encrypted.edit().putString(KEY_TOKEN, legacyToken).apply()
            legacy.edit().remove(KEY_TOKEN).apply()
        }
    }
}
