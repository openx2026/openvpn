package com.openvpn.client.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.openvpn.client.R
import com.openvpn.client.api.CatalogChain
import com.openvpn.client.api.CatalogPlan
import com.openvpn.client.databinding.FragmentShopBinding
import com.openvpn.client.ui.PortalViewModel
import com.openvpn.client.util.Labels
import es.dmoral.toasty.Toasty

class ShopFragment : Fragment() {
    private var _binding: FragmentShopBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()

    private var plans: List<CatalogPlan> = emptyList()
    private var chains: List<CatalogChain> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShopBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.retryButton.setOnClickListener { viewModel.loadShopCatalog() }
        binding.createOrderButton.setOnClickListener { createOrder() }

        viewModel.plans.observe(viewLifecycleOwner) {
            plans = it
            bindPlanSpinner()
            applyPrefill()
        }
        viewModel.chains.observe(viewLifecycleOwner) {
            chains = it
            bindChainSpinner()
        }
        viewModel.loading.observe(viewLifecycleOwner) { binding.loadingBar.isVisible = it }
        viewModel.error.observe(viewLifecycleOwner) { err ->
            val hasError = !err.isNullOrBlank()
            binding.errorText.isVisible = hasError
            binding.retryButton.isVisible = hasError
            binding.errorText.text = err
        }
        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            val pending = orders.any { it.status == "PENDING" }
            binding.pendingBanner.isVisible = pending
            binding.createOrderButton.isEnabled = !pending && plans.isNotEmpty() && chains.isNotEmpty()
        }
        viewModel.shopPrefillPlanId.observe(viewLifecycleOwner) { applyPrefill() }

        if (plans.isEmpty() || chains.isEmpty()) {
            viewModel.loadShopCatalog()
        }
        viewModel.refreshOrders()
    }

    private fun bindPlanSpinner() {
        val labels = plans.map { p ->
            "${p.name} · ${Labels.planTierLabel(p.planTier)} · ${Labels.planTypeLabel(p.planType)} · ${Labels.formatUsdtPerMonth(p.priceUsdt)}"
        }
        binding.planSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun bindChainSpinner() {
        val labels = chains.map { c -> "${Labels.chainHint(c.chainId)}（chainId ${c.chainId}）" }
        binding.chainSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun applyPrefill() {
        val prefill = viewModel.shopPrefillPlanId.value ?: return
        val idx = plans.indexOfFirst { it.id == prefill }
        if (idx >= 0) {
            binding.planSpinner.setSelection(idx)
            viewModel.consumeShopPrefill()
        }
    }

    private fun createOrder() {
        if (plans.isEmpty() || chains.isEmpty()) return
        val plan = plans.getOrNull(binding.planSpinner.selectedItemPosition) ?: return
        val chain = chains.getOrNull(binding.chainSpinner.selectedItemPosition) ?: return
        binding.messageText.isVisible = false
        viewModel.createOrder(plan.id, chain.chainId,
            onSuccess = { created ->
                binding.messageText.isVisible = true
                binding.messageText.text = "订单已创建，请在「订单」中查看收款信息。"
                Toasty.success(requireContext(), "订单已创建").show()
                viewModel.navigateToOrders(created.id)
            },
            onError = { msg ->
                binding.messageText.isVisible = true
                binding.messageText.text = msg
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
