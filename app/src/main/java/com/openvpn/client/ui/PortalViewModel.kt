package com.openvpn.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openvpn.client.api.ApiException
import com.openvpn.client.api.CatalogChain
import com.openvpn.client.api.CatalogPlan
import com.openvpn.client.api.PortalApi
import com.openvpn.client.api.UserMembershipInfo
import com.openvpn.client.api.UserMembershipSnapshot
import com.openvpn.client.api.UserOrder
import com.openvpn.client.api.sortedByCreatedAtDesc
import com.openvpn.client.api.UserProfile
import com.openvpn.client.bridge.VpnEngineBridge
import com.v2ray.ang.AppConfig
import com.openvpn.client.data.SessionStore
import com.openvpn.client.data.SubscriptionBodyCache
import com.openvpn.client.data.SubscriptionFeedStore
import com.openvpn.client.util.DeviceIdentity
import com.openvpn.client.util.Labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

enum class PortalTab { HOME, SHOP, ORDERS, MINE }

class PortalViewModel(app: Application) : AndroidViewModel(app) {
    private val api = PortalApi()
    private val sessionStore = SessionStore(app)
    private val subscriptionFeedStore = SubscriptionFeedStore(app)
    private val subscriptionBodyCache = SubscriptionBodyCache(app)

    private val _token = MutableLiveData(sessionStore.token)
    val token: LiveData<String?> = _token

    private val _authMessage = MutableLiveData<String?>(null)
    val authMessage: LiveData<String?> = _authMessage

    private val _currentTab = MutableLiveData(PortalTab.HOME)
    val currentTab: LiveData<PortalTab> = _currentTab

    private val _plans = MutableLiveData<List<CatalogPlan>>(emptyList())
    val plans: LiveData<List<CatalogPlan>> = _plans

    private val _chains = MutableLiveData<List<CatalogChain>>(emptyList())
    val chains: LiveData<List<CatalogChain>> = _chains

    private val _orders = MutableLiveData<List<UserOrder>>(emptyList())
    val orders: LiveData<List<UserOrder>> = _orders

    private val _ordersLoading = MutableLiveData(false)
    val ordersLoading: LiveData<Boolean> = _ordersLoading

    private val _ordersLoadError = MutableLiveData<String?>(null)
    val ordersLoadError: LiveData<String?> = _ordersLoadError

    private val _cancellingOrderId = MutableLiveData<Long?>(null)
    val cancellingOrderId: LiveData<Long?> = _cancellingOrderId

    private val _profile = MutableLiveData<UserProfile?>(null)
    val profile: LiveData<UserProfile?> = _profile

    private val _membership = MutableLiveData<UserMembershipSnapshot?>(null)
    val membership: LiveData<UserMembershipSnapshot?> = _membership

    private val _subscriptionUrl = MutableLiveData<String?>(null)
    val subscriptionUrl: LiveData<String?> = _subscriptionUrl

    private val _subscriptionFeedExpiresAt = MutableLiveData<String?>(null)
    val subscriptionFeedExpiresAt: LiveData<String?> = _subscriptionFeedExpiresAt

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _shopPrefillPlanId = MutableLiveData<Long?>(null)
    val shopPrefillPlanId: LiveData<Long?> = _shopPrefillPlanId

    private val _ordersJumpDetailId = MutableLiveData<Long?>(null)
    val ordersJumpDetailId: LiveData<Long?> = _ordersJumpDetailId

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    private val _vpnRunning = MutableLiveData(false)
    val vpnRunning: LiveData<Boolean> = _vpnRunning

    private val _vpnBusy = MutableLiveData(false)
    val vpnBusy: LiveData<Boolean> = _vpnBusy

    private val _vpnDelayMs = MutableLiveData<Int?>(null)
    val vpnDelayMs: LiveData<Int?> = _vpnDelayMs

    private var vpnDelayPollJob: Job? = null
    private var delayMeasureJob: Job? = null
    private var ordersRefreshJob: Job? = null
    private var mineRefreshJob: Job? = null
    private var catalogJob: Job? = null
    private var shopCatalogJob: Job? = null
    private var pendingOrderPollJob: Job? = null
    private var ordersCacheLoaded = false

    val pendingOrder: UserOrder?
        get() = _orders.value?.firstOrNull { it.status == "PENDING" }

    init {
        hydrateSubscriptionFeedFromCache()
        loadCatalog()
        if (_token.value != null) {
            refreshMine()
            loadOrdersCache() // 冷启动已登录：全量拉一次订单列表
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun setAuthMessage(message: String?) {
        _authMessage.value = message
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun isLoggedIn(): Boolean = !_token.value.isNullOrBlank()

    fun selectTab(tab: PortalTab) {
        if (_currentTab.value == tab) return
        _currentTab.value = tab
    }

    fun navigateToShop(planId: Long) {
        _shopPrefillPlanId.value = planId
        _currentTab.value = PortalTab.SHOP
    }

    fun consumeShopPrefill() {
        _shopPrefillPlanId.value = null
    }

    fun navigateToOrders(orderId: Long) {
        _ordersJumpDetailId.value = orderId
        _currentTab.value = PortalTab.ORDERS
    }

    fun consumeOrdersJump() {
        _ordersJumpDetailId.value = null
    }

    fun login(username: String, password: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val deviceId = DeviceIdentity.registrationDeviceId(getApplication())
                if (deviceId == null) {
                    _error.value =
                        getApplication<Application>().getString(com.openvpn.client.R.string.register_device_unavailable)
                    return@launch
                }
                val res = withContext(Dispatchers.IO) {
                    api.login(username, password, deviceId)
                }
                persistToken(res.accessToken)
                _toastMessage.value = "登录成功。"
                onSuccess()
            } catch (e: ApiException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = e.message ?: "请求失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun register(username: String, password: String, inviteCode: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val deviceId = DeviceIdentity.registrationDeviceId(getApplication())
                if (deviceId == null) {
                    _error.value =
                        getApplication<Application>().getString(com.openvpn.client.R.string.register_device_unavailable)
                    return@launch
                }
                val res = withContext(Dispatchers.IO) {
                    api.register(username, password, inviteCode, deviceId)
                }
                persistToken(res.accessToken)
                _toastMessage.value = "注册成功，已自动登录。"
                onSuccess()
            } catch (e: ApiException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = e.message ?: "请求失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val token = _token.value
            if (token.isNullOrBlank()) {
                _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.session_expired)
                return@launch
            }
            _loading.value = true
            try {
                withContext(Dispatchers.IO) {
                    api.changePassword(token, currentPassword, newPassword)
                }
                _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.change_password_success)
                logout()
                onSuccess()
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return@launch
                _toastMessage.value = e.message
            } catch (e: Exception) {
                _toastMessage.value = e.message ?: "修改失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun logout() {
        sessionStore.clear()
        subscriptionFeedStore.clear()
        subscriptionBodyCache.clear()
        _token.value = null
        _profile.value = null
        _membership.value = null
        stopVpnDelayPolling()
        _subscriptionUrl.value = null
        _subscriptionFeedExpiresAt.value = null
        _orders.value = emptyList()
        ordersCacheLoaded = false
        pendingOrderPollJob?.cancel()
        pendingOrderPollJob = null
        _ordersLoadError.value = null
        _chains.value = emptyList()
        _currentTab.value = PortalTab.HOME
        loadCatalog()
    }

    fun loadCatalog() {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val p = withContext(Dispatchers.IO) { api.catalogPlans() }
                _plans.value = p
            } catch (e: Exception) {
                _error.value = (e as? ApiException)?.message ?: e.message ?: "无法加载套餐目录"
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadShopCatalog() {
        if (shopCatalogJob?.isActive == true) return
        shopCatalogJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val (p, c) = withContext(Dispatchers.IO) {
                    val plans = if (_plans.value.isNullOrEmpty()) {
                        api.catalogPlans()
                    } else {
                        _plans.value!!
                    }
                    plans to api.catalogChains()
                }
                _plans.value = p
                _chains.value = c
            } catch (e: Exception) {
                _error.value = (e as? ApiException)?.message ?: e.message ?: "无法加载目录"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 全量拉取订单列表。默认仅冷启动已登录 / 登录成功后拉一次；force=true 时强制刷新（订单页重试） */
    fun loadOrdersCache(force: Boolean = false, onDone: (() -> Unit)? = null) {
        val t = _token.value ?: return
        if (!force && ordersCacheLoaded) {
            onDone?.invoke()
            return
        }
        ordersRefreshJob?.let { existing ->
            if (existing.isActive) {
                onDone?.let { cb -> existing.invokeOnCompletion { cb() } }
                return
            }
        }
        ordersRefreshJob = viewModelScope.launch {
            _ordersLoading.value = true
            if (force) _ordersLoadError.value = null
            try {
                val list = api.orders(t)
                _orders.value = list.sortedByCreatedAtDesc()
                ordersCacheLoaded = true
                _ordersLoadError.value = null
                syncPendingOrderPolling()
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return@launch
                _ordersLoadError.value = e.message
                if (!ordersCacheLoaded) {
                    _orders.value = emptyList()
                }
            } catch (e: Exception) {
                _ordersLoadError.value = e.message
                if (!ordersCacheLoaded) {
                    _orders.value = emptyList()
                }
            } finally {
                _ordersLoading.value = false
                onDone?.invoke()
            }
        }
    }

    fun refreshOrders(onDone: (() -> Unit)? = null) {
        loadOrdersCache(force = true, onDone = onDone)
    }

    fun loadOrderDetail(orderId: Long, onResult: (UserOrder?) -> Unit) {
        val cached = findOrder(orderId)
        if (cached != null) {
            onResult(cached)
            return
        }
        refreshOrderDetailFromApi(orderId, onResult)
    }

    /** 强制从接口拉取订单详情并写入本地列表（待支付轮询用） */
    fun refreshOrderDetailFromApi(orderId: Long, onResult: (UserOrder?) -> Unit) {
        val t = _token.value ?: return
        viewModelScope.launch {
            try {
                val row = withContext(Dispatchers.IO) { api.order(t, orderId) }
                if (row != null) {
                    upsertOrder(row)
                }
                onResult(row)
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return@launch
                onResult(null)
            } catch (_: Exception) {
                onResult(null)
            }
        }
    }

    fun findOrder(orderId: Long): UserOrder? =
        _orders.value?.firstOrNull { it.id == orderId }

    private fun upsertOrder(order: UserOrder) {
        val list = _orders.value.orEmpty()
        _orders.value = list.map { if (it.id == order.id) order else it }
            .let { updated ->
                if (updated.any { it.id == order.id }) updated else updated + order
            }
            .sortedByCreatedAtDesc()
        syncPendingOrderPolling()
    }

    /** 存在待支付订单时每 3 秒拉取详情并增量更新缓存（不依赖详情页是否打开） */
    private fun syncPendingOrderPolling() {
        val hasPending = _orders.value.orEmpty().any { it.status == "PENDING" }
        if (!hasPending || _token.value == null) {
            pendingOrderPollJob?.cancel()
            pendingOrderPollJob = null
            return
        }
        if (pendingOrderPollJob?.isActive == true) return

        pendingOrderPollJob = viewModelScope.launch {
            while (isActive) {
                val pendingIds = _orders.value.orEmpty()
                    .filter { it.status == "PENDING" }
                    .map { it.id }
                if (pendingIds.isEmpty()) break

                refreshPendingOrdersFromApi(pendingIds)
                delay(PENDING_ORDER_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshPendingOrdersFromApi(orderIds: List<Long>) {
        val t = _token.value ?: return
        for (id in orderIds) {
            try {
                val row = withContext(Dispatchers.IO) { api.order(t, id) }
                if (row != null) {
                    upsertOrderWithoutPollSync(row)
                }
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return
            } catch (_: Exception) {
                // 单次轮询失败忽略，下一轮继续
            }
        }
        syncPendingOrderPolling()
    }

    private fun upsertOrderWithoutPollSync(order: UserOrder) {
        val list = _orders.value.orEmpty()
        _orders.value = list.map { if (it.id == order.id) order else it }
            .let { updated ->
                if (updated.any { it.id == order.id }) updated else updated + order
            }
            .sortedByCreatedAtDesc()
    }

    fun createOrder(planId: Long, chainId: Long, onSuccess: (UserOrder) -> Unit, onError: (String) -> Unit) {
        val t = _token.value ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                val created = withContext(Dispatchers.IO) { api.createOrder(t, planId, chainId) }
                upsertOrder(created)
                onSuccess(created)
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return@launch
                onError(e.message)
            } catch (e: Exception) {
                onError(e.message ?: "创建失败")
            } finally {
                _loading.value = false
            }
        }
    }

    fun cancelOrder(
        orderId: Long,
        onSuccess: (UserOrder) -> Unit,
        onError: (String) -> Unit,
    ) {
        val t = _token.value ?: return
        viewModelScope.launch {
            _cancellingOrderId.value = orderId
            try {
                val row = withContext(Dispatchers.IO) { api.cancelOrder(t, orderId) }
                upsertOrder(row)
                onSuccess(row)
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return@launch
                onError(e.message)
            } catch (e: Exception) {
                onError(e.message ?: "取消订单失败")
            } finally {
                _cancellingOrderId.value = null
            }
        }
    }

    fun refreshMine() {
        val t = _token.value ?: return
        mineRefreshJob?.let { existing ->
            if (existing.isActive) return
        }
        mineRefreshJob = viewModelScope.launch {
            _loading.value = true
            try {
                val profileRes = runCatching { withContext(Dispatchers.IO) { api.profile(t) } }
                profileRes.onSuccess { _profile.value = it }

                val mRes = runCatching { withContext(Dispatchers.IO) { api.membership(t) } }
                mRes.onSuccess { _membership.value = it }
                    .onFailure { e ->
                        if (e is ApiException && handleUnauthorized(e)) return@launch
                    }

                refreshSubscriptionUrlInternal(t)
            } finally {
                _loading.value = false
            }
        }
    }

    fun refreshSubscriptionUrl() {
        val t = _token.value ?: return
        viewModelScope.launch {
            resolveSubscriptionFeedUrl(t, forceRefresh = true)
        }
    }

    private fun hydrateSubscriptionFeedFromCache() {
        val cached = subscriptionFeedStore.read()
        if (cached == null || !subscriptionFeedStore.isCachedEntryValid(cached)) return
        applySubscriptionFeedLiveData(cached.feedUrl, cached.expiresAt)
    }

    private fun applySubscriptionFeedLiveData(feedUrl: String?, expiresAt: String?) {
        _subscriptionUrl.value = feedUrl
        _subscriptionFeedExpiresAt.value = expiresAt
    }

    private suspend fun resolveSubscriptionFeedUrl(
        authToken: String,
        forceRefresh: Boolean = false,
    ): String? {
        if (!forceRefresh) {
            val cached = subscriptionFeedStore.read()
            if (cached != null && subscriptionFeedStore.isCachedEntryValid(cached)) {
                applySubscriptionFeedLiveData(cached.feedUrl, cached.expiresAt)
                return cached.feedUrl
            }
        }
        return refreshSubscriptionUrlFromApi(authToken)
    }

    private suspend fun refreshSubscriptionUrlInternal(authToken: String) {
        resolveSubscriptionFeedUrl(authToken, forceRefresh = false)
    }

    private suspend fun refreshSubscriptionUrlFromApi(authToken: String): String? {
        val sRes = runCatching { withContext(Dispatchers.IO) { api.subscriptionFeedUrl(authToken) } }
        return sRes.fold(
            onSuccess = { response ->
                val feedUrl = Labels.normalizeSubscriptionFeedUrl(response.feedUrl)
                val expiresAt = response.expiresAt?.trim()?.takeIf { it.isNotEmpty() }
                if (!feedUrl.isNullOrBlank() && expiresAt != null) {
                    subscriptionFeedStore.save(feedUrl, expiresAt)
                } else {
                    subscriptionFeedStore.clear()
                }
                applySubscriptionFeedLiveData(feedUrl, expiresAt)
                feedUrl
            },
            onFailure = { e ->
                if (e is ApiException && handleUnauthorized(e)) return null
                subscriptionFeedStore.clear()
                applySubscriptionFeedLiveData(null, null)
                null
            },
        )
    }

    fun refreshPortalData() {
        if (_plans.value.isNullOrEmpty()) {
            loadCatalog()
        }
        refreshMine()
    }

    private fun persistToken(token: String) {
        sessionStore.token = token
        _token.value = token
        refreshPortalData()
        loadOrdersCache() // 未登录打开 App 后登录：全量拉一次订单列表
    }


    fun refreshVpnState() {
        val running = VpnEngineBridge.isRunning(getApplication())
        setVpnRunningState(running)
        if (running && (_vpnDelayMs.value == null || _vpnDelayMs.value!! <= 0)) {
            scheduleDelayMeasureRetries()
        }
    }

    fun onVpnEngineStateMessage(key: Int, content: Serializable? = null) {
        when (key) {
            AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                setVpnRunningState(true)
                scheduleDelayMeasureRetries()
            }
            AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                VpnEngineBridge.parseDelayMeasureResult(content)?.let { delay ->
                    _vpnDelayMs.value = delay
                }
            }
            AppConfig.MSG_STATE_NOT_RUNNING,
            AppConfig.MSG_STATE_START_FAILURE,
            AppConfig.MSG_STATE_STOP_SUCCESS,
            -> setVpnRunningState(false)
        }
    }

    private fun setVpnRunningState(running: Boolean) {
        val wasRunning = _vpnRunning.value == true
        _vpnRunning.value = running
        if (running) {
            if (!wasRunning) {
                startVpnDelayPolling()
            }
        } else {
            stopVpnDelayPolling()
        }
    }

    /** Core may not be ready when the VPN service process starts; retry after tunnel is up. */
    private fun scheduleDelayMeasureRetries() {
        delayMeasureJob?.cancel()
        delayMeasureJob = viewModelScope.launch {
            val app = getApplication<Application>()
            for (waitMs in listOf(0L, 1000L, 2500L, 5000L)) {
                if (_vpnRunning.value != true) return@launch
                if (waitMs > 0L) delay(waitMs)
                if (!VpnEngineBridge.isRunning(app)) return@launch
                withContext(Dispatchers.IO) {
                    VpnEngineBridge.requestDelayMeasure(app)
                }
            }
        }
    }

    private fun startVpnDelayPolling() {
        if (vpnDelayPollJob?.isActive == true) return
        vpnDelayPollJob = viewModelScope.launch {
            delay(2000)
            while (isActive) {
                val app = getApplication<Application>()
                if (!VpnEngineBridge.isRunning(app)) {
                    setVpnRunningState(false)
                    break
                }
                withContext(Dispatchers.IO) {
                    VpnEngineBridge.requestDelayMeasure(app)
                }
                delay(4000)
            }
        }
    }

    private fun stopVpnDelayPolling() {
        vpnDelayPollJob?.cancel()
        vpnDelayPollJob = null
        delayMeasureJob?.cancel()
        delayMeasureJob = null
        _vpnDelayMs.value = null
    }

    fun toggleVpn(onNeedVpnPermission: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (VpnEngineBridge.isRunning(app)) {
                VpnEngineBridge.stopVpn(app)
                setVpnRunningState(false)
                _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_disconnected)
                return@launch
            }

            val membership = refreshMembershipForVpn()
            vpnMembershipBlockMessage(membership)?.let { message ->
                _toastMessage.value = message
                return@launch
            }

            _vpnBusy.value = true
            var vpnStartHandoff = false
            try {
                val geoResult = withContext(Dispatchers.IO) {
                    VpnEngineBridge.ensureGeoAssets(getApplication())
                }
                if (geoResult.isFailure) {
                    _toastMessage.value = geoResult.exceptionOrNull()?.message
                        ?: getApplication<Application>().getString(com.openvpn.client.R.string.vpn_geo_assets_missing)
                    return@launch
                }

                val t = _token.value
                if (t.isNullOrBlank()) {
                    _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_no_feed_url)
                    return@launch
                }
                val feedUrl = resolveSubscriptionFeedUrl(t)
                if (feedUrl.isNullOrBlank()) {
                    _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_no_feed_url)
                    return@launch
                }

                val prepResult = withContext(Dispatchers.IO) {
                    val feedExpiresAt = _subscriptionFeedExpiresAt.value
                        ?: subscriptionFeedStore.read()?.expiresAt
                    VpnEngineBridge.prepareSubscription(getApplication(), feedUrl, feedExpiresAt)
                }
                if (prepResult.isFailure) {
                    _toastMessage.value = prepResult.exceptionOrNull()?.message ?: "订阅同步失败"
                    return@launch
                }

                val prepIntent = VpnEngineBridge.vpnPrepareIntent(getApplication())
                if (prepIntent != null) {
                    onNeedVpnPermission(prepIntent)
                } else {
                    vpnStartHandoff = true
                    startVpnAfterPermission()
                }
            } finally {
                if (!vpnStartHandoff) {
                    _vpnBusy.value = false
                }
            }
        }
    }

    fun startVpnAfterPermission() {
        viewModelScope.launch {
            _vpnBusy.value = true
            try {
                withContext(Dispatchers.Main) {
                    VpnEngineBridge.startVpn(getApplication())
                }
                val running = waitForVpnRunning()
                setVpnRunningState(running)
                if (running) {
                    scheduleDelayMeasureRetries()
                    _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_connected)
                } else {
                    _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_start_failed)
                }
            } catch (e: Exception) {
                setVpnRunningState(false)
                _toastMessage.value = e.message ?: getApplication<Application>().getString(com.openvpn.client.R.string.vpn_start_failed)
            } finally {
                _vpnBusy.value = false
            }
        }
    }

    private suspend fun refreshMembershipForVpn(): UserMembershipInfo? {
        val token = _token.value ?: return _membership.value?.membership
        val snapshot = runCatching {
            withContext(Dispatchers.IO) { api.membership(token) }
        }.getOrNull()
        if (snapshot != null) {
            _membership.value = snapshot
        }
        return snapshot?.membership ?: _membership.value?.membership
    }

    private fun vpnMembershipBlockMessage(membership: UserMembershipInfo?): String? {
        val app = getApplication<Application>()
        if (membership == null) {
            return app.getString(com.openvpn.client.R.string.vpn_not_member)
        }
        if (membership.status != "ACTIVE") {
            return app.getString(com.openvpn.client.R.string.vpn_not_member)
        }
        if (!Labels.isMembershipActive(membership)) {
            return app.getString(com.openvpn.client.R.string.vpn_membership_expired)
        }
        return null
    }

    private suspend fun waitForVpnRunning(
        timeoutMs: Long = 10_000L,
        intervalMs: Long = 300L,
    ): Boolean {
        val app = getApplication<Application>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (VpnEngineBridge.isRunning(app)) return true
            delay(intervalMs)
        }
        return VpnEngineBridge.isRunning(app)
    }

    fun onVpnPermissionDenied() {
        _vpnBusy.value = false
        _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_permission_denied)
    }

    private fun handleUnauthorized(e: ApiException): Boolean {
        if (e.status != 401) return false
        logout()
        _authMessage.value = "登录已失效，请重新登录。"
        return true
    }

    private companion object {
        private const val PENDING_ORDER_POLL_INTERVAL_MS = 3_000L
    }
}
