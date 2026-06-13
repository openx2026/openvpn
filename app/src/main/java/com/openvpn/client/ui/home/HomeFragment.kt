package com.openvpn.client.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.openvpn.client.BuildConfig
import com.openvpn.client.R
import com.openvpn.client.databinding.FragmentHomeBinding
import com.openvpn.client.databinding.ViewFeatureChipBinding
import com.openvpn.client.ui.MainActivity
import com.openvpn.client.ui.PortalViewModel

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()
    private lateinit var adapter: PlanGridAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (BuildConfig.DEBUG) {
            binding.debugApiBaseUrlText.isVisible = true
            binding.debugApiBaseUrlText.text = "API_BASE_URL: ${BuildConfig.API_BASE_URL}"
        }
        setupFeatureChips()
        adapter = PlanGridAdapter { plan ->
            (activity as? MainActivity)?.requireLogin {
                viewModel.navigateToShop(plan.id)
            }
        }
        binding.plansRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.plansRecycler.adapter = adapter

        binding.retryButton.setOnClickListener { viewModel.loadCatalog() }

        viewModel.plans.observe(viewLifecycleOwner) { adapter.submit(it) }
        viewModel.loading.observe(viewLifecycleOwner) { binding.loadingBar.isVisible = it && adapter.itemCount == 0 }
        viewModel.error.observe(viewLifecycleOwner) { err ->
            val hasError = !err.isNullOrBlank()
            binding.errorText.isVisible = hasError
            binding.retryButton.isVisible = hasError
            binding.errorText.text = err
        }
        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            binding.pendingBanner.isVisible = orders.any { it.status == "PENDING" }
        }

        if (viewModel.plans.value.isNullOrEmpty()) {
            viewModel.loadCatalog()
        }
    }

    private fun setupFeatureChips() {
        bindChip(binding.chipSpeed, R.string.feature_speed, R.string.feature_speed_sub)
        bindChip(binding.chipFlex, R.string.feature_flex, R.string.feature_flex_sub)
        bindChip(binding.chipTransparent, R.string.feature_transparent, R.string.feature_transparent_sub)
    }

    private fun bindChip(chip: ViewFeatureChipBinding, titleRes: Int, subRes: Int) {
        chip.chipTitle.setText(titleRes)
        chip.chipSub.setText(subRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
