package com.example.sprintly.ui.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.offline.OfflineConnectivity
import com.example.sprintly.offline.WorkspaceSnapshots
import com.example.sprintly.tasks.TaskRepository
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace
import com.example.sprintly.workspace.WorkspaceFailure
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
    data class Ready<T>(val data: T, val cached: Boolean = false, val refreshFailed: Boolean = false) : ScreenState<T>
    data class Failed(val error: WorkspaceFailure) : ScreenState<Nothing>
}

internal abstract class WorkspaceScreenViewModel<T>(
    private val snapshots: WorkspaceSnapshots,
    private val connectivity: OfflineConnectivity,
) : ViewModel() {
    private val screenState = MutableStateFlow<ScreenState<T>>(ScreenState.Loading)
    val state = screenState.asStateFlow()
    private var request: Job? = null
    private var currentLogin: String? = null

    protected fun load(readCached: suspend () -> T?, keepCurrent: Boolean = true, fetch: suspend () -> T) {
        val login = snapshots.sessionId() ?: return
        val previous = (screenState.value as? ScreenState.Ready).takeIf { keepCurrent && currentLogin == login }
        currentLogin = login
        request?.cancel()
        screenState.value = previous?.copy(cached = true) ?: ScreenState.Loading
        request = viewModelScope.launch {
            val saved = readCached() ?: previous?.data
            if (snapshots.sessionId() != login) return@launch
            if (saved != null) screenState.value = ScreenState.Ready(saved, cached = true)
            val result = try {
                if (!connectivity.isConnected()) throw WorkspaceFailure("NETWORK_ERROR")
                ScreenState.Ready(fetch())
            } catch (failure: WorkspaceFailure) {
                if (saved != null && failure.temporaryReadFailure) {
                    ScreenState.Ready(saved, cached = true, refreshFailed = true)
                } else ScreenState.Failed(failure)
            }
            if (snapshots.sessionId() == login) screenState.value = result
        }
    }
}

@HiltViewModel
internal class WorkspaceListViewModel @Inject constructor(
    private val snapshots: WorkspaceSnapshots,
    connectivity: OfflineConnectivity,
) : WorkspaceScreenViewModel<List<Workspace>>(snapshots, connectivity) {
    fun refresh() = load(snapshots::cachedWorkspaces, fetch = snapshots::refreshWorkspaces)
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
    private val snapshots: WorkspaceSnapshots,
    connectivity: OfflineConnectivity,
    private val savedState: SavedStateHandle,
) : WorkspaceScreenViewModel<WorkspaceDetail>(snapshots, connectivity) {
    val workspaceId: String = checkNotNull(savedState["workspaceId"])
    val tab: WorkspaceTab
        get() = WorkspaceTab.entries[savedState[TAB_INDEX] ?: 0]

    fun selectTab(tab: WorkspaceTab) {
        if (tab == this.tab) return
        savedState[TAB_INDEX] = tab.ordinal
        loadTab(keepCurrent = false)
    }

    fun refresh() = loadTab(keepCurrent = true)

    private fun loadTab(keepCurrent: Boolean) {
        val tabToLoad = tab
        load(readCached = { readCached(tabToLoad) }, keepCurrent = keepCurrent) {
            val workspace = snapshots.refreshWorkspace(workspaceId)
            try {
                when (tabToLoad) {
                    WorkspaceTab.Projects -> WorkspaceDetail(
                        workspace,
                        projects = snapshots.refreshProjects(workspaceId),
                    )
                    WorkspaceTab.Members -> WorkspaceDetail(
                        workspace,
                        members = snapshots.refreshMembers(workspaceId),
                    )
                }
            } catch (failure: WorkspaceFailure) {
                if (failure.accessLost || failure.temporaryReadFailure) throw failure
                WorkspaceDetail(workspace, sectionError = failure)
            }
        }
    }

    private suspend fun readCached(tab: WorkspaceTab): WorkspaceDetail? {
        val workspace = snapshots.cachedWorkspace(workspaceId) ?: return null
        return when (tab) {
            WorkspaceTab.Projects -> WorkspaceDetail(workspace, projects = snapshots.cachedProjects(workspaceId))
            WorkspaceTab.Members -> WorkspaceDetail(workspace, members = snapshots.cachedMembers(workspaceId))
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
    private val snapshots: WorkspaceSnapshots,
    private val connectivity: OfflineConnectivity,
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
    private var currentLogin: String? = null

    fun refresh() {
        val login = snapshots.sessionId() ?: return
        val previous = current.takeIf { currentLogin == login }
        if (previous?.busy == true || refreshRequest?.isActive == true) return
        currentLogin = login
        screenState.value = previous?.let { ScreenState.Ready(it.copy(busy = true)) } ?: ScreenState.Loading
        refreshRequest = viewModelScope.launch {
            val saved = previous ?: cachedBoard()
            if (snapshots.sessionId() != login) return@launch
            if (saved != null) screenState.value = ScreenState.Ready(saved.copy(busy = true), cached = true)
            val result = try {
                if (!connectivity.isConnected()) throw WorkspaceFailure("NETWORK_ERROR")
                val workspace = snapshots.refreshWorkspace(workspaceId)
                val project = snapshots.refreshProject(workspaceId, projectId)
                val board = snapshots.refreshBoard(workspaceId, projectId)
                ScreenState.Ready(WorkspaceBoard(workspace, project, board))
            } catch (failure: WorkspaceFailure) {
                failedSync(saved, failure)
            }
            if (snapshots.sessionId() == login) screenState.value = result
        }
    }

    private suspend fun cachedBoard(): WorkspaceBoard? {
        val workspace = snapshots.cachedWorkspace(workspaceId) ?: return null
        val project = snapshots.cachedProject(workspaceId, projectId) ?: return null
        val board = snapshots.cachedBoard(workspaceId, projectId) ?: return null
        return WorkspaceBoard(workspace, project, board)
    }

    fun moveTask(taskId: String, targetColumnId: String, targetIndex: Int): Boolean {
        val previous = current ?: return false
        val login = snapshots.sessionId() ?: return false
        if (login != currentLogin || previous.busy || previous.syncError != null || !connectivity.isConnected()) return false
        val move = BoardMove.prepare(previous.board, taskId, targetColumnId, targetIndex) ?: return false
        screenState.value = ScreenState.Ready(previous.copy(board = move.optimistic, busy = true))
        viewModelScope.launch {
            val outcome = move.execute(
                save = tasks::moveTask,
                reload = { snapshots.refreshBoard(workspaceId, projectId) },
                publish = {
                    if (snapshots.sessionId() == login) {
                        screenState.value = ScreenState.Ready(previous.copy(board = it, busy = true))
                    }
                },
                onFailure = { if (snapshots.sessionId() == login) failures.trySend(it) },
            )
            val updated = previous.copy(board = outcome.board)
            if (snapshots.sessionId() == login) {
                screenState.value = outcome.syncError?.let { failedSync(updated, it) } ?: ScreenState.Ready(updated)
            }
        }
        return true
    }

    private fun failedSync(previous: WorkspaceBoard?, failure: WorkspaceFailure): ScreenState<WorkspaceBoard> =
        if (previous == null || failure.accessLost) ScreenState.Failed(failure)
        else ScreenState.Ready(previous.copy(busy = false, syncError = failure), cached = true, refreshFailed = true)
}
