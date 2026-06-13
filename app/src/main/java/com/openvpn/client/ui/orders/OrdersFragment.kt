package com.openvpn.client.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.openvpn.client.R
import com.openvpn.client.databinding.FragmentOrdersBinding
import com.openvpn.client.ui.PortalViewModel

class OrdersFragment : Fragment() {
    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()
    private lateinit var adapter: OrdersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = OrdersAdapter { order -> openDetail(order.id) }
        binding.ordersRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.ordersRecycler.adapter = adapter
        binding.ordersRecycler.isNestedScrollingEnabled = false

        binding.retryButton.setOnClickListener { viewModel.refreshOrders() }

        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            adapter.submit(orders)
            binding.subtitleText.text = "${orders.size} 笔记录"
            binding.emptyText.isVisible = orders.isEmpty()
        }
        viewModel.loading.observe(viewLifecycleOwner) { binding.loadingBar.isVisible = it }
        viewModel.error.observe(viewLifecycleOwner) { err ->
            val hasError = !err.isNullOrBlank()
            binding.errorText.isVisible = hasError
            binding.retryButton.isVisible = hasError
            binding.errorText.text = err
        }
        viewModel.ordersJumpDetailId.observe(viewLifecycleOwner) { id ->
            if (id != null) {
                openDetail(id)
                viewModel.consumeOrdersJump()
            }
        }

        applyScrollBottomInset()
        viewModel.refreshOrders()
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

    private fun openDetail(orderId: Long) {
        viewModel.loadOrderDetail(orderId) { order ->
            if (order == null) return@loadOrderDetail
            OrderDetailBottomSheet().apply {
                this.order = order
            }.show(parentFragmentManager, OrderDetailBottomSheet.TAG)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
