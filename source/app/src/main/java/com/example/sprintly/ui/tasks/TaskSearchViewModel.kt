package com.example.sprintly.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.offline.WorkspaceSnapshots
import com.example.sprintly.tasks.Task
import com.example.sprintly.tasks.TaskRepository
import com.example.sprintly.tasks.TaskSearchPage
import com.example.sprintly.ui.workspace.accessLost
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.WorkspaceFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TaskFilters(
    val columnId: String? = null,
    val priority: String? = null,
    val assigneeId: String? = null,
)

internal data class SearchCriteria(val keyword: String = "", val filters: TaskFilters = TaskFilters()) {
    val title = keyword.trim()
    val valid = title.codePointCount(0, title.length) <= 150
}

internal data class SearchColumn(val id: String, val name: String)

internal data class SearchOptions(val project: String, val columns: List<SearchColumn>, val members: List<Member>)

internal data class TaskSearchState(
    val criteria: SearchCriteria,
    val options: SearchOptions? = null,
    val results: TaskSearchPage? = null,
    val failure: WorkspaceFailure? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
)

@HiltViewModel
internal class TaskSearchViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val tasks: TaskRepository,
    private val snapshots: WorkspaceSnapshots,
) : ViewModel() {
    val workspaceId = checkNotNull(saved.get<String>("workspaceId"))
    val projectId = checkNotNull(saved.get<String>("projectId"))
    private val mutableState = MutableStateFlow(
        TaskSearchState(
            SearchCriteria(
                keyword = saved["keyword"] ?: "",
                filters = TaskFilters(saved["column"], saved["priority"], saved["assignee"]),
            ),
        ),
    )
    val state = mutableState.asStateFlow()
    private var search: Job? = null
    private var available = false

    fun updateConnection(connected: Boolean, authenticated: Boolean) {
        if (!connected || !authenticated) {
            available = false
            search?.cancel()
            mutableState.value = mutableState.value.copy(
                options = null,
                results = null,
                loading = false,
                offline = !connected,
                failure = if (connected) WorkspaceFailure("AUTH_RETRY") else null,
            )
            return
        }
        available = true
        request(preserve = true)
    }

    fun editKeyword(value: String) = change(state.value.criteria.copy(keyword = value), debounce = true)

    fun setFilters(filters: TaskFilters) = change(state.value.criteria.copy(filters = filters))

    fun reset() = change(SearchCriteria())

    fun retry() = request(preserve = true)

    fun loadMore() {
        val results = state.value.results ?: return
        if (!state.value.loading && results.hasMore) request(append = true)
    }

    private fun change(criteria: SearchCriteria, debounce: Boolean = false) {
        if (criteria == state.value.criteria) return
        saved["keyword"] = criteria.keyword
        saved["column"] = criteria.filters.columnId
        saved["priority"] = criteria.filters.priority
        saved["assignee"] = criteria.filters.assigneeId
        mutableState.value = state.value.copy(criteria = criteria, results = null)
        request(debounce = debounce)
    }

    private fun request(debounce: Boolean = false, append: Boolean = false, preserve: Boolean = false) {
        search?.cancel()
        val previous = state.value
        if (!available || !previous.criteria.valid) {
            mutableState.value = previous.copy(results = null, loading = false)
            return
        }
        val keepResults = append || preserve
        mutableState.value = previous.copy(
            results = previous.results.takeIf { keepResults },
            loading = true,
            offline = false,
            failure = null,
        )
        search = viewModelScope.launch {
            val session = snapshots.sessionId() ?: return@launch
            try {
                if (debounce) delay(300)
                val options = if (preserve || previous.options == null) loadOptions() else previous.options
                val results = when {
                    append -> {
                        val current = checkNotNull(previous.results)
                        val next = page(previous.criteria, current.page + 1)
                        next.copy(tasks = (current.tasks + next.tasks).distinctBy(Task::id))
                    }
                    preserve && previous.results != null -> reloadVisiblePages(previous.criteria, previous.results.page)
                    else -> page(previous.criteria, 1)
                }
                if (snapshots.sessionId() == session) {
                    mutableState.value = state.value.copy(options = options, results = results, loading = false)
                }
            } catch (failure: WorkspaceFailure) {
                if (failure.accessLost) snapshots.removeProject(workspaceId, projectId, session)
                if (snapshots.sessionId() != session) return@launch
                mutableState.value = state.value.copy(
                    results = previous.results.takeIf { append && !failure.accessLost },
                    loading = false,
                    failure = failure,
                )
            }
        }
    }

    private suspend fun loadOptions(): SearchOptions = coroutineScope {
        val project = async { snapshots.refreshProject(workspaceId, projectId) }
        val columns = async {
            val board = snapshots.refreshBoard(workspaceId, projectId)
            board.columns.sortedBy { it.position }.map { SearchColumn(it.id, it.name) }
        }
        val members = async { snapshots.refreshMembers(workspaceId) }
        SearchOptions(project.await().name, columns.await(), members.await())
    }

    private suspend fun reloadVisiblePages(criteria: SearchCriteria, lastPage: Int): TaskSearchPage {
        var result = page(criteria, 1)
        while (result.page < lastPage && result.hasMore) {
            val next = page(criteria, result.page + 1)
            result = next.copy(tasks = (result.tasks + next.tasks).distinctBy(Task::id))
        }
        return result
    }

    private suspend fun page(criteria: SearchCriteria, number: Int): TaskSearchPage = tasks.listTasksPage(
        projectId = projectId,
        q = criteria.title.ifEmpty { null },
        columnId = criteria.filters.columnId,
        assigneeId = criteria.filters.assigneeId,
        priority = criteria.filters.priority,
        page = number,
    )
}
