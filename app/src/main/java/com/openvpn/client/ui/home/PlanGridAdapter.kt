package com.openvpn.client.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.openvpn.client.api.CatalogPlan
import com.openvpn.client.databinding.ItemPlanCardBinding
import com.openvpn.client.util.Labels

class PlanGridAdapter(
    private val onPlanClick: (CatalogPlan) -> Unit,
) : RecyclerView.Adapter<PlanGridAdapter.VH>() {
    private val items = mutableListOf<CatalogPlan>()

    fun submit(list: List<CatalogPlan>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlanCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemPlanCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(plan: CatalogPlan) {
            binding.planName.text = plan.name
            binding.planTier.text = Labels.planTierLabel(plan.planTier)
            binding.planPrice.text = Labels.formatUsdtPerMonth(plan.priceUsdt)
            binding.planCycle.text = "周期：${Labels.planTypeLabel(plan.planType)}"
            val desc = plan.description?.trim().orEmpty()
            binding.planDesc.isVisible = desc.isNotEmpty()
            binding.planDesc.text = desc
            binding.root.setOnClickListener { onPlanClick(plan) }
        }
    }
}
