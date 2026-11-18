package com.example.sprintly.ui

import android.os.Bundle
import android.view.View
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.sprintly.R
import com.example.sprintly.auth.AuthState
import com.example.sprintly.databinding.FragmentAuthFormBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AuthFormFragment : Fragment(R.layout.fragment_auth_form) {
    private val form by lazy { ViewModelProvider(this)[AuthFormViewModel::class.java] }
    private val isRegistration get() = arguments?.getBoolean("registration") == true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentAuthFormBinding.bind(view)
        val session = ViewModelProvider(requireActivity())[SessionViewModel::class.java]
        ViewCompat.setAccessibilityHeading(binding.authTitle, true)
        configureMode(binding)

        val backGuard = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, false) {}
        binding.upButton.setOnClickListener { switchForm(binding) }
        binding.switchForm.setOnClickListener { switchForm(binding) }
        binding.submitButton.setOnClickListener { submit(binding) }
        val lastField = if (isRegistration) binding.confirmInput else binding.passwordInput
        lastField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                submit(binding)
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    form.state.collect { submission ->
                        backGuard.isEnabled = submission.isRunning
                        renderSubmission(binding, submission)
                    }
                }
                launch {
                    session.state.collect { state ->
                        binding.expiredNotice.isVisible = !isRegistration &&
                            state is AuthState.SignedOut && state.expired
                    }
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val binding = FragmentAuthFormBinding.bind(requireView())
        with(binding) {
            listOf(displayNameLayout, emailLayout, passwordLayout, confirmLayout).forEach { field ->
                field.editText?.doAfterTextChanged {
                    field.error = null
                    if (field == passwordLayout) confirmLayout.error = null
                    form.clearError()
                }
            }
        }
    }

    private fun configureMode(binding: FragmentAuthFormBinding) = with(binding) {
        if (resources.configuration.fontScale > 1.3f) {
            footer.orientation = LinearLayout.VERTICAL
        }
        if (!isRegistration) return@with

        upButton.isVisible = true
        illustration.isVisible = false
        displayNameLayout.isVisible = true
        confirmLayout.isVisible = true
        authTitle.setText(R.string.auth_create_account)
        authDescription.setText(R.string.auth_register_description)
        switchPrompt.setText(R.string.auth_has_account)
        switchForm.setText(R.string.auth_login)
        emailInput.setAutofillHints("newUsername", View.AUTOFILL_HINT_EMAIL_ADDRESS)
        passwordInput.setAutofillHints("newPassword")
        passwordInput.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        passwordInput.nextFocusForwardId = R.id.confirm_input
        passwordLayout.helperText = getString(R.string.auth_password_rules)
        submitButton.backgroundTintList =
            AppCompatResources.getColorStateList(requireContext(), R.color.auth_create_button_background)
        val textTint = AppCompatResources.getColorStateList(requireContext(), R.color.auth_create_button_text)
        submitButton.setTextColor(textTint)
        submitButton.iconTint = textTint
    }

    private fun switchForm(binding: FragmentAuthFormBinding) {
        if (form.state.value.isRunning) return
        ViewCompat.getWindowInsetsController(binding.root)?.hide(WindowInsetsCompat.Type.ime())
        requireContext().getSystemService(AutofillManager::class.java)?.cancel()
        val nav = findNavController()
        when (nav.currentDestination?.id) {
            R.id.register_fragment -> nav.popBackStack()
            R.id.login_fragment -> nav.navigate(R.id.register_fragment)
        }
    }

    private fun submit(binding: FragmentAuthFormBinding) = with(binding) {
        if (form.state.value.isRunning) return@with
        form.clearError()

        val displayName = displayNameInput.text?.toString()?.trim().orEmpty()
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        val confirm = confirmInput.text?.toString().orEmpty()

        if (isRegistration) {
            displayNameLayout.error = when {
                displayName.isEmpty() -> getString(R.string.auth_name_required)
                Character.codePointCount(displayName, 0, displayName.length) > 100 ->
                    getString(R.string.auth_name_too_long)
                displayName.any(Char::isISOControl) -> getString(R.string.auth_name_invalid)
                else -> null
            }
            confirmLayout.error = when {
                confirm.isEmpty() -> getString(R.string.auth_confirmation_required)
                confirm != password -> getString(R.string.auth_mismatched_passwords)
                else -> null
            }
        }
        emailLayout.error = when {
            email.isEmpty() -> getString(R.string.auth_email_required)
            !isValidEmail(email) ->
                getString(R.string.auth_email_invalid)
            else -> null
        }
        passwordLayout.error = when {
            password.isEmpty() -> getString(R.string.auth_password_required)
            password.toByteArray(Charsets.UTF_8).size > 72 -> getString(R.string.auth_password_too_long)
            isRegistration && Character.codePointCount(password, 0, password.length) < 8 ->
                getString(R.string.auth_password_too_short)
            else -> null
        }

        val firstInvalidField = listOf(displayNameLayout, emailLayout, passwordLayout, confirmLayout)
            .firstOrNull { it.isVisible && it.error != null }
        if (firstInvalidField != null) {
            firstInvalidField.editText?.requestFocus()
            return@with
        }

        ViewCompat.getWindowInsetsController(root)?.hide(WindowInsetsCompat.Type.ime())
        form.submit(email, password, displayName.takeIf { isRegistration })
    }

    private fun isValidEmail(email: String): Boolean {
        val normalized = email.lowercase()
        val at = normalized.indexOf('@')
        return normalized.length <= 254 && at in 1..64 &&
            emailLocalPattern.matches(normalized.substring(0, at)) &&
            emailDomainPattern.matches(normalized.substring(at + 1))
    }

    private fun renderSubmission(
        binding: FragmentAuthFormBinding,
        submission: AuthFormViewModel.Submission,
    ) = with(binding) {
        val enabled = !submission.isRunning
        listOf(displayNameLayout, emailLayout, passwordLayout, confirmLayout, upButton, switchForm,
            submitButton).forEach { it.isEnabled = enabled }
        loading.isVisible = submission.isRunning

        val code = submission.errorCode
        val error = when (code) {
            null, "EMAIL_ALREADY_REGISTERED" -> null
            "INVALID_CREDENTIALS" -> R.string.auth_invalid_credentials
            "VALIDATION_ERROR" -> R.string.auth_invalid_form
            "RATE_LIMITED" -> R.string.auth_rate_limited
            "NETWORK_ERROR" -> if (isRegistration) R.string.auth_offline_register else R.string.auth_offline_login
            else -> R.string.auth_server_error
        }
        formNotice.isVisible = error != null
        if (error != null) formNotice.setText(error)
        if (code == "EMAIL_ALREADY_REGISTERED") {
            emailLayout.error = getString(R.string.auth_email_taken)
            emailInput.requestFocus()
        }
        submitButton.setText(when {
            code == "NETWORK_ERROR" || code == "SERVER_ERROR" -> R.string.auth_retry
            isRegistration -> R.string.auth_create_account
            else -> R.string.auth_login
        })
    }

    private companion object {
        val emailLocalPattern = Regex("[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*")
        val emailDomainPattern = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    }
}
