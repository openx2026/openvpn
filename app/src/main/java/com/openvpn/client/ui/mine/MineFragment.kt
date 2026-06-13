package com.openvpn.client.ui.mine

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.openvpn.client.R
import com.openvpn.client.api.UserMembershipInfo
import com.openvpn.client.databinding.FragmentMineBinding
import com.openvpn.client.databinding.ViewKvRowBinding
import com.openvpn.client.ui.PortalViewModel
import com.openvpn.client.util.ClipboardUtil
import com.openvpn.client.util.Labels
import es.dmoral.toasty.Toasty

class MineFragment : Fragment() {
    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.changePasswordButton.setOnClickListener { showChangePasswordDialog() }
        binding.logoutButton.setOnClickListener { viewModel.logout() }
        binding.copyInviteButton.setOnClickListener { copyInviteCode() }
        binding.inviteButton.setOnClickListener { shareInvite() }

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            val name = profile?.username.orEmpty().ifBlank { "—" }
            binding.usernameText.text = name
            binding.avatarText.text = Labels.avatarLetters(name)
            renderInviteCode(profile?.inviteCode)
        }
        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            binding.pendingBanner.isVisible = orders.any { it.status == "PENDING" }
        }
        viewModel.membership.observe(viewLifecycleOwner) { snapshot ->
            renderMembership(snapshot?.membership, snapshot)
        }

        applyScrollBottomInset()
        viewModel.refreshMine()
    }

    private fun applyScrollBottomInset() {
        val baseClearance = resources.getDimensionPixelSize(R.dimen.portal_bottom_bar_scroll_clearance)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            binding.mineContent.setPadding(
                binding.mineContent.paddingLeft,
                binding.mineContent.paddingTop,
                binding.mineContent.paddingRight,
                baseClearance + bottomInset,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun renderInviteCode(rawCode: String?) {
        val code = rawCode?.trim().orEmpty()
        val ready = code.length == 8
        binding.inviteCodeText.text = if (ready) code else getString(R.string.invite_code_pending)
        binding.inviteCodeText.setTextColor(
            requireContext().getColor(if (ready) R.color.portal_primary else R.color.portal_text_secondary),
        )
        binding.copyInviteButton.isEnabled = ready
        binding.inviteButton.isEnabled = ready
        binding.copyInviteButton.alpha = if (ready) 1f else 0.5f
        binding.inviteButton.alpha = if (ready) 1f else 0.5f
    }

    private fun copyInviteCode() {
        val code = viewModel.profile.value?.inviteCode?.trim().orEmpty()
        if (code.length != 8) {
            Toasty.info(requireContext(), getString(R.string.invite_code_pending)).show()
            return
        }
        if (ClipboardUtil.copy(requireContext(), "invite_code", code)) {
            Toasty.success(requireContext(), getString(R.string.copied)).show()
        } else {
            Toasty.error(requireContext(), getString(R.string.copy_failed)).show()
        }
    }

    private fun renderMembership(m: UserMembershipInfo?, snapshot: com.openvpn.client.api.UserMembershipSnapshot?) {
        val (label, active) = Labels.mineSubscriptionPill(m)
        binding.membershipPill.text = label
        binding.membershipPill.setBackgroundColor(if (active) Color.parseColor("#D1FAE5") else Color.parseColor("#F3F4F6"))
        binding.membershipPill.setTextColor(if (active) Color.parseColor("#059669") else Color.parseColor("#6B7280"))

        if (m == null) {
            binding.membershipDetails.isVisible = false
            binding.noMembershipText.isVisible = true
        } else {
            binding.noMembershipText.isVisible = false
            binding.membershipDetails.isVisible = true
            bindKv(binding.kvTier, "档位", Labels.planTierLabel(m.planTier))
            bindKv(binding.kvExpiry, "到期时间", Labels.formatMinePageMembershipExpiry(m))
            bindKv(binding.kvRemaining, "剩余", Labels.mineSubscriptionRemainingLabel(m))
            bindKv(binding.kvInvited, "已邀请", "${snapshot?.inviteInvitedCount ?: 0} 人")
            bindKv(binding.kvRewardDays, "已获得", "${snapshot?.inviteRewardDaysEarned ?: 0} 天")
        }
    }

    private fun bindKv(kv: ViewKvRowBinding, label: String, value: String) {
        kv.kvLabel.text = label
        kv.kvValue.text = value
    }

    private fun showChangePasswordDialog() {
        ChangePasswordBottomSheet().show(parentFragmentManager, ChangePasswordBottomSheet.TAG)
    }

    private fun shareInvite() {
        val code = viewModel.profile.value?.inviteCode?.trim().orEmpty()
        if (code.length != 8) {
            Toasty.info(requireContext(), getString(R.string.invite_code_pending)).show()
            return
        }
        val text = "邀请你使用 OpenVPN，我的邀请码：$code"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share_invite)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
