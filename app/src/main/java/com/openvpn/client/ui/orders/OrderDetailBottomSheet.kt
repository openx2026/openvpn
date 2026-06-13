package com.openvpn.client.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.openvpn.client.R
import com.openvpn.client.api.UserOrder
import com.openvpn.client.api.planDisplayName
import com.openvpn.client.databinding.BottomSheetOrderDetailBinding
import com.openvpn.client.databinding.ViewKvRowBinding
import com.openvpn.client.ui.PortalViewModel
import com.openvpn.client.util.ClipboardUtil
import com.openvpn.client.util.DateFormats
import com.openvpn.client.util.Labels
import com.openvpn.client.util.QrUtil
import es.dmoral.toasty.Toasty

class OrderDetailBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetOrderDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()

    var order: UserOrder? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val o = order ?: return dismiss()
        bindOrder(o)

        viewModel.orders.observe(viewLifecycleOwner) { list ->
            val currentId = order?.id ?: return@observe
            val fresh = list.firstOrNull { it.id == currentId } ?: return@observe
            val wasPending = order?.status == "PENDING"
            bindOrder(fresh)
            if (wasPending && fresh.status == "PAID") {
                Toasty.success(requireContext(), Labels.statusLabel("PAID")).show()
            }
        }

        viewModel.cancellingOrderId.observe(viewLifecycleOwner) { id ->
            val current = order
            if (current == null || current.status != "PENDING") return@observe
            val cancelling = id == current.id
            binding.cancelOrderButton.isEnabled = !cancelling
            binding.cancelOrderButton.text = getString(
                if (cancelling) R.string.canceling_order else R.string.cancel_order,
            )
        }

        binding.closeButton.setOnClickListener { dismiss() }
    }

    private fun bindOrder(o: UserOrder) {
        order = o
        binding.detailOrderNo.text = o.orderNo
        binding.detailStatus.text = Labels.statusLabel(o.status)
        binding.detailStatus.setBackgroundColor(Labels.statusColor(o.status))
        bindKv(binding.kvPlan, "套餐", o.planDisplayName())
        bindKv(binding.kvAmount, "应付 USDT", o.amount.toString())
        bindKv(binding.kvChain, "支付链", "${Labels.chainHint(o.chainId ?: 0)} (${o.chainId ?: "—"})")
        bindKv(binding.kvExpires, "支付截止", DateFormats.formatLocal(o.expiredAt))

        when {
            o.status == "CANCELLED" -> {
                binding.txHashText.text = getString(R.string.order_cancelled)
            }
            o.txHash?.trim().orEmpty().isNotEmpty() -> {
                binding.txHashText.text = o.txHash!!.trim()
            }
            else -> {
                binding.txHashText.text = getString(R.string.tx_hash_empty)
            }
        }

        val pending = o.status == "PENDING"
        binding.pendingPaymentSection.isVisible = pending
        binding.cancelOrderButton.isVisible = pending
        if (pending) {
            binding.toAddressText.text = o.toAddress
            QrUtil.encode(o.toAddress, 512)?.let { binding.qrImage.setImageBitmap(it) }
            binding.copyAddressButton.setOnClickListener {
                if (ClipboardUtil.copy(requireContext(), "address", o.toAddress)) {
                    showTip(getString(R.string.copied))
                } else {
                    showTip(getString(R.string.copy_failed))
                }
            }
            binding.cancelOrderButton.setOnClickListener {
                confirmCancelOrder(o.id)
            }
        } else {
            binding.cancelOrderButton.setOnClickListener(null)
        }
    }

    private fun confirmCancelOrder(orderId: Long) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.cancel_order_confirm))
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                viewModel.cancelOrder(
                    orderId,
                    onSuccess = { updated ->
                        bindOrder(updated)
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

    private fun bindKv(kv: ViewKvRowBinding, label: String, value: String) {
        kv.kvLabel.text = label
        kv.kvValue.text = value
    }

    private fun showTip(msg: String) {
        binding.tipText.isVisible = true
        binding.tipText.text = msg
        Toasty.info(requireContext(), msg).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OrderDetailBottomSheet"
    }
}
