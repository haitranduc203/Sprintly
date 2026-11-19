package com.example.sprintly.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import com.example.sprintly.R
import com.example.sprintly.databinding.DialogTaskAssigneeBinding
import com.example.sprintly.databinding.SheetTaskSearchFiltersBinding
import com.example.sprintly.ui.workspace.collectScreen
import com.example.sprintly.workspace.Member
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TaskSearchFiltersSheet : BottomSheetDialogFragment() {
    private val model: TaskSearchViewModel by viewModels({ requireParentFragment() })
    private lateinit var draft: TaskFilters
    private var picker: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: requireArguments()
        draft = TaskFilters(source.getString("column"), source.getString("priority"), source.getString("assignee"))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetTaskSearchFiltersBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = SheetTaskSearchFiltersBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.searchFiltersHeading, true)
        binding.searchApplyFilters.setOnClickListener {
            model.setFilters(draft)
            dismiss()
        }
        binding.searchClearFilters.setOnClickListener {
            draft = TaskFilters()
            model.setFilters(draft)
            dismiss()
        }
        collectScreen(model.state) { state ->
            if (state.offline) dismiss()
            bindOptions(binding, state.options)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putAll(draft.toArgs())
    }

    override fun onDestroyView() {
        picker?.dismiss()
        picker = null
        super.onDestroyView()
    }

    private fun bindOptions(binding: SheetTaskSearchFiltersBinding, options: SearchOptions?) {
        val columns = options?.columns.orEmpty()
        val members = options?.members.orEmpty()
        val column = columns.firstOrNull { it.id == draft.columnId }?.name
        val priority = draft.priority?.let { getString(taskPriorityLabel(it)) }
        val assignee = members.firstOrNull { it.id == draft.assigneeId }?.displayName
        binding.searchPickColumn.text = choice(R.string.task_search_column, column, R.string.task_search_any_column)
        binding.searchPickPriority.text = choice(R.string.task_search_priority, priority, R.string.task_search_any_priority)
        binding.searchPickAssignee.text = choice(R.string.task_search_assignee, assignee, R.string.task_search_any_assignee)
        binding.searchPickColumn.isEnabled = options != null
        binding.searchPickAssignee.isEnabled = options != null
        binding.searchApplyFilters.isEnabled = options != null
        binding.searchPickColumn.setOnClickListener {
            choose(R.string.task_search_column, R.string.task_search_any_column, columns.map { it.name }) { index ->
                draft = draft.copy(columnId = columns.getOrNull(index)?.id)
                bindOptions(binding, options)
            }
        }
        binding.searchPickPriority.setOnClickListener {
            val priorities = taskPriorities.asReversed()
            val labels = priorities.map { getString(taskPriorityLabel(it)) }
            choose(R.string.task_search_priority, R.string.task_search_any_priority, labels) { index ->
                draft = draft.copy(priority = priorities.getOrNull(index))
                bindOptions(binding, options)
            }
        }
        binding.searchPickAssignee.setOnClickListener {
            chooseAssignee(members) { member ->
                draft = draft.copy(assigneeId = member?.id)
                bindOptions(binding, options)
            }
        }
    }

    private fun choice(label: Int, value: String?, all: Int): String =
        getString(R.string.task_search_filter_choice, getString(label), value ?: getString(all))

    private fun choose(label: Int, all: Int, values: List<String>, selected: (Int) -> Unit) {
        picker = MaterialAlertDialogBuilder(requireContext())
            .setTitle(label)
            .setItems((listOf(getString(all)) + values).toTypedArray()) { _, index -> selected(index - 1) }
            .setNegativeButton(R.string.workspace_cancel, null)
            .show()
    }

    private fun chooseAssignee(members: List<Member>, selected: (Member?) -> Unit) {
        val binding = DialogTaskAssigneeBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_search_assignee)
            .setView(binding.root)
            .setNeutralButton(R.string.task_search_any_assignee) { _, _ -> selected(null) }
            .setNegativeButton(R.string.workspace_cancel, null)
            .create()
        val adapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        binding.taskAssigneeResults.adapter = adapter
        var matches = members
        fun filter(query: String) {
            matches = members.filter { it.displayName.contains(query, true) || it.email.contains(query, true) }
            adapter.clear()
            adapter.addAll(matches.map {
                getString(R.string.task_search_result_meta, it.displayName, it.email)
            })
            binding.taskAssigneeEmpty.isVisible = matches.isEmpty()
        }
        binding.taskAssigneeSearch.doAfterTextChanged { filter(it.toString().trim()) }
        binding.taskAssigneeResults.setOnItemClickListener { _, _, index, _ ->
            selected(matches[index])
            dialog.dismiss()
        }
        filter("")
        picker = dialog
        dialog.show()
    }

    internal companion object {
        fun show(manager: FragmentManager, filters: TaskFilters) = TaskSearchFiltersSheet().apply {
            arguments = filters.toArgs()
        }.showNow(manager, "taskSearchFilters")

        private fun TaskFilters.toArgs() = bundleOf("column" to columnId, "priority" to priority, "assignee" to assigneeId)
    }
}
