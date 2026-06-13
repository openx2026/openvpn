package com.openvpn.client.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openvpn.client.BuildConfig
import com.openvpn.client.OpenVpnApplication
import com.openvpn.client.R
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private inline fun <reified T> Gson.fromTyped(json: String): T =
    fromJson(json, object : TypeToken<T>() {}.type)

class PortalApi(
    context: Context = OpenVpnApplication.instance.applicationContext,
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = createClient(appContext)

    private val baseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')

    suspend fun register(username: String, password: String, inviteCode: String? = null): AuthResponse =
        post("/auth/register", RegisterRequest(username, password, inviteCode?.trim()?.ifEmpty { null }))

    suspend fun login(username: String, password: String): AuthResponse =
        post("/auth/login", LoginRequest(username, password))

    suspend fun profile(token: String): UserProfile = get("/auth/profile", token)

    suspend fun changePassword(token: String, currentPassword: String, newPassword: String): OkResponse =
        post(
            "/auth/password/change",
            ChangePasswordRequest(currentPassword, newPassword),
            token,
        )

    suspend fun catalogPlans(): List<CatalogPlan> = get("/membership/catalog/plans")

    suspend fun catalogChains(): List<CatalogChain> = get("/membership/catalog/chains")

    suspend fun orders(token: String): List<UserOrder> = get("/membership/orders", token)

    suspend fun order(token: String, id: Long): UserOrder? {
        return try {
            get<UserOrder>("/membership/order/$id", token)
        } catch (e: ApiException) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun createOrder(token: String, planId: Long, chainId: Long): UserOrder =
        post("/membership/order", CreateOrderRequest(planId, chainId), token)

    suspend fun membership(token: String): UserMembershipSnapshot = get("/membership", token)

    suspend fun subscriptionFeedUrl(token: String): SubscriptionFeedResponse =
        get("/membership/subscription-feed-url", token)

    private suspend inline fun <reified T> get(path: String, token: String? = null): T =
        request("GET", path, body = null, token = token)

    private suspend inline fun <reified T> post(path: String, body: Any, token: String? = null): T {
        val json = gson.toJson(body)
        return request("POST", path, json, token)
    }

    private suspend inline fun <reified T> request(method: String, path: String, body: String?, token: String?): T {
        val url = "$baseUrl$path"
        val builder = Request.Builder().url(url)
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            else -> error("不支持的 HTTP 方法：$method")
        }
        val response = try {
            executeAsync(builder.build())
        } catch (e: IOException) {
            logRequestFailure(method, path, url, e)
            throw ApiException(0, appContext.getString(R.string.network_connection_failed))
        }
        response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val message = parseErrorMessage(raw, it.code)
                throw ApiException(it.code, message)
            }
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                return Unit as T
            }
            if (raw.isBlank()) {
                throw ApiException(0, appContext.getString(R.string.api_empty_response))
            }
            return gson.fromTyped(raw)
        }
    }

    private fun parseErrorMessage(raw: String, code: Int): String {
        if (raw.isBlank()) return httpStatusMessage(code)
        return try {
            val map = gson.fromJson(raw, Map::class.java)
            val msg = map["message"]
            when (msg) {
                is List<*> -> msg.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .joinToString("；") { localizeServerMessage(it, code) }
                    .ifBlank { httpStatusMessage(code) }
                is String -> localizeServerMessage(msg, code)
                else -> httpStatusMessage(code)
            }
        } catch (_: Exception) {
            httpStatusMessage(code)
        }
    }

    private fun localizeServerMessage(message: String, code: Int): String {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return httpStatusMessage(code)
        if (trimmed.any { it in '\u4e00'..'\u9fff' }) return trimmed

        val lower = trimmed.lowercase()
        return when {
            lower.contains("password") && (lower.contains("longer") || lower.contains("shorter") || lower.contains("length")) ->
                appContext.getString(R.string.api_validation_password)
            lower.contains("password") && lower.contains("empty") ->
                appContext.getString(R.string.api_validation_password_required)
            lower.contains("username") && lower.contains("empty") ->
                appContext.getString(R.string.api_validation_username)
            lower.contains("user not found") ->
                appContext.getString(R.string.api_user_not_found)
            lower == "unauthorized" || lower.contains("invalid credentials") ->
                appContext.getString(R.string.session_expired)
            else -> httpStatusMessage(code)
        }
    }

    private fun httpStatusMessage(code: Int): String = when (code) {
        400 -> R.string.api_http_400
        401 -> R.string.api_http_401
        403 -> R.string.api_http_403
        404 -> R.string.api_http_404
        409 -> R.string.api_http_409
        422 -> R.string.api_http_422
        500 -> R.string.api_http_500
        502 -> R.string.api_http_502
        503 -> R.string.api_http_503
        else -> R.string.api_request_failed
    }.let { resId ->
        if (resId == R.string.api_request_failed) {
            appContext.getString(resId, code)
        } else {
            appContext.getString(resId)
        }
    }

    private suspend fun executeAsync(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    private fun logRequestFailure(method: String, path: String, url: String, error: IOException) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "HTTP request failed: $method $url", error)
        } else {
            Log.e(TAG, "HTTP request failed: $method $path", error)
        }
    }

    companion object {
        private const val TAG = "PortalApi"

        private val noProxySelector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }

        private val directSocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

            override fun createSocket(host: String, port: Int): Socket =
                Socket(Proxy.NO_PROXY).apply { connect(InetSocketAddress(host, port)) }

            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                Socket(Proxy.NO_PROXY).apply {
                    bind(InetSocketAddress(localHost, localPort))
                    connect(InetSocketAddress(host, port))
                }

            override fun createSocket(host: InetAddress, port: Int): Socket =
                Socket(Proxy.NO_PROXY).apply { connect(InetSocketAddress(host, port)) }

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket = Socket(Proxy.NO_PROXY).apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
        }

        private fun createClient(context: Context): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .proxySelector(noProxySelector)
                .connectionSpecs(
                    if (BuildConfig.DEBUG) {
                        listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS)
                    } else {
                        listOf(ConnectionSpec.MODERN_TLS)
                    },
                )
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)

            // 会员 API 必须直连本机/局域网调试地址，不能走 VPN 引擎的 SOCKS/系统代理，也不能绑到蜂窝网络的 socketFactory。
            builder.socketFactory(directSocketFactory)
            return builder.build()
        }
    }
}
