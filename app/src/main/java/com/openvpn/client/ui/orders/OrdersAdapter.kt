package com.openvpn.client.ui.orders

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.openvpn.client.R
import com.openvpn.client.api.UserOrder
import com.openvpn.client.api.planDisplayName
import com.openvpn.client.databinding.ItemOrderBinding
import com.openvpn.client.util.DateFormats
import com.openvpn.client.util.Labels
import com.openvpn.client.util.OrderPayCountdown

class OrdersAdapter(
    private val onClick: (UserOrder) -> Unit,
    private val onCancel: (UserOrder) -> Unit,
) : RecyclerView.Adapter<OrdersAdapter.VH>() {
    private val items = mutableListOf<UserOrder>()
    private var cancellingOrderId: Long? = null
    private var countdownNowMs = System.currentTimeMillis()

    fun submit(list: List<UserOrder>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setCancellingOrderId(orderId: Long?) {
        if (cancellingOrderId == orderId) return
        cancellingOrderId = orderId
        notifyDataSetChanged()
    }

    fun tickCountdowns(nowMs: Long) {
        countdownNowMs = nowMs
        for (i in items.indices) {
            if (items[i].status == "PENDING") {
                notifyItemChanged(i, PAYLOAD_COUNTDOWN)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_COUNTDOWN)) {
            val order = items[position]
            if (order.status == "PENDING") {
                holder.bindMetaRight(order)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = items.size

    inner class VH(val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: UserOrder) {
            val ctx = binding.root.context
            binding.orderNo.text = order.orderNo
            binding.orderStatus.text = Labels.statusLabel(order.status)
            binding.orderStatus.backgroundTintList =
                ColorStateList.valueOf(Labels.statusColor(order.status))
            binding.orderPlanSummary.text =
                "${order.planDisplayName()} · ${Labels.formatUsdtPrice(order.amount)} USDT"
            bindMetaRight(order)
            binding.root.setOnClickListener { onClick(order) }

            val pending = order.status == "PENDING"
            binding.cancelOrderButton.isVisible = pending
            if (pending) {
                val cancelling = cancellingOrderId == order.id
                binding.cancelOrderButton.isEnabled = !cancelling
                binding.cancelOrderButton.text = ctx.getString(
                    if (cancelling) R.string.canceling_order else R.string.cancel_order,
                )
                binding.cancelOrderButton.setOnClickListener { onCancel(order) }
            } else {
                binding.cancelOrderButton.setOnClickListener(null)
            }
        }

        fun bindMetaRight(order: UserOrder) {
            val ctx = binding.root.context
            if (order.status == "PENDING") {
                binding.orderMetaRight.text = OrderPayCountdown.format(order.expiredAt, countdownNowMs)
                binding.orderMetaRight.setTextColor(
                    ContextCompat.getColor(ctx, R.color.portal_badge_pending),
                )
                binding.orderMetaRight.typeface = Typeface.MONOSPACE
                binding.orderMetaRight.setTypeface(binding.orderMetaRight.typeface, Typeface.BOLD)
            } else {
                binding.orderMetaRight.text = DateFormats.formatLocal(order.createdAt)
                binding.orderMetaRight.setTextColor(
                    ContextCompat.getColor(ctx, R.color.portal_text_secondary),
                )
                binding.orderMetaRight.typeface = Typeface.DEFAULT
                binding.orderMetaRight.setTypeface(binding.orderMetaRight.typeface, Typeface.NORMAL)
            }
        }
    }

    companion object {
        private const val PAYLOAD_COUNTDOWN = "countdown"
    }
}
