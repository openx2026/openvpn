package com.openvpn.client.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.openvpn.client.R
import com.openvpn.client.api.UserOrder
import com.openvpn.client.databinding.FragmentOrdersBinding
import com.openvpn.client.ui.PortalViewModel
import com.openvpn.client.util.Labels
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OrdersFragment : Fragment() {
    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()
    private lateinit var adapter: OrdersAdapter
    private var countdownJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = OrdersAdapter(
            onClick = { order -> openDetail(order) },
            onCancel = { order -> confirmCancelOrder(order.id) },
        )
        binding.ordersRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.ordersRecycler.adapter = adapter
        binding.ordersRecycler.isNestedScrollingEnabled = false

        binding.retryButton.setOnClickListener { viewModel.refreshOrders() }

        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            adapter.submit(orders)
            binding.subtitleText.text = "${orders.size} 笔记录"
            binding.emptyText.isVisible = orders.isEmpty()
            updateCountdownTicker(orders.any { it.status == "PENDING" })
        }
        viewModel.cancellingOrderId.observe(viewLifecycleOwner) { id ->
            adapter.setCancellingOrderId(id)
        }
        viewModel.ordersLoading.observe(viewLifecycleOwner) { binding.loadingBar.isVisible = it }
        viewModel.ordersLoadError.observe(viewLifecycleOwner) { err ->
            val hasError = !err.isNullOrBlank()
            binding.errorText.isVisible = hasError
            binding.retryButton.isVisible = hasError
            binding.errorText.text = err
        }
        viewModel.ordersJumpDetailId.observe(viewLifecycleOwner) { id ->
            if (id != null) {
                openDetailById(id)
                viewModel.consumeOrdersJump()
            }
        }

        applyScrollBottomInset()
    }

    private fun updateCountdownTicker(hasPending: Boolean) {
        if (!hasPending) {
            countdownJob?.cancel()
            countdownJob = null
            return
        }
        if (countdownJob?.isActive == true) return
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                adapter.tickCountdowns(System.currentTimeMillis())
                delay(1000L)
            }
        }
    }

    private fun applyScrollBottomInset() {
        val baseClearance = resources.getDimensionPixelSize(R.dimen.portal_bottom_bar_scroll_clearance)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            binding.ordersContent.setPadding(
                binding.ordersContent.paddingLeft,
                binding.ordersContent.paddingTop,
                binding.ordersContent.paddingRight,
                baseClearance + bottomInset,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun confirmCancelOrder(orderId: Long) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.cancel_order_confirm))
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                viewModel.cancelOrder(
                    orderId,
                    onSuccess = {
                        Toasty.success(requireContext(), Labels.statusLabel("CANCELLED")).show()
                    },
                    onError = { msg ->
                        Toasty.error(requireContext(), msg).show()
                    },
                )
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun openDetail(order: UserOrder) {
        // 创建订单后切 Tab 与 replace 同事务周期内直接 show 可能触发 FragmentManager 异常
        view?.post {
            if (!isAdded) return@post
            OrderDetailBottomSheet().apply {
                this.order = order
            }.show(parentFragmentManager, OrderDetailBottomSheet.TAG)
        }
    }

    private fun openDetailById(orderId: Long) {
        val cached = viewModel.findOrder(orderId)
        if (cached != null) {
            openDetail(cached)
            return
        }
        viewModel.loadOrderDetail(orderId) { order ->
            if (order == null || !isAdded) return@loadOrderDetail
            openDetail(order)
        }
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        countdownJob = null
        super.onDestroyView()
        _binding = null
    }
}
