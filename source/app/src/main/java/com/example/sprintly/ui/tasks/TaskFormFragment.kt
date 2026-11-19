package com.example.sprintly.ui.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Rect
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.activity.addCallback
import androidx.core.content.getSystemService
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.sprintly.R
import com.example.sprintly.databinding.DialogTaskAssigneeBinding
import com.example.sprintly.databinding.FragmentTaskFormBinding
import com.example.sprintly.offline.OfflineConnectivity
import com.example.sprintly.ui.workspace.accessLost
import com.example.sprintly.ui.workspace.collectScreen
import com.example.sprintly.ui.workspace.currentAccount
import com.example.sprintly.ui.workspace.goBackAfterAccessLoss
import com.example.sprintly.ui.workspace.refreshWithOfflineSession
import com.example.sprintly.ui.workspace.retryOfflineWorkspace
import com.example.sprintly.ui.workspace.showCached
import com.example.sprintly.ui.workspace.showError
import com.example.sprintly.ui.workspace.showLoading
import com.example.sprintly.ui.workspace.showNotCached
import com.example.sprintly.ui.workspace.temporaryReadFailure
import com.example.sprintly.workspace.Member
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class TaskFormFragment : Fragment(R.layout.fragment_task_form) {
    private val model: TaskFormViewModel by viewModels()
    @Inject internal lateinit var connectivity: OfflineConnectivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentTaskFormBinding.bind(view)
        var rendering = false
        var renderedOptions: TaskFormOptions? = null

        binding.taskToolbar.setTitle(if (model.editing) R.string.task_edit else R.string.task_create)
        binding.taskToolbar.setNavigationOnClickListener { closeForm() }
        binding.taskCancel.setOnClickListener { closeForm() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { closeForm() }
        val retry = { retryOfflineWorkspace(connectivity, model::load) }
        binding.taskFormStatus.statusRetry.setOnClickListener { retry() }
        binding.taskTitleInput.doAfterTextChanged { text ->
            if (!rendering) model.edit { it.copy(title = text.toString()) }
        }
        binding.taskDescriptionInput.doAfterTextChanged { text ->
            if (!rendering) model.edit { it.copy(description = text?.toString()?.ifEmpty { null }) }
        }
        binding.taskDueButton.setOnClickListener { chooseDueDate() }
        binding.taskAssigneeButton.setOnClickListener {
            model.state.value.options?.let { chooseAssignee(it.members) }
        }
        binding.taskClearDue.setOnClickListener { model.edit { it.copy(dueAt = null) } }
        binding.taskSubmit.setOnClickListener {
            if (!connectivity.isConnected() || currentAccount()?.needsRetry != false) return@setOnClickListener
            requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view.windowToken, 0)
            model.submit()
        }

        collectScreen(model.state.combine(connectivity.connected) { state, online -> state to online }) { (state, online) ->
            state.result?.let { result ->
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.task_form_fragment) {
                    if (!model.editing) nav.previousBackStackEntry?.savedStateHandle?.apply {
                        set("boardCreatedTaskId", result.id)
                        set("boardCreatedColumnId", result.columnId)
                    }
                    nav.popBackStack()
                }
                return@collectScreen
            }
            val loadFailure = state.loadFailure
            if (loadFailure?.accessLost == true) {
                goBackAfterAccessLoss(R.id.workspace_board_fragment)
                return@collectScreen
            }
            val options = state.options
            val draft = state.draft
            val needsRetry = currentAccount()?.needsRetry != false
            val readOnly = !online || state.cached || needsRetry
            binding.taskFormScroll.isVisible = options != null && (loadFailure == null || loadFailure.temporaryReadFailure)
            binding.taskFormActions.isVisible = binding.taskFormScroll.isVisible
            val showBanner = !online || needsRetry || state.cached && loadFailure != null
            binding.taskOfflineBanner.showCached(binding.taskFormScroll.isVisible && showBanner, online, retry)
            when {
                binding.taskFormScroll.isVisible -> binding.taskFormStatus.root.isVisible = false
                !online -> binding.taskFormStatus.showNotCached()
                loadFailure != null -> binding.taskFormStatus.showError(loadFailure)
                options == null -> binding.taskFormStatus.showLoading()
                else -> binding.taskFormStatus.root.isVisible = false
            }
            if (options == null || draft == null) return@collectScreen
            rendering = true
            if (options != renderedOptions) {
                renderedOptions = options
                binding.taskFormProject.text = options.projectName
                binding.taskFormWorkspace.text = options.workspaceName
                binding.taskColumnInput.configureDropdown(options.board.columns.map { it.name }) { index ->
                    model.edit { it.copy(columnId = options.board.columns[index].id) }
                }
                binding.taskPriorityInput.configureDropdown(taskPriorities.map { getString(taskPriorityLabel(it)) }) { index ->
                    model.edit { it.copy(priority = taskPriorities[index]) }
                }
            }
            binding.taskTitleInput.syncText(draft.title)
            binding.taskDescriptionInput.syncText(draft.description.orEmpty())
            val columnName = options.board.columns.firstOrNull { it.id == draft.columnId }?.name
            binding.taskColumnInput.setText(columnName.orEmpty(), false)
            binding.taskPriorityInput.setText(getString(taskPriorityLabel(draft.priority)), false)
            val assignee = options.members.firstOrNull { it.id == draft.assigneeId }?.displayName
            binding.taskAssigneeButton.text = when {
                draft.assigneeId == null -> getString(R.string.task_unassigned)
                assignee == null && readOnly -> getString(R.string.task_assignee_not_cached)
                assignee == null -> getString(R.string.task_assignee_removed)
                else -> assignee
            }
            binding.taskDueButton.text = draft.dueAt?.let(requireContext()::taskDateTime)
                ?: getString(R.string.task_choose_due)
            binding.taskClearDue.isVisible = draft.dueAt != null
            listOf(
                binding.taskTitleInput, binding.taskDescriptionInput, binding.taskColumnInput,
                binding.taskPriorityInput, binding.taskAssigneeButton, binding.taskDueButton, binding.taskClearDue,
            ).forEach { it.isEnabled = state.editable }
            binding.taskCancel.isEnabled = !state.saving
            binding.taskSubmit.isEnabled = state.canSubmit && !readOnly
            binding.taskSubmit.setText(
                when {
                    state.saving -> R.string.task_sending
                    state.uncertain -> R.string.auth_retry
                    model.editing -> R.string.task_save
                    else -> R.string.task_create
                },
            )
            val titleError = if (state.inputError == R.string.task_title_length) getString(R.string.task_title_length) else null
            if (binding.taskTitleLayout.error != titleError) {
                binding.taskTitleLayout.error = titleError
                if (titleError != null) binding.taskTitleLayout.doOnLayout {
                    it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), false)
                }
            }
            binding.taskFormError.isVisible = state.inputError != null && binding.taskTitleLayout.error == null
            state.inputError?.let(binding.taskFormError::setText)
            rendering = false
        }
        refreshWithOfflineSession(view, connectivity, model::load)
    }

    private fun closeForm() {
        val state = model.state.value
        if (state.saving) return
        if (!state.dirty) {
            findNavController().navigateUp()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_discard_title)
            .setMessage(if (state.uncertain) R.string.task_discard_uncertain else R.string.task_discard_message)
            .setNegativeButton(R.string.task_keep_editing, null)
            .setPositiveButton(R.string.task_discard) { _, _ -> findNavController().navigateUp() }
            .show()
    }

    private fun chooseDueDate() {
        val context = requireContext()
        val zone = ZoneId.systemDefault()
        val initial = model.state.value.draft?.dueAt?.let { Instant.parse(it).atZone(zone) }
            ?: ZonedDateTime.now(zone)
        DatePickerDialog(context, { _, year, month, day ->
            val selection = LocalDate.of(year, month + 1, day)
            TimePickerDialog(context, { _, hour, minute ->
                val dateTime = selection.atTime(LocalTime.of(hour, minute)).atZone(zone)
                model.edit { it.copy(dueAt = dateTime.toInstant().toString()) }
            }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun chooseAssignee(members: List<Member>) {
        val binding = DialogTaskAssigneeBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_assignee)
            .setView(binding.root)
            .setNeutralButton(R.string.task_unassigned) { _, _ -> model.edit { it.copy(assigneeId = null) } }
            .setNegativeButton(R.string.workspace_cancel, null)
            .create()
        val adapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        binding.taskAssigneeResults.adapter = adapter
        var matches = members
        fun filter(query: String) {
            matches = members.filter {
                it.displayName.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true)
            }
            adapter.clear()
            adapter.addAll(matches.map { "${it.displayName} · ${it.email}" })
            binding.taskAssigneeEmpty.isVisible = matches.isEmpty()
        }
        binding.taskAssigneeSearch.doAfterTextChanged { filter(it.toString().trim()) }
        binding.taskAssigneeResults.setOnItemClickListener { _, _, index, _ ->
            val member = matches[index]
            model.edit { it.copy(assigneeId = member.id) }
            dialog.dismiss()
        }
        filter("")
        dialog.show()
    }

    private fun AutoCompleteTextView.configureDropdown(labels: List<String>, selected: (Int) -> Unit) {
        setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels))
        setOnItemClickListener { _, _, index, _ -> selected(index) }
    }

    private fun EditText.syncText(value: String) {
        if (text.toString() != value) setText(value)
    }
}
