package com.openvpn.client.util

import java.time.Instant

object OrderPayCountdown {
    fun format(expiredAtIso: String, nowMs: Long = System.currentTimeMillis()): String {
        val end = runCatching { Instant.parse(expiredAtIso.trim()).toEpochMilli() }.getOrNull()
        if (end == null) return "—"
        val ms = end - nowMs
        if (ms <= 0) return "支付已截止"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            "剩余 ${h}:${two(m)}:${two(s)}"
        } else {
            "剩余 ${m}:${two(s)}"
        }
    }

    fun isExpired(expiredAtIso: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val end = runCatching { Instant.parse(expiredAtIso.trim()).toEpochMilli() }.getOrNull()
        return end != null && nowMs >= end
    }

    private fun two(n: Long): String = if (n < 10) "0$n" else n.toString()
}
