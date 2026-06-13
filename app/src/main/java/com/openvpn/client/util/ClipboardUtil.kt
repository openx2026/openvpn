package com.openvpn.client.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardUtil {
    fun copy(context: Context, label: String, text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        return true
    }
}
