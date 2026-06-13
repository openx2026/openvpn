package com.openvpn.client.bridge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.openvpn.client.data.SubscriptionBodyCache
import com.openvpn.client.util.Labels
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.GeoAssetsManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MessageUtil
import java.io.Serializable

/**
 * Thin facade so app UI does not import engine internals directly.
 */
object VpnEngineBridge {
    private const val PORTAL_SUBSCRIPTION_REMARKS = "OpenVPN"
    /**
     * CoreVpnService runs in :RunSoLibV2RayDaemon, so [CoreServiceManager.isRunning] in the
     * main process is usually false even when VPN is connected. Fall back to service state.
     */
    fun isRunning(context: Context): Boolean {
        if (CoreServiceManager.isRunning()) return true
        return isSystemVpnTransportActive(context)
    }

    /** 主进程内 [CoreServiceManager] 可能为 false（服务跑在 :RunSoLibV2RayDaemon），用系统 VPN 传输层兜底。 */
    private fun isSystemVpnTransportActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun ensureGeoAssets(context: Context): Result<Unit> = GeoAssetsManager.ensureReady(context)

    fun vpnPrepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun findSubscriptionByUrl(url: String): SubscriptionCache? {
        val normalized = normalizeFeedUrl(url) ?: return null
        return MmkvManager.decodeSubscriptions().firstOrNull {
            normalizeFeedUrl(it.subscription.url) == normalized
        }
    }

    fun hasSubscriptionImported(url: String): Boolean = findSubscriptionByUrl(url) != null

    fun prepareSubscription(
        context: Context,
        feedUrl: String,
        feedExpiresAt: String? = null,
    ): Result<Unit> {
        return runCatching {
            val appContext = context.applicationContext
            val url = normalizeFeedUrl(feedUrl)
                ?: error("订阅链接无效")
            val sub = upsertPortalSubscription(url)
            val result = updatePortalSubscriptionConfig(appContext, sub, url, feedExpiresAt)
            if (result.successCount == 0 && result.failureCount > 0) {
                MmkvManager.removeSubscription(sub.guid)
                SubscriptionBodyCache(appContext).clear()
                val retry = upsertPortalSubscription(url)
                val retryResult = updatePortalSubscriptionConfig(appContext, retry, url, feedExpiresAt)
                if (retryResult.successCount == 0 && retryResult.failureCount > 0) {
                    error("订阅更新失败，无法从服务器拉取节点")
                }
            }
            val subGuid = findSubscriptionByUrl(url)?.guid ?: sub.guid
            if (!ensureServerSelected(appContext, subGuid)) {
                val subscriptionId = subGuid.takeIf { it.isNotEmpty() }
                    ?: MmkvManager.decodeSubscriptions().firstOrNull()?.guid
                    ?: ""
                val hasConfiguredNodes = subscriptionId.isNotEmpty() &&
                    MmkvManager.decodeServerList(subscriptionId).any {
                        MmkvManager.decodeServerConfig(it) != null
                    }
                error(
                    if (hasConfiguredNodes) {
                        "订阅中没有可用节点，请稍后重试或联系管理员"
                    } else {
                        "订阅中暂无可用节点，请确认面板已同步节点"
                    },
                )
            }
        }
    }

    /**
     * Portal 订阅链 token 每次签发都会变；按路径识别同一条订阅并更新 URL，
     * 避免 MMKV 里堆积多条旧链导致 updateConfigViaSubAll 重复拉取。
     */
    private fun upsertPortalSubscription(url: String): SubscriptionCache {
        val existing = findPortalSubscription()
        if (existing != null) {
            existing.subscription.url = url
            existing.subscription.remarks = PORTAL_SUBSCRIPTION_REMARKS
            existing.subscription.enabled = true
            existing.subscription.autoUpdate = false
            MmkvManager.encodeSubscription(existing.guid, existing.subscription)
            removeStalePortalSubscriptions(keepGuid = existing.guid)
            return existing
        }
        removeStalePortalSubscriptions()
        val item = SubscriptionItem(
            remarks = PORTAL_SUBSCRIPTION_REMARKS,
            url = url,
            enabled = true,
            autoUpdate = false,
        )
        MmkvManager.encodeSubscription("", item)
        return findSubscriptionByUrl(url) ?: findPortalSubscription()
            ?: error("订阅导入失败，请检查网络或订阅链接")
    }

    private fun findPortalSubscription(): SubscriptionCache? {
        return MmkvManager.decodeSubscriptions().firstOrNull { isPortalFeedUrl(it.subscription.url) }
    }

    private fun removeStalePortalSubscriptions(keepGuid: String? = null) {
        MmkvManager.decodeSubscriptions().forEach { sub ->
            if (isPortalFeedUrl(sub.subscription.url) && sub.guid != keepGuid) {
                MmkvManager.removeSubscription(sub.guid)
            }
        }
    }

    private fun isPortalFeedUrl(raw: String?): Boolean {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty()) return false
        return runCatching {
            val path = java.net.URI(u).path?.trim().orEmpty()
            path.contains("/subscription/")
        }.getOrDefault(false)
    }

    private fun updatePortalSubscriptionConfig(
        context: Context,
        sub: SubscriptionCache,
        url: String,
        feedExpiresAt: String?,
    ): SubscriptionUpdateResult {
        val bodyCache = SubscriptionBodyCache(context)
        val cached = bodyCache.read(url)
        if (cached != null && bodyCache.isValid(cached)) {
            val fromCache = AngConfigManager.applySubscriptionContent(sub, cached.body)
            if (fromCache.successCount > 0) {
                return fromCache
            }
        }
        val fetched = AngConfigManager.fetchSubscriptionContent(sub)
        if (fetched.isEmpty()) {
            return SubscriptionUpdateResult(failureCount = 1)
        }
        bodyCache.save(url, fetched, bodyCache.computeExpiresAtMs(feedExpiresAt))
        return AngConfigManager.applySubscriptionContent(sub, fetched)
    }

    private fun normalizeFeedUrl(raw: String): String? = Labels.normalizeSubscriptionFeedUrl(raw)

    /** 随机探测节点可用性，选中第一个可达的节点；全部不可达则返回 false。 */
    private fun ensureServerSelected(context: Context, subGuid: String?): Boolean {
        val subscriptionId = subGuid ?: MmkvManager.decodeSubscriptions().firstOrNull()?.guid ?: return false
        val servers = MmkvManager.decodeServerList(subscriptionId)
            .filter { MmkvManager.decodeServerConfig(it) != null }
        if (servers.isEmpty()) {
            MmkvManager.setSelectServer("")
            return false
        }

        MmkvManager.setSelectServer("")
        for (serverGuid in servers.shuffled()) {
            val delayMs = measureServerDelayMillis(context, serverGuid)
            if (delayMs >= 0L) {
                MmkvManager.setSelectServer(serverGuid)
                MmkvManager.encodeServerTestDelayMillis(serverGuid, delayMs)
                return true
            }
        }
        return false
    }

    /** 连接前真实延迟探测（与引擎 RealPing 一致）；失败返回 -1。 */
    private fun measureServerDelayMillis(context: Context, serverGuid: String): Long {
        CoreNativeManager.initCoreEnv(context.applicationContext)
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context.applicationContext, serverGuid)
        if (!configResult.status) {
            return -1L
        }
        var delay = CoreNativeManager.measureOutboundDelay(
            configResult.content,
            SettingsManager.getDelayTestUrl(),
        )
        if (delay < 0L) {
            delay = CoreNativeManager.measureOutboundDelay(
                configResult.content,
                SettingsManager.getDelayTestUrl(second = true),
            )
        }
        return delay
    }

    fun measureCurrentDelayMillis(context: Context): Long? {
        if (!isRunning(context)) return null
        val live = CoreServiceManager.measureCurrentDelay()
        if (live >= 0L) return live
        return cachedSelectedServerDelayMillis()
    }

    fun requestDelayMeasure(context: Context) {
        if (!isRunning(context)) return
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_MEASURE_DELAY, "")
    }

    fun parseDelayMeasureResult(content: Serializable?): Int? {
        val text = content?.toString().orEmpty()
        if (text.isBlank()) return null
        val match = DELAY_RESULT_REGEX.find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }

    private val DELAY_RESULT_REGEX = Regex("""(\d+)\s*(?:ms|毫秒)""", RegexOption.IGNORE_CASE)

    fun cachedSelectedServerDelayMillis(): Long? {
        val guid = MmkvManager.getSelectServer() ?: return null
        val aff = MmkvManager.decodeServerAffiliationInfo(guid) ?: return null
        return aff.testDelayMillis.takeIf { it > 0L }
    }

    fun startVpn(context: Context) {
        CoreServiceManager.startVService(context)
    }

    fun stopVpn(context: Context) {
        CoreServiceManager.stopVService(context)
    }
}
