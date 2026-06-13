package com.openvpn.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.Instant

class SubscriptionFeedStore(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    data class Entry(
        val feedUrl: String,
        val expiresAt: String,
    )

    fun read(): Entry? {
        val url = prefs.getString(KEY_FEED_URL, null)?.trim().orEmpty()
        val exp = prefs.getString(KEY_EXPIRES_AT, null)?.trim().orEmpty()
        if (url.isEmpty() || exp.isEmpty()) return null
        return Entry(feedUrl = url, expiresAt = exp)
    }

    fun save(feedUrl: String, expiresAt: String) {
        val url = feedUrl.trim()
        val exp = expiresAt.trim()
        if (url.isEmpty() || exp.isEmpty()) return
        prefs.edit()
            .putString(KEY_FEED_URL, url)
            .putString(KEY_EXPIRES_AT, exp)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_FEED_URL)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    fun isExpired(expiresAt: String): Boolean {
        val exp = expiresAt.trim()
        if (exp.isEmpty()) return true
        val epoch = runCatching { Instant.parse(exp).toEpochMilli() }.getOrNull() ?: return true
        return epoch <= System.currentTimeMillis()
    }

    fun isCachedEntryValid(entry: Entry): Boolean {
        return entry.feedUrl.isNotBlank() && !isExpired(entry.expiresAt)
    }

    companion object {
        private const val PREFS_NAME = "openvpn.android.subscription_feed.encrypted"
        private const val KEY_FEED_URL = "feed_url"
        private const val KEY_EXPIRES_AT = "expires_at"

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
    }
}
