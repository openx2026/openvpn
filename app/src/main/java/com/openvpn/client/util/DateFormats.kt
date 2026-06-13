package com.openvpn.client.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateFormats {
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.CHINA)

    fun formatLocal(iso: String): String {
        return runCatching {
            val instant = Instant.parse(iso)
            formatter.format(instant.atZone(ZoneId.systemDefault()))
        }.getOrDefault(iso)
    }
}
