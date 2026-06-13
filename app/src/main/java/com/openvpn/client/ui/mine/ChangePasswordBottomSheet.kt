package com.openvpn.client.ui.mine

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.openvpn.client.R
import com.openvpn.client.databinding.BottomSheetChangePasswordBinding
import com.openvpn.client.ui.PortalViewModel

class ChangePasswordBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetChangePasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PortalViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetChangePasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val clearErrorWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                hideError()
            }
        }
        binding.inputCurrentPassword.addTextChangedListener(clearErrorWatcher)
        binding.inputNewPassword.addTextChangedListener(clearErrorWatcher)
        binding.inputConfirmPassword.addTextChangedListener(clearErrorWatcher)

        binding.inputConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.confirmButton.setOnClickListener { submit() }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.confirmButton.isEnabled = !loading
            binding.cancelButton.isEnabled = !loading
            binding.inputCurrentPassword.isEnabled = !loading
            binding.inputNewPassword.isEnabled = !loading
            binding.inputConfirmPassword.isEnabled = !loading
            binding.confirmButton.text = if (loading) {
                getString(R.string.action_submitting)
            } else {
                getString(R.string.action_confirm)
            }
        }
    }

    private fun submit() {
        hideError()
        val current = binding.inputCurrentPassword.text?.toString().orEmpty()
        val newPassword = binding.inputNewPassword.text?.toString().orEmpty()
        val confirm = binding.inputConfirmPassword.text?.toString().orEmpty()

        when {
            current.isBlank() -> {
                showError(getString(R.string.change_password_current_required))
                binding.inputCurrentPassword.requestFocus()
            }
            newPassword.length < 7 -> {
                showError(getString(R.string.login_hint))
                binding.inputNewPassword.requestFocus()
            }
            newPassword != confirm -> {
                showError(getString(R.string.change_password_mismatch))
                binding.inputConfirmPassword.requestFocus()
            }
            else -> {
                viewModel.changePassword(current, newPassword) {
                    dismiss()
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.isVisible = true
    }

    private fun hideError() {
        binding.errorText.isVisible = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ChangePasswordBottomSheet"
    }
}
