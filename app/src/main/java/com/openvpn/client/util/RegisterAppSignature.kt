package com.openvpn.client.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RegisterAppSignature {
    /**
     * HMAC-SHA256，与后端 `register-app-signature.util.ts` 一致。
     * payload = `{timestampSec}|{username}|{deviceId}`（username、deviceId 小写 trim）
     */
    fun sign(
        secret: String,
        timestampSec: Long,
        username: String,
        deviceId: String,
    ): String {
        val payload = buildPayload(timestampSec, username, deviceId)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildPayload(timestampSec: Long, username: String, deviceId: String): String {
        val u = username.trim().lowercase()
        val d = deviceId.trim().lowercase()
        return "$timestampSec|$u|$d"
    }
}
