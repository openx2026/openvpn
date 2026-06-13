package com.openvpn.client.bridge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.openvpn.client.util.Labels
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.SubscriptionCache
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

    fun prepareSubscription(context: Context, feedUrl: String): Result<Unit> {
        return runCatching {
            val appContext = context.applicationContext
            val url = normalizeFeedUrl(feedUrl)
                ?: error("订阅链接无效")
            val existing = findSubscriptionByUrl(url)
            if (existing == null) {
                importSubscription(url)
            } else {
                val storedUrl = normalizeFeedUrl(existing.subscription.url)
                if (storedUrl != null && storedUrl != url) {
                    existing.subscription.url = url
                    MmkvManager.encodeSubscription(existing.guid, existing.subscription)
                }
                val result = AngConfigManager.updateConfigViaSub(existing)
                if (result.successCount == 0 && result.failureCount > 0) {
                    MmkvManager.removeSubscription(existing.guid)
                    importSubscription(url)
                }
            }
            val subGuid = findSubscriptionByUrl(url)?.guid
            if (!ensureServerSelected(appContext, subGuid)) {
                val subscriptionId = subGuid
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

    private fun importSubscription(url: String) {
        val (_, countSub) = AngConfigManager.importBatchConfig(url, "", false)
        if (countSub <= 0 && findSubscriptionByUrl(url) == null) {
            error("订阅导入失败，请检查网络或订阅链接")
        }
        val sub = findSubscriptionByUrl(url)
        if (sub != null) {
            val result = AngConfigManager.updateConfigViaSub(sub)
            if (result.successCount == 0 && result.failureCount > 0) {
                error("订阅更新失败，无法从服务器拉取节点")
            }
        }
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
