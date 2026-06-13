package com.openvpn.client.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import com.openvpn.client.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.MessageUtil
import com.openvpn.client.databinding.ActivityMainBinding
import com.openvpn.client.databinding.ViewPortalNavItemBinding
import com.openvpn.client.ui.home.HomeFragment
import com.openvpn.client.ui.login.LoginBottomSheet
import com.openvpn.client.ui.mine.MineFragment
import com.openvpn.client.ui.orders.OrdersFragment
import com.openvpn.client.ui.shop.ShopFragment
import es.dmoral.toasty.Toasty

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: PortalViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    private val tabFragments = mutableMapOf<PortalTab, Fragment>()

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AppConfig.BROADCAST_ACTION_ACTIVITY) return
            @Suppress("DEPRECATION")
            val content = intent.getSerializableExtra("content")
            viewModel.onVpnEngineStateMessage(intent.getIntExtra("key", 0), content)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnAfterPermission()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        setupBottomNav()

        viewModel.token.observe(this) { updateVpnButton() }

        viewModel.currentTab.observe(this) { tab ->
            updateNavSelection(tab)
            showTab(tab)
        }

        viewModel.vpnRunning.observe(this) { updateVpnButton() }
        viewModel.vpnBusy.observe(this) { updateVpnButton() }
        viewModel.vpnDelayMs.observe(this) { updateVpnButton() }

        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                showPortalToast(msg)
                viewModel.clearToast()
            }
        }

        viewModel.authMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                val message = msg
                viewModel.clearAuthMessage()
                requireLogin(initialMessage = message)
            }
        }

        binding.bottomBarContainer.isVisible = true
        viewModel.selectTab(viewModel.currentTab.value ?: PortalTab.HOME)
        if (viewModel.plans.value.isNullOrEmpty()) {
            viewModel.loadCatalog()
        }
        updateVpnButton()
        updateNavSelection(viewModel.currentTab.value ?: PortalTab.HOME)
        registerVpnStateReceiver()
    }

    private fun showPortalToast(message: String) {
        when {
            message.contains("成功") || message == getString(R.string.vpn_connected) -> {
                Toasty.success(this, message, Toast.LENGTH_SHORT, true).show()
            }
            message == getString(R.string.vpn_disconnected) -> {
                Toasty.info(this, message, Toast.LENGTH_SHORT, true).show()
            }
            else -> {
                Toasty.error(this, message, Toast.LENGTH_SHORT, true).show()
            }
        }
    }

    fun requireLogin(initialMessage: String? = null, onSuccess: () -> Unit = {}) {
        if (viewModel.isLoggedIn()) {
            onSuccess()
            return
        }
        LoginBottomSheet.show(supportFragmentManager, initialMessage) {
            onSuccess()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshVpnState()
        if (viewModel.isLoggedIn()) {
            MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        }
    }

    override fun onDestroy() {
        unregisterVpnStateReceiver()
        super.onDestroy()
    }

    private fun registerVpnStateReceiver() {
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                vpnStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStateReceiver, filter)
        }
    }

    private fun unregisterVpnStateReceiver() {
        runCatching { unregisterReceiver(vpnStateReceiver) }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.fragmentContainer.setPadding(0, bars.top, 0, 0)
            binding.bottomBarContainer.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupBottomNav() {
        val bar = binding.portalBottomBar
        bindNavItem(bar.navHome, R.drawable.ic_nav_home, R.string.nav_home, PortalTab.HOME)
        bindNavItem(bar.navShop, R.drawable.ic_nav_shop, R.string.nav_shop, PortalTab.SHOP)
        bindNavItem(bar.navOrders, R.drawable.ic_nav_orders, R.string.nav_orders, PortalTab.ORDERS)
        bindNavItem(bar.navMine, R.drawable.ic_nav_mine, R.string.nav_mine, PortalTab.MINE)

        bar.vpnConnectButton.setOnClickListener {
            requireLogin {
                viewModel.toggleVpn { intent ->
                    vpnPermissionLauncher.launch(intent)
                }
            }
        }
    }

    private fun bindNavItem(
        item: ViewPortalNavItemBinding,
        iconRes: Int,
        labelRes: Int,
        tab: PortalTab,
    ) {
        item.navIcon.setImageResource(iconRes)
        item.navLabel.setText(labelRes)
        item.root.setOnClickListener {
            if (viewModel.currentTab.value == tab) return@setOnClickListener
            if (tab == PortalTab.HOME) {
                viewModel.selectTab(tab)
                return@setOnClickListener
            }
            requireLogin {
                viewModel.selectTab(tab)
            }
        }
    }

    private fun updateNavSelection(tab: PortalTab) {
        val bar = binding.portalBottomBar
        val primary = getColor(R.color.portal_primary)
        val secondary = getColor(R.color.portal_text_secondary)
        listOf(
            PortalTab.HOME to bar.navHome,
            PortalTab.SHOP to bar.navShop,
            PortalTab.ORDERS to bar.navOrders,
            PortalTab.MINE to bar.navMine,
        ).forEach { (itemTab, item) ->
            val selected = itemTab == tab
            item.navIcon.imageTintList = ColorStateList.valueOf(if (selected) primary else secondary)
            item.navLabel.setTextColor(if (selected) primary else secondary)
            item.navLabel.paint.isFakeBoldText = selected
        }
    }

    private fun updateVpnButton() {
        val bar = binding.portalBottomBar
        val running = viewModel.vpnRunning.value == true
        val busy = viewModel.vpnBusy.value == true
        val delayMs = viewModel.vpnDelayMs.value

        bar.vpnConnectButton.isEnabled = !busy
        bar.vpnConnectButton.alpha = if (!busy) 1f else 0.45f
        bar.vpnConnectButton.setBackgroundResource(
            if (running) R.drawable.bg_vpn_button_active else R.drawable.bg_vpn_button_idle,
        )
        bar.vpnConnectRing.setBackgroundResource(
            if (running) R.drawable.bg_vpn_button_ring_active else R.drawable.bg_vpn_button_ring,
        )

        if (running) {
            bar.vpnConnectIcon.isVisible = false
            bar.vpnConnectDelay.isVisible = true
            bar.vpnConnectDelay.text = when {
                delayMs != null && delayMs > 0 -> getString(R.string.vpn_delay_ms, delayMs)
                busy -> "…"
                else -> "--"
            }
        } else {
            bar.vpnConnectIcon.isVisible = true
            bar.vpnConnectDelay.isVisible = false
            bar.vpnConnectIcon.setImageResource(R.drawable.ic_vpn_power)
        }
    }

    private fun showTab(tab: PortalTab) {
        val fragment = tabFragments.getOrPut(tab) {
            when (tab) {
                PortalTab.HOME -> HomeFragment()
                PortalTab.SHOP -> ShopFragment()
                PortalTab.ORDERS -> OrdersFragment()
                PortalTab.MINE -> MineFragment()
            }
        }
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current === fragment) return
        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment)
        }
    }
}
