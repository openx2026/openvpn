package com.openvpn.client.ui.orders

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.openvpn.client.api.UserOrder
import com.openvpn.client.databinding.ItemOrderBinding
import com.openvpn.client.util.DateFormats
import com.openvpn.client.util.Labels

class OrdersAdapter(
    private val onClick: (UserOrder) -> Unit,
) : RecyclerView.Adapter<OrdersAdapter.VH>() {
    private val items = mutableListOf<UserOrder>()

    fun submit(list: List<UserOrder>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: UserOrder) {
            binding.orderNo.text = order.orderNo
            binding.orderStatus.text = Labels.statusLabel(order.status)
            binding.orderStatus.setBackgroundColor(statusColor(order.status))
            binding.orderMeta1.text = "${order.subscriptionPlan.name} · ${order.amount} USDT"
            binding.orderMeta2.text = DateFormats.formatLocal(order.createdAt)
            binding.root.setOnClickListener { onClick(order) }
        }

        private fun statusColor(status: String): Int = when (status) {
            "PENDING" -> Color.parseColor("#F59E0B")
            "PAID" -> Color.parseColor("#10B981")
            else -> Color.parseColor("#9CA3AF")
        }
    }
}
