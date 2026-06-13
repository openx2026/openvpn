package com.openvpn.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.Instant

/**
 * Caches raw HTTP body from GET /subscription/<token> (node config text).
 * Keyed by normalized feed URL; invalidated when URL changes or TTL elapses.
 */
class SubscriptionBodyCache(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    data class Entry(
        val feedUrl: String,
        val body: String,
        val expiresAtMs: Long,
    )

    fun read(feedUrl: String): Entry? {
        val url = feedUrl.trim()
        if (url.isEmpty()) return null
        val storedUrl = prefs.getString(KEY_FEED_URL, null)?.trim().orEmpty()
        if (storedUrl != url) return null
        val body = prefs.getString(KEY_BODY, null).orEmpty()
        val expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (body.isEmpty() || expiresAtMs <= 0L) return null
        return Entry(feedUrl = storedUrl, body = body, expiresAtMs = expiresAtMs)
    }

    fun save(feedUrl: String, body: String, expiresAtMs: Long) {
        val url = feedUrl.trim()
        val content = body.trim()
        if (url.isEmpty() || content.isEmpty() || expiresAtMs <= System.currentTimeMillis()) return
        prefs.edit()
            .putString(KEY_FEED_URL, url)
            .putString(KEY_BODY, content)
            .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_FEED_URL)
            .remove(KEY_BODY)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
    }

    fun isValid(entry: Entry): Boolean = entry.expiresAtMs > System.currentTimeMillis()

    /** Defaults to [DEFAULT_CONTENT_TTL_MS], capped by subscription link expiresAt. */
    fun computeExpiresAtMs(feedExpiresAtIso: String?): Long {
        val now = System.currentTimeMillis()
        val ttlEnd = now + DEFAULT_CONTENT_TTL_MS
        val linkEnd = feedExpiresAtIso?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return if (linkEnd != null) minOf(ttlEnd, linkEnd) else ttlEnd
    }

    companion object {
        /** 4h: panel sync is infrequent; still capped by feed link expiry. */
        const val DEFAULT_CONTENT_TTL_MS = 4 * 60 * 60 * 1000L

        private const val PREFS_NAME = "openvpn.android.subscription_body.encrypted"
        private const val KEY_FEED_URL = "feed_url"
        private const val KEY_BODY = "body"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"

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
