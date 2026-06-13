package com.openvpn.client.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Labels {
    private val usdtPrice = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }

    val PLAN_TIER_LABEL = mapOf(
        "BASIC" to "基础",
        "ADVANCED" to "进阶",
        "PRO" to "专业",
    )

    val PLAN_TYPE_LABEL = mapOf(
        "ONE_MONTH" to "1 个月",
        "ONE_QUARTER" to "1 季度",
        "ONE_YEAR" to "1 年",
        "TWO_YEARS" to "2 年",
        "THREE_YEARS" to "3 年",
    )

    private val CHAIN_HINT = mapOf(
        1L to "Ethereum",
        56L to "BSC",
        97L to "BSC 测试网",
        728126428L to "TRON",
    )

    fun planTierLabel(tier: String): String = PLAN_TIER_LABEL[tier] ?: tier

    fun planTypeLabel(type: String): String = PLAN_TYPE_LABEL[type] ?: type

    /** 套餐月价 USDT（最多两位小数，如 1.2、1.25；整数不显示 .0） */
    fun formatUsdtPrice(amount: Double): String {
        if (!amount.isFinite()) return "—"
        val rounded = kotlin.math.round(amount * 100.0) / 100.0
        return usdtPrice.format(rounded)
    }

    fun formatUsdtPerMonth(amount: Double): String = "${formatUsdtPrice(amount)} USDT / 月"

    fun chainHint(chainId: Long): String = CHAIN_HINT[chainId] ?: "链"

    fun statusLabel(status: String): String = when (status) {
        "PENDING" -> "待支付"
        "PAID" -> "已支付"
        "EXPIRED" -> "已过期"
        else -> status
    }

    fun avatarLetters(username: String): String {
        val u = username.trim()
        if (u.isEmpty()) return "?"
        if (u.startsWith("tgz") && u.length > 3) return u.substring(3, 5).uppercase()
        return u.take(2).uppercase()
    }

    fun mineSubscriptionPill(membership: com.openvpn.client.api.UserMembershipInfo?): Pair<String, Boolean> {
        if (membership == null) return "未开通" to false
        if (membership.status == "INACTIVE") return "无效" to false
        val exp = membership.expiredAt?.trim().orEmpty()
        if (exp.isNotEmpty()) {
            val t = runCatching { java.time.Instant.parse(exp).toEpochMilli() }.getOrNull()
            if (t != null && t <= System.currentTimeMillis()) return "已过期" to false
        }
        return "已开通" to true
    }

    fun formatMinePageMembershipExpiry(m: com.openvpn.client.api.UserMembershipInfo): String {
        if (m.status == "INACTIVE") return "—"
        val exp = m.expiredAt?.trim().orEmpty()
        if (exp.isEmpty()) return "无限期"
        return DateFormats.formatLocal(exp)
    }

    fun mineSubscriptionRemainingLabel(m: com.openvpn.client.api.UserMembershipInfo?): String {
        if (m == null || m.status == "INACTIVE") return "—"
        return m.remainingDays?.let { "约 $it 天" } ?: "无限期"
    }

    fun isMembershipActive(m: com.openvpn.client.api.UserMembershipInfo): Boolean {
        if (m.status != "ACTIVE") return false
        val exp = m.expiredAt?.trim().orEmpty()
        if (exp.isEmpty()) return true
        val t = runCatching { java.time.Instant.parse(exp).toEpochMilli() }.getOrNull() ?: return false
        return t > System.currentTimeMillis()
    }

    private fun isLocalDevHost(host: String): Boolean {
        val h = host.lowercase().substringBefore(':')
        if (h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2") return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("10.")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts[0] == "172") {
            val second = parts[1].toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    fun normalizeSubscriptionFeedUrl(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty()) return null
        return try {
            val uri = java.net.URI(u)
            val host = uri.host?.lowercase().orEmpty()
            val isLocal = isLocalDevHost(host)
            when {
                isLocal && uri.scheme == "https" -> java.net.URI(
                    "http",
                    uri.userInfo,
                    uri.host,
                    uri.port,
                    uri.path,
                    uri.query,
                    uri.fragment,
                ).toString()
                uri.scheme == "http" && !isLocal -> java.net.URI(
                    "https",
                    uri.userInfo,
                    uri.host,
                    uri.port,
                    uri.path,
                    uri.query,
                    uri.fragment,
                ).toString()
                else -> u
            }
        } catch (_: Exception) {
            u
        }
    }
}
