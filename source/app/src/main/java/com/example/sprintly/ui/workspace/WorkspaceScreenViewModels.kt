package com.example.sprintly.ui.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace
import com.example.sprintly.workspace.WorkspaceFailure
import com.example.sprintly.workspace.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal data class WorkspaceBoard(val workspace: Workspace, val project: Project, val board: Board)

@HiltViewModel
internal class WorkspaceBoardViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val savedState: SavedStateHandle,
) : WorkspaceScreenViewModel<WorkspaceBoard>() {
    val workspaceId: String = checkNotNull(savedState["workspaceId"])
    val projectId: String = checkNotNull(savedState["projectId"])

    var selectedColumn: String?
        get() = savedState["selectedColumn"]
        set(value) { savedState["selectedColumn"] = value }

    fun refresh() = load {
        val workspace = repository.getWorkspace(workspaceId)
        val project = repository.getProject(projectId)
        val board = repository.getBoard(projectId)
        WorkspaceBoard(workspace, project, board)
    }
}
