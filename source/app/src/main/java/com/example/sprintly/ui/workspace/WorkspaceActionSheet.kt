package com.example.sprintly.ui.workspace

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.example.sprintly.R
import com.example.sprintly.auth.AuthState
import com.example.sprintly.databinding.DialogWorkspaceActionBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class WorkspaceActionSheet : BottomSheetDialogFragment() {
    private val model: WorkspaceActionViewModel by viewModels()
    private val action: WorkspaceAction get() = requireArguments().actionKind()
    private val accountId: String? get() = requireArguments().getString(WorkspaceAction.ACCOUNT_ID)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        DialogWorkspaceActionBinding.inflate(inflater, container, false).root

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as BottomSheetDialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.behavior.isDraggable = false
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (currentAccount()?.user?.id != accountId) dialog.hide()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = DialogWorkspaceActionBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.actionTitle, true)
        val startName = requireArguments().getString(WorkspaceAction.NAME).orEmpty()
        val startDescription = requireArguments().getString(WorkspaceAction.DESCRIPTION).orEmpty()
        val isMemberLookup = action == WorkspaceAction.AddMember
        val isConfirmation = action.consequences != null

        binding.actionTitle.text = if (isConfirmation) getString(action.title, startName) else getString(action.title)
        action.consequences?.let(binding.actionMessage::setText)
        binding.actionMessage.isVisible = isConfirmation
        binding.actionName.isVisible = action.field != null
        action.field?.let { binding.actionName.hint = getString(it) }
        binding.actionDescription.isVisible = action.hasDescription
        binding.actionSubmit.setText(action.submit)
        binding.memberLookup.isVisible = isMemberLookup
        if (isMemberLookup) {
            binding.actionNameInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        if (isConfirmation) {
            binding.actionSubmit.backgroundTintList = requireContext().tint(R.color.orbix_error_container)
            binding.actionSubmit.setTextColor(requireContext().getColor(R.color.orbix_error))
        }
        binding.actionNameInput.setText(startName)
        binding.actionDescriptionInput.setText(startDescription)
        binding.actionNameInput.doAfterTextChanged { text ->
            binding.actionName.error = null
            if (isMemberLookup) model.emailChanged(text.toString())
        }
        binding.memberLookup.setOnClickListener {
            if (currentAccount()?.needsRetry != false) return@setOnClickListener
            val email = binding.actionNameInput.text?.toString()?.trim().orEmpty()
            when {
                email.isEmpty() -> showNameError(binding, R.string.auth_email_required)
                email.length > 254 || !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    showNameError(binding, R.string.auth_email_invalid)
                else -> {
                    hideKeyboard(view)
                    model.lookup(email)
                }
            }
        }
        binding.memberCandidate.setOnClickListener {
            model.selectCandidate(binding.memberCandidate.isChecked)
        }
        binding.actionSubmit.setOnClickListener {
            if (currentAccount()?.needsRetry != false) return@setOnClickListener
            val name = binding.actionNameInput.text?.toString()?.trim().orEmpty()
            val nameError = when {
                action.field == null || isMemberLookup -> null
                name.isEmpty() -> R.string.workspace_name_required
                name.codePointCount(0, name.length) > MAX_NAME_LENGTH -> R.string.workspace_name_length
                INVALID_NAME.containsMatchIn(name) -> R.string.workspace_input_invalid
                else -> null
            }
            if (nameError != null) {
                showNameError(binding, nameError)
            } else {
                hideKeyboard(view)
                val description = binding.actionDescriptionInput.text?.toString()?.trim()?.ifEmpty { null }
                model.submit(name, description)
            }
        }
        val close = {
            if (!model.state.value.busy) {
                val changed = !isConfirmation &&
                    (binding.actionNameInput.text.toString() != startName ||
                        binding.actionDescriptionInput.text.toString() != startDescription)
                if (changed) confirmDiscard() else dismiss()
            }
        }
        binding.actionCancel.setOnClickListener { close() }
        (requireDialog() as BottomSheetDialog).onBackPressedDispatcher.addCallback(viewLifecycleOwner) { close() }
        binding.actionRetrySession.setOnClickListener { sessionViewModel().restore() }

        val updates = combine(model.state, sessionViewModel().state, ::Pair)
        collectScreen(updates) { (state, auth) ->
            when {
                auth == AuthState.Checking -> requireDialog().hide()
                auth !is AuthState.SignedIn || auth.user.id != accountId -> dismiss()
                state.outcome != null -> finish(state.outcome)
                else -> {
                    requireDialog().show()
                    render(binding, state, auth.needsRetry)
                }
            }
        }
    }

    private fun render(
        binding: DialogWorkspaceActionBinding,
        state: WorkspaceActionState,
        needsRetry: Boolean,
    ) {
        binding.actionProgress.isVisible = state.busy
        binding.actionNameInput.isEnabled = !state.busy
        binding.actionDescriptionInput.isEnabled = !state.busy
        binding.memberLookup.isEnabled = !state.busy && !needsRetry
        binding.memberCandidate.isVisible = state.candidate != null
        binding.memberCandidate.isEnabled = !state.busy
        binding.memberCandidate.isChecked = state.candidateSelected
        binding.memberCandidate.text = state.candidate?.let { "${it.displayName}\n${it.email}" }
        binding.actionSubmit.isEnabled = !state.busy && !needsRetry &&
            (action != WorkspaceAction.AddMember || state.candidateSelected)
        binding.actionCancel.isEnabled = !state.busy
        binding.actionRetrySession.isVisible = needsRetry
        val error = if (needsRetry) R.string.auth_refresh_unavailable_description else state.error
        binding.actionError.isVisible = error != null
        error?.let(binding.actionError::setText)
    }

    private fun showNameError(binding: DialogWorkspaceActionBinding, error: Int) {
        binding.actionName.error = if (error == R.string.workspace_name_length) {
            getString(error, MAX_NAME_LENGTH)
        } else {
            getString(error)
        }
        binding.actionNameInput.requestFocus()
    }

    private fun hideKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workspace_discard_question)
            .setNegativeButton(R.string.workspace_continue_editing, null)
            .setPositiveButton(R.string.workspace_discard) { _, _ -> dismiss() }
            .show()
    }

    private fun finish(outcome: ActionOutcome) {
        val manager = parentFragmentManager
        val kind = action.name
        dismiss()
        manager.setFragmentResult(
            WorkspaceAction.KEY,
            bundleOf(
                WorkspaceAction.KEY to kind,
                WorkspaceAction.SUCCEEDED to outcome.succeeded,
                WorkspaceAction.RESULT_ID to outcome.id,
            ),
        )
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100
        val INVALID_NAME = Regex("[\\p{Cc}\\p{Cs}]")
    }
}
