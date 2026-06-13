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
import com.openvpn.client.api.UserProfile
import com.openvpn.client.bridge.VpnEngineBridge
import com.v2ray.ang.AppConfig
import com.openvpn.client.data.SessionStore
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

    private val _profile = MutableLiveData<UserProfile?>(null)
    val profile: LiveData<UserProfile?> = _profile

    private val _membership = MutableLiveData<UserMembershipSnapshot?>(null)
    val membership: LiveData<UserMembershipSnapshot?> = _membership

    private val _subscriptionUrl = MutableLiveData<String?>(null)
    val subscriptionUrl: LiveData<String?> = _subscriptionUrl

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

    val pendingOrder: UserOrder?
        get() = _orders.value?.firstOrNull { it.status == "PENDING" }

    init {
        if (_token.value != null) {
            refreshPortalData()
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
                val res = withContext(Dispatchers.IO) { api.login(username, password) }
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
                val res = withContext(Dispatchers.IO) { api.register(username, password, inviteCode) }
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
        _token.value = null
        _profile.value = null
        _membership.value = null
        stopVpnDelayPolling()
        _subscriptionUrl.value = null
        _orders.value = emptyList()
        _chains.value = emptyList()
        _currentTab.value = PortalTab.HOME
        loadCatalog()
    }

    fun loadCatalog() {
        viewModelScope.launch {
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
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val (p, c) = withContext(Dispatchers.IO) {
                    api.catalogPlans() to api.catalogChains()
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

    fun refreshOrders(onDone: (() -> Unit)? = null) {
        val t = _token.value ?: return
        ordersRefreshJob?.let { existing ->
            if (existing.isActive) {
                onDone?.let { cb -> existing.invokeOnCompletion { cb() } }
                return
            }
        }
        ordersRefreshJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val list = api.orders(t)
                _orders.value = list
            } catch (e: ApiException) {
                if (handleUnauthorized(e)) return@launch
                _error.value = e.message
                _orders.value = emptyList()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
                onDone?.invoke()
            }
        }
    }

    fun loadOrderDetail(orderId: Long, onResult: (UserOrder?) -> Unit) {
        val t = _token.value ?: return
        viewModelScope.launch {
            try {
                val row = withContext(Dispatchers.IO) { api.order(t, orderId) }
                if (row != null) {
                    _orders.value = _orders.value?.map { if (it.id == row.id) row else it }
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

    fun createOrder(planId: Long, chainId: Long, onSuccess: (UserOrder) -> Unit, onError: (String) -> Unit) {
        val t = _token.value ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                val created = withContext(Dispatchers.IO) { api.createOrder(t, planId, chainId) }
                refreshOrders()
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

    fun refreshMine() {
        val t = _token.value ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                val profileRes = runCatching { withContext(Dispatchers.IO) { api.profile(t) } }
                profileRes.onSuccess { _profile.value = it }

                val mRes = runCatching { withContext(Dispatchers.IO) { api.membership(t) } }
                mRes.onSuccess { _membership.value = it }
                    .onFailure { e ->
                        if (e is ApiException && handleUnauthorized(e)) return@launch
                    }

                val oRes = runCatching { withContext(Dispatchers.IO) { api.orders(t) } }
                oRes.onSuccess { _orders.value = it }
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
            refreshSubscriptionUrlInternal(t)
        }
    }

    private suspend fun refreshSubscriptionUrlInternal(token: String) {
        val sRes = runCatching { withContext(Dispatchers.IO) { api.subscriptionFeedUrl(token) } }
        sRes.onSuccess {
            _subscriptionUrl.value = Labels.normalizeSubscriptionFeedUrl(it.feedUrl)
        }.onFailure { e ->
            if (e is ApiException && handleUnauthorized(e)) return
            _subscriptionUrl.value = null
        }
    }

    fun refreshPortalData() {
        loadCatalog()
        refreshMine()
    }

    private fun persistToken(token: String) {
        sessionStore.token = token
        _token.value = token
        refreshPortalData()
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
                val feedUrl = runCatching {
                    Labels.normalizeSubscriptionFeedUrl(api.subscriptionFeedUrl(t).feedUrl)
                }.onFailure { e ->
                    if (e is ApiException && handleUnauthorized(e)) return@launch
                }.getOrNull()
                _subscriptionUrl.value = feedUrl
                if (feedUrl.isNullOrBlank()) {
                    _toastMessage.value = getApplication<Application>().getString(com.openvpn.client.R.string.vpn_no_feed_url)
                    return@launch
                }

                val prepResult = withContext(Dispatchers.IO) {
                    VpnEngineBridge.prepareSubscription(getApplication(), feedUrl)
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
}
