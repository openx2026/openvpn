package com.openvpn.client.ui.mine

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.openvpn.client.R
import com.openvpn.client.api.CatalogCampaign
import com.openvpn.client.api.UserMembershipInfo
import com.openvpn.client.databinding.FragmentMineBinding
import com.openvpn.client.databinding.ViewCampaignRuleItemBinding
import com.openvpn.client.databinding.ViewKvRowBinding
import com.openvpn.client.ui.PortalViewModel
import com.openvpn.client.util.ClipboardUtil
import com.openvpn.client.util.Labels
import es.dmoral.toasty.Toasty

class MineFragment : Fragment() {
    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()
    private var campaignRulesExpanded = false

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
        binding.campaignRulesToggle.setOnClickListener { toggleCampaignRules() }

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
        viewModel.activeCampaigns.observe(viewLifecycleOwner) { campaigns ->
            renderCampaignRules(campaigns)
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

    private fun toggleCampaignRules() {
        campaignRulesExpanded = !campaignRulesExpanded
        updateCampaignRulesExpandedUi()
    }

    private fun updateCampaignRulesExpandedUi() {
        binding.campaignRulesContent.isVisible = campaignRulesExpanded
        binding.campaignRulesDivider.isVisible = campaignRulesExpanded
        binding.campaignRulesChevron.animate()
            .rotation(if (campaignRulesExpanded) 180f else 0f)
            .setDuration(150)
            .start()
    }

    private fun renderCampaignRules(campaigns: List<CatalogCampaign>) {
        val items = campaigns
            .sortedBy { it.id }
            .filter { it.remark?.trim().orEmpty().isNotEmpty() }

        binding.campaignRulesContent.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        if (items.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.campaign_rules_empty)
                setTextColor(requireContext().getColor(R.color.portal_text_secondary))
                textSize = 13f
                setLineSpacing(0f, 1.45f)
                setBackgroundResource(R.drawable.bg_campaign_rules_item)
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            binding.campaignRulesContent.addView(empty)
        } else {
            items.forEachIndexed { index, campaign ->
                val itemBinding = ViewCampaignRuleItemBinding.inflate(inflater, binding.campaignRulesContent, false)
                itemBinding.campaignRuleText.text = campaign.remark!!.trim()
                if (index == 0) {
                    itemBinding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = 0
                    }
                }
                binding.campaignRulesContent.addView(itemBinding.root)
            }
        }

        binding.campaignRulesChevron.rotation = if (campaignRulesExpanded) 180f else 0f
        binding.campaignRulesContent.isVisible = campaignRulesExpanded
        binding.campaignRulesDivider.isVisible = campaignRulesExpanded
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
