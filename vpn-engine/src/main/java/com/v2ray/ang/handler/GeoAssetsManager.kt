package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * Ensures v2ray geo routing databases exist before starting the core.
 */
object GeoAssetsManager {
    private val requiredFiles = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT)

    fun missingFiles(context: Context): List<String> {
        val dir = Utils.userAssetPath(context)
        if (dir.isBlank()) return requiredFiles
        return requiredFiles.filter { !File(dir, it).isFile }
    }

    fun ensureReady(context: Context): Result<Unit> {
        SettingsManager.initAssets(context, context.assets)
        val assetDir = Utils.userAssetPath(context)
        if (assetDir.isBlank()) {
            return Result.failure(IllegalStateException("无法创建路由数据目录"))
        }

        val missing = missingFiles(context)
        if (missing.isEmpty()) return Result.success(Unit)

        val source = AppConfig.GEO_FILES_SOURCES.first()
        val baseUrl = String.format(AppConfig.GITHUB_DOWNLOAD_URL, source)
        for (name in missing) {
            val target = File(assetDir, name)
            target.parentFile?.mkdirs()
            val url = "$baseUrl/$name"
            LogUtil.i(AppConfig.TAG, "Downloading geo asset: $url")
            val ok = HttpUtil.downloadToFile(
                UrlContentRequest(url = url, timeout = 60_000),
                target,
            )
            if (!ok || !target.isFile || target.length() <= 0L) {
                return Result.failure(
                    IllegalStateException("缺少路由数据 $name，从 GitHub 下载失败，请检查网络后重试"),
                )
            }
            LogUtil.i(AppConfig.TAG, "Geo asset ready: ${target.absolutePath}")
        }
        return Result.success(Unit)
    }
}
