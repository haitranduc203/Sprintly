package com.example.sprintly.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sprintly.R
import com.example.sprintly.databinding.FragmentTaskSearchBinding
import com.example.sprintly.databinding.ItemTaskSearchResultBinding
import com.example.sprintly.databinding.ItemWorkspaceColumnChipBinding
import com.example.sprintly.offline.OfflineConnectivity
import com.example.sprintly.tasks.Task
import com.example.sprintly.ui.workspace.accessLost
import com.example.sprintly.ui.workspace.bindRows
import com.example.sprintly.ui.workspace.collectScreen
import com.example.sprintly.ui.workspace.currentAccount
import com.example.sprintly.ui.workspace.goBackAfterAccessLoss
import com.example.sprintly.ui.workspace.refreshWithOfflineSession
import com.example.sprintly.ui.workspace.retryOfflineWorkspace
import com.example.sprintly.ui.workspace.showEmpty
import com.example.sprintly.ui.workspace.showError
import com.example.sprintly.ui.workspace.showLoading
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TaskSearchFragment : Fragment(R.layout.fragment_task_search) {
    private val model: TaskSearchViewModel by viewModels()
    @Inject internal lateinit var connectivity: OfflineConnectivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentTaskSearchBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.searchResultHeading, true)
        val adapter = SearchResults(::openTask)
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.searchResultRows.bindRows(adapter)
        binding.searchToolbar.setNavigationOnClickListener { back() }
        binding.searchOpenFilters.setOnClickListener { openFilters() }
        binding.searchMore.setOnClickListener { model.loadMore() }
        binding.searchKeyword.setText(model.state.value.criteria.keyword)
        binding.searchKeyword.doAfterTextChanged { model.editKeyword(it.toString()) }
        binding.searchKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            dismissKeyboard()
            true
        }

        refreshWithOfflineSession(view, connectivity) {
            model.updateConnection(connectivity.connected.value, currentAccount()?.needsRetry == false)
        }

        var lastOptions: Pair<SearchOptions?, TaskFilters>? = null
        var lastCriteria = model.state.value.criteria
        collectScreen(model.state) { state ->
            if (state.failure?.accessLost == true) {
                goBackAfterAccessLoss(R.id.workspace_detail_fragment)
                return@collectScreen
            }
            val inputs = state.options to state.criteria.filters
            if (lastOptions != inputs) {
                lastOptions = inputs
                bindFilters(binding, state)
            }
            if (state.criteria != lastCriteria) {
                lastCriteria = state.criteria
                binding.searchResultRows.scrollToPosition(0)
            }
            renderState(binding, adapter, state)
        }
    }

    private fun renderState(binding: FragmentTaskSearchBinding, adapter: SearchResults, state: TaskSearchState) {
        val results = state.results
        val failure = state.failure
        val columns = state.options?.columns.orEmpty().associate { it.id to it.name }
        val editable = !state.offline
        binding.searchKeyword.isEnabled = editable
        if (binding.searchKeyword.text.toString() != state.criteria.keyword) {
            binding.searchKeyword.setText(state.criteria.keyword)
            binding.searchKeyword.setSelection(state.criteria.keyword.length)
        }
        binding.searchField.error = if (state.criteria.valid) null else getString(R.string.task_search_too_long)
        binding.searchOpenFilters.isEnabled = editable && state.options != null
        binding.searchResultsHeader.isVisible = editable
        binding.searchResultCount.text = results?.let { getString(R.string.task_search_count, it.total) }.orEmpty()
        binding.searchMore.isVisible = results?.hasMore == true
        binding.searchMore.isEnabled = !state.loading
        binding.searchMore.setText(if (failure == null) R.string.task_search_more else R.string.task_search_more_failed)
        adapter.submitList(results?.tasks.orEmpty().map { SearchRow(it, columns[it.columnId]) })

        val status = binding.searchStatus
        when {
            state.offline -> {
                status.showEmpty(R.string.task_search_offline)
                status.statusRetry.setText(R.string.task_search_back_to_board)
                status.statusRetry.isVisible = true
                status.statusRetry.setOnClickListener { back() }
            }
            !state.criteria.valid -> status.root.isVisible = false
            failure != null && results == null -> {
                status.showError(failure)
                status.statusRetry.setText(R.string.auth_retry)
                status.statusRetry.setOnClickListener { retryOfflineWorkspace(connectivity, model::retry) }
            }
            state.loading && results == null -> status.showLoading()
            results != null && results.tasks.isEmpty() -> {
                status.showEmpty(R.string.task_search_empty)
                status.statusRetry.setText(R.string.task_search_reset)
                status.statusRetry.isVisible = state.criteria != SearchCriteria()
                status.statusRetry.setOnClickListener { model.reset() }
            }
            else -> status.root.isVisible = false
        }
    }

    private fun bindFilters(binding: FragmentTaskSearchBinding, state: TaskSearchState) {
        val chips = binding.searchFilterChips
        val options = state.options
        chips.removeAllViews()
        binding.searchToolbar.subtitle = options?.project
        if (options == null) return
        val filters = state.criteria.filters
        val activeFilters = listOf(
            filters.columnId?.let { id -> options.columns.firstOrNull { it.id == id }?.name } to R.string.task_search_column,
            filters.priority?.let { getString(taskPriorityLabel(it)) } to R.string.task_search_priority,
            filters.assigneeId?.let { id -> options.members.firstOrNull { it.id == id }?.displayName } to R.string.task_search_assignee,
        )
        activeFilters.forEach { (label, fallback) ->
            val chip = ItemWorkspaceColumnChipBinding.inflate(layoutInflater, chips, false).root
            chip.text = label ?: getString(fallback)
            chip.isChecked = label != null
            chip.setOnClickListener {
                chip.isChecked = label != null
                openFilters()
            }
            chips.addView(chip)
        }
        if (filters != TaskFilters()) {
            val chip = ItemWorkspaceColumnChipBinding.inflate(layoutInflater, chips, false).root
            chip.setText(R.string.task_search_clear_filters)
            chip.isCheckable = false
            chip.setOnClickListener { model.setFilters(TaskFilters()) }
            chips.addView(chip)
        }
    }

    private fun openFilters() {
        if (model.state.value.options == null) return
        if (childFragmentManager.findFragmentByTag("taskSearchFilters") != null) return
        dismissKeyboard()
        TaskSearchFiltersSheet.show(childFragmentManager, model.state.value.criteria.filters)
    }

    private fun openTask(task: Task) {
        val navigation = findNavController()
        if (navigation.currentDestination?.id != R.id.task_search_fragment) return
        dismissKeyboard()
        navigation.navigate(
            R.id.task_detail_fragment,
            Bundle().apply {
                putString("workspaceId", model.workspaceId)
                putString("projectId", model.projectId)
                putString("taskId", task.id)
            },
        )
    }

    private fun back() {
        dismissKeyboard()
        findNavController().popBackStack()
    }

    private fun dismissKeyboard() {
        view?.let { WindowCompat.getInsetsController(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
    }
}

private data class SearchRow(val task: Task, val column: String?)

private class SearchResults(private val open: (Task) -> Unit) :
    ListAdapter<SearchRow, SearchResults.Holder>(object : DiffUtil.ItemCallback<SearchRow>() {
        override fun areItemsTheSame(old: SearchRow, new: SearchRow) = old.task.id == new.task.id
        override fun areContentsTheSame(old: SearchRow, new: SearchRow) = old == new
    }) {

    class Holder(val binding: ItemTaskSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTaskSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val task = row.task
        val binding = holder.binding
        val context = binding.root.context
        val assignee = task.assignee?.displayName ?: context.getString(R.string.task_unassigned)
        binding.searchResultTitle.text = task.title
        binding.searchResultMetadata.text =
            row.column?.let { context.getString(R.string.task_search_result_meta, it, assignee) } ?: assignee
        binding.searchResultPriority.setText(taskPriorityLabel(task.priority))
        binding.searchResultPriority.applyTaskPriorityColors(task.priority)
        binding.root.setOnClickListener { open(task) }
    }
}
