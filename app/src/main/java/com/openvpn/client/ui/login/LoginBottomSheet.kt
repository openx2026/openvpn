package com.openvpn.client.ui.login

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.openvpn.client.R
import com.openvpn.client.databinding.BottomSheetLoginBinding
import com.openvpn.client.ui.PortalViewModel

class LoginBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()

    private var isRegister = false
    private var onSuccess: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString(ARG_INITIAL_MESSAGE)?.let { showError(it) }

        binding.authTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isRegister = tab?.position == 1
                binding.inviteLayout.isVisible = isRegister
                binding.confirmPasswordLayout.isVisible = isRegister
                binding.submitButton.setText(if (isRegister) R.string.action_register else R.string.action_login)
                if (!isRegister) {
                    binding.inputConfirmPassword.text?.clear()
                }
                hideError()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.submitButton.setOnClickListener { submit() }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.submitButton.isEnabled = !loading
            binding.cancelButton.isEnabled = !loading
            binding.inputUsername.isEnabled = !loading
            binding.inputPassword.isEnabled = !loading
            binding.inputConfirmPassword.isEnabled = !loading
            binding.inputInvite.isEnabled = !loading
            binding.submitButton.text = getString(
                if (loading) R.string.action_submitting
                else if (isRegister) R.string.action_register
                else R.string.action_login,
            )
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            if (!err.isNullOrBlank()) showError(err)
        }
    }

    private fun submit() {
        hideError()
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        val confirmPassword = binding.inputConfirmPassword.text?.toString().orEmpty()
        val invite = binding.inputInvite.text?.toString()?.trim()
        val onDone = {
            onSuccess?.invoke()
            dismiss()
        }
        if (isRegister) {
            if (password != confirmPassword) {
                showError(getString(R.string.register_password_mismatch))
                return
            }
            viewModel.register(username, password, invite, onDone)
        } else {
            viewModel.login(username, password, onDone)
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.isVisible = true
        binding.errorText.setTextColor(Color.parseColor("#DC2626"))
    }

    private fun hideError() {
        binding.errorText.isVisible = false
        viewModel.clearError()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LoginBottomSheet"
        private const val ARG_INITIAL_MESSAGE = "initial_message"

        fun show(
            fragmentManager: FragmentManager,
            initialMessage: String? = null,
            onSuccess: (() -> Unit)? = null,
        ) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            LoginBottomSheet().apply {
                arguments = bundleOf(ARG_INITIAL_MESSAGE to initialMessage)
                this.onSuccess = onSuccess
            }.show(fragmentManager, TAG)
        }
    }
}
