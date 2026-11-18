package com.example.sprintly.ui.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.tasks.TaskRepository
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace
import com.example.sprintly.workspace.WorkspaceFailure
import com.example.sprintly.workspace.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>
    data class Ready<T>(val data: T) : ScreenState<T>
    data class Failed(val error: WorkspaceFailure) : ScreenState<Nothing>
}

internal abstract class WorkspaceScreenViewModel<T> : ViewModel() {
    private val screenState = MutableStateFlow<ScreenState<T>>(ScreenState.Loading)
    val state = screenState.asStateFlow()
    private var request: Job? = null

    protected fun load(fetch: suspend () -> T) {
        request?.cancel()
        screenState.value = ScreenState.Loading
        request = viewModelScope.launch {
            screenState.value = try {
                ScreenState.Ready(fetch())
            } catch (failure: WorkspaceFailure) {
                ScreenState.Failed(failure)
            }
        }
    }
}

@HiltViewModel
internal class WorkspaceListViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
) : WorkspaceScreenViewModel<List<Workspace>>() {
    fun refresh() = load { repository.listWorkspaces() }
}

internal enum class WorkspaceTab { Projects, Members }

internal data class WorkspaceDetail(
    val workspace: Workspace,
    val projects: List<Project>? = null,
    val members: List<Member>? = null,
    val sectionError: WorkspaceFailure? = null,
)

@HiltViewModel
internal class WorkspaceDetailViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val savedState: SavedStateHandle,
) : WorkspaceScreenViewModel<WorkspaceDetail>() {
    val workspaceId: String = checkNotNull(savedState["workspaceId"])
    val tab: WorkspaceTab
        get() = WorkspaceTab.entries[savedState[TAB_INDEX] ?: 0]

    fun selectTab(tab: WorkspaceTab) {
        if (tab == this.tab) return
        savedState[TAB_INDEX] = tab.ordinal
        refresh()
    }

    fun refresh() {
        val tabToLoad = tab
        load {
            val workspace = repository.getWorkspace(workspaceId)
            try {
                when (tabToLoad) {
                    WorkspaceTab.Projects -> WorkspaceDetail(
                        workspace,
                        projects = repository.listProjects(workspaceId),
                    )
                    WorkspaceTab.Members -> WorkspaceDetail(
                        workspace,
                        members = repository.listMembers(workspaceId),
                    )
                }
            } catch (failure: WorkspaceFailure) {
                if (failure.accessLost) throw failure
                WorkspaceDetail(workspace, sectionError = failure)
            }
        }
    }

    private companion object {
        const val TAB_INDEX = "workspaceTab"
    }
}

internal data class WorkspaceBoard(
    val workspace: Workspace,
    val project: Project,
    val board: Board,
    val busy: Boolean = false,
    val syncError: WorkspaceFailure? = null,
)

@HiltViewModel
internal class WorkspaceBoardViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val tasks: TaskRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val workspaceId: String = checkNotNull(savedState["workspaceId"])
    val projectId: String = checkNotNull(savedState["projectId"])
    private val screenState = MutableStateFlow<ScreenState<WorkspaceBoard>>(ScreenState.Loading)
    val state = screenState.asStateFlow()
    private val failures = Channel<WorkspaceFailure>(Channel.BUFFERED)
    val moveFailures = failures.receiveAsFlow()
    private var refreshRequest: Job? = null

    var selectedColumn: String?
        get() = savedState["selectedColumn"]
        set(value) { savedState["selectedColumn"] = value }

    private val current: WorkspaceBoard?
        get() = (state.value as? ScreenState.Ready)?.data

    fun refresh() {
        val previous = current
        if (previous?.busy == true || refreshRequest?.isActive == true) return
        screenState.value = previous?.let { ScreenState.Ready(it.copy(busy = true)) } ?: ScreenState.Loading
        refreshRequest = viewModelScope.launch {
            screenState.value = try {
                val workspace = repository.getWorkspace(workspaceId)
                val project = repository.getProject(projectId)
                val board = repository.getBoard(projectId)
                ScreenState.Ready(WorkspaceBoard(workspace, project, board))
            } catch (failure: WorkspaceFailure) {
                failedSync(previous, failure)
            }
        }
    }

    fun moveTask(taskId: String, targetColumnId: String, targetIndex: Int): Boolean {
        val previous = current ?: return false
        if (previous.busy || previous.syncError != null) return false
        val move = BoardMove.prepare(previous.board, taskId, targetColumnId, targetIndex) ?: return false
        screenState.value = ScreenState.Ready(previous.copy(board = move.optimistic, busy = true))
        viewModelScope.launch {
            val outcome = move.execute(
                save = tasks::moveTask,
                reload = { repository.getBoard(projectId) },
                publish = { screenState.value = ScreenState.Ready(previous.copy(board = it, busy = true)) },
                onFailure = { failures.trySend(it) },
            )
            val updated = previous.copy(board = outcome.board)
            screenState.value = outcome.syncError?.let { failedSync(updated, it) } ?: ScreenState.Ready(updated)
        }
        return true
    }

    private fun failedSync(previous: WorkspaceBoard?, failure: WorkspaceFailure): ScreenState<WorkspaceBoard> =
        if (previous == null || failure.accessLost) ScreenState.Failed(failure)
        else ScreenState.Ready(previous.copy(busy = false, syncError = failure))
}
