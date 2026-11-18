package com.example.sprintly.ui.tasks

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.R
import com.example.sprintly.tasks.Task
import com.example.sprintly.tasks.TaskInput
import com.example.sprintly.tasks.TaskRepository
import com.example.sprintly.ui.workspace.accessLost
import com.example.sprintly.ui.workspace.displayMessage
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.WorkspaceFailure
import com.example.sprintly.workspace.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class TaskFormOptions(
    val projectName: String,
    val workspaceName: String,
    val board: Board,
    val members: List<Member>,
)

internal data class TaskFormState(
    val options: TaskFormOptions? = null,
    val draft: TaskInput? = null,
    val baseline: TaskInput? = null,
    val saving: Boolean = false,
    val uncertain: Boolean = false,
    val loadFailure: WorkspaceFailure? = null,
    @param:StringRes val inputError: Int? = null,
    val result: Task? = null,
) {
    val dirty get() = draft != baseline || uncertain
    val editable get() = !saving && !uncertain && loadFailure == null
}

@HiltViewModel
internal class TaskFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tasks: TaskRepository,
    private val workspaces: WorkspaceRepository,
) : ViewModel() {
    private val projectId = checkNotNull(savedState.get<String>("projectId"))
    private val workspaceId = checkNotNull(savedState.get<String>("workspaceId"))
    private val initialColumn = savedState.get<String>("columnId")
    private val taskId = savedState.get<String>("taskId")
    val editing = taskId != null

    private val mutableState = MutableStateFlow(TaskFormState())
    val state = mutableState.asStateFlow()
    private var original: Task? = null
    private var pending: TaskSubmission<TaskInput>? = null
    private var loading = false

    fun load() {
        if (loading || state.value.saving || state.value.result != null) return
        if (state.value.options == null || state.value.loadFailure != null) {
            viewModelScope.launch { loadOptions() }
        }
    }

    fun edit(change: (TaskInput) -> TaskInput) {
        mutableState.update { current ->
            val draft = current.draft
            if (draft == null || !current.editable) current
            else current.copy(draft = change(draft), inputError = null)
        }
    }

    fun submit() {
        val current = state.value
        val options = current.options ?: return
        val draft = current.draft ?: return
        if (current.saving || current.result != null || current.loadFailure != null) return
        val input = pending?.payload ?: draft.copy(
            title = draft.title.trim(),
            description = draft.description?.trim()?.ifEmpty { null },
        )
        val inputError = when {
            !input.title.withinTaskLimit(150) -> R.string.task_title_length
            pending != null -> null
            options.board.columns.none { it.id == input.columnId } -> R.string.task_choose_column
            input.assigneeId != null && options.members.none { it.id == input.assigneeId } ->
                R.string.task_invalid_assignee
            else -> null
        }
        if (inputError != null) {
            mutableState.update { it.copy(inputError = inputError) }
            return
        }
        mutableState.update { it.copy(saving = true, inputError = null) }
        viewModelScope.launch {
            try {
                val previous = original
                val result = if (previous == null) {
                    val submission = pending ?: TaskSubmission(input).also { pending = it }
                    tasks.createTask(projectId, submission.payload, submission.key)
                } else {
                    val boardVersion = options.board.boardVersion.takeIf { input.columnId != previous.columnId }
                    if (input == previous.toInput()) previous
                    else tasks.updateTask(previous.id, input, previous.version, boardVersion)
                }
                pending = null
                mutableState.update { it.copy(saving = false, uncertain = false, result = result) }
            } catch (failure: WorkspaceFailure) {
                val uncertain = pending != null && failure.needsIdempotentRetry
                if (!uncertain) pending = null
                val keyReused = failure.code == "IDEMPOTENCY_KEY_REUSED"
                val conflict = failure.status == 409 && !keyReused
                val assigneeRemoved = failure.code == "INVALID_ASSIGNEE"
                val message = when {
                    uncertain -> R.string.task_unconfirmed
                    keyReused -> R.string.task_request_changed
                    conflict -> R.string.task_conflict
                    assigneeRemoved -> R.string.task_invalid_assignee
                    else -> failure.displayMessage()
                }
                if (failure.accessLost) mutableState.update { it.copy(loadFailure = failure) }
                else if (conflict || assigneeRemoved) loadOptions(message)
                mutableState.update { it.copy(saving = false, uncertain = uncertain, inputError = message) }
            }
        }
    }

    private suspend fun loadOptions(@StringRes message: Int? = null) {
        loading = true
        mutableState.update { it.copy(loadFailure = null) }
        try {
            coroutineScope {
                val project = async { workspaces.getProject(projectId) }
                val workspace = async { workspaces.getWorkspace(workspaceId) }
                val board = async { workspaces.getBoard(projectId) }
                val members = async { workspaces.listMembers(workspaceId) }
                val options = TaskFormOptions(project.await().name, workspace.await().name, board.await(), members.await())
                val latest = taskId?.let { options.board.findTask(it) ?: throw WorkspaceFailure("TASK_NOT_FOUND", 404) }
                val initial = latest?.toInput() ?: TaskInput(
                    options.board.columns.firstOrNull { it.id == initialColumn }?.id
                        ?: options.board.columns.firstOrNull()?.id.orEmpty(),
                    "", null, "MEDIUM", null, null,
                )
                original = latest
                mutableState.update { current ->
                    val draft = current.draft
                    val baseline = current.baseline
                    current.copy(
                        options = options,
                        baseline = initial,
                        draft = if (draft != null && baseline != null) draft.rebase(baseline, initial) else initial,
                        inputError = message,
                    )
                }
            }
        } catch (failure: WorkspaceFailure) {
            mutableState.update { it.copy(loadFailure = failure) }
        } finally {
            loading = false
        }
    }

    private fun Task.toInput() = TaskInput(columnId, title, description, priority, assigneeId, dueAt)

    private fun TaskInput.rebase(before: TaskInput, latest: TaskInput) = copy(
        columnId = if (columnId == before.columnId) latest.columnId else columnId,
        title = if (title == before.title) latest.title else title,
        description = if (description == before.description) latest.description else description,
        priority = if (priority == before.priority) latest.priority else priority,
        assigneeId = if (assigneeId == before.assigneeId) latest.assigneeId else assigneeId,
        dueAt = if (dueAt == before.dueAt) latest.dueAt else dueAt,
    )
}
