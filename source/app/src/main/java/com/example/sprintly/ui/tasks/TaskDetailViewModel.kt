package com.example.sprintly.ui.tasks

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.R
import com.example.sprintly.offline.OfflineConnectivity
import com.example.sprintly.offline.WorkspaceSnapshots
import com.example.sprintly.tasks.Task
import com.example.sprintly.tasks.TaskActivity
import com.example.sprintly.tasks.TaskComment
import com.example.sprintly.tasks.TaskRepository
import com.example.sprintly.ui.workspace.BoardMove
import com.example.sprintly.ui.workspace.accessLost
import com.example.sprintly.ui.workspace.displayMessage
import com.example.sprintly.ui.workspace.temporaryReadFailure
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.WorkspaceFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class TaskDetails(
    val task: Task,
    val projectName: String,
    val workspaceRole: String,
    val board: Board,
)

internal data class DiscussionPage<T>(
    val rows: List<T> = emptyList(),
    val next: Int? = 1,
    val loading: Boolean = false,
    val failure: WorkspaceFailure? = null,
)

internal data class TaskDetailState(
    val details: TaskDetails? = null,
    val loadFailure: WorkspaceFailure? = null,
    val cached: Boolean = false,
    val busy: Boolean = false,
    val deleted: Boolean = false,
    val comments: DiscussionPage<TaskComment> = DiscussionPage(),
    val activity: DiscussionPage<TaskActivity> = DiscussionPage(),
    val commentText: String = "",
    val sendingComment: Boolean = false,
    val commentUncertain: Boolean = false,
    @param:StringRes val commentError: Int? = null,
    @param:StringRes val notice: Int? = null,
) {
    val canUseServer get() = details != null && !cached && loadFailure == null
}

@HiltViewModel
internal class TaskDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tasks: TaskRepository,
    private val snapshots: WorkspaceSnapshots,
    private val connectivity: OfflineConnectivity,
) : ViewModel() {
    val workspaceId = checkNotNull(savedState.get<String>("workspaceId"))
    val projectId = checkNotNull(savedState.get<String>("projectId"))
    val taskId = checkNotNull(savedState.get<String>("taskId"))

    private val mutableState = MutableStateFlow(TaskDetailState())
    val state = mutableState.asStateFlow()
    private var pending: TaskSubmission<String>? = null
    private var loadingDetails = false

    fun refresh() {
        if (loadingDetails || state.value.busy || state.value.deleted) return
        viewModelScope.launch {
            if (loadDetails()) {
                if (!state.value.sendingComment) loadComments(fromStart = true)
                refreshActivity()
            }
        }
    }

    fun loadComments(fromStart: Boolean = false) {
        if (!canUseServer() || state.value.sendingComment) return
        fetchPage(TaskDetailState::comments, { page ->
            tasks.listComments(taskId, page)
        }, TaskComment::id, fromStart) { current, page -> current.copy(comments = page) }
    }

    fun loadActivity(fromStart: Boolean = false) = fetchPage(TaskDetailState::activity, { page ->
        tasks.listActivities(taskId, page)
    }, TaskActivity::id, fromStart) { current, page -> current.copy(activity = page) }

    fun editComment(text: String) {
        mutableState.update { current ->
            if (current.sendingComment || current.commentUncertain) current
            else current.copy(commentText = text, commentError = null)
        }
    }

    fun sendComment() {
        val current = state.value
        if (!canUseServer() || current.sendingComment || current.comments.loading || current.busy
        ) return
        val login = snapshots.sessionId() ?: return
        val body = pending?.payload ?: current.commentText.trim()
        if (!body.withinTaskLimit(2_000)) {
            mutableState.update { it.copy(commentError = R.string.task_comment_length) }
            return
        }
        mutableState.update { it.copy(sendingComment = true, commentError = null) }
        viewModelScope.launch {
            val submission = pending ?: TaskSubmission(body).also { pending = it }
            try {
                val comment = tasks.createComment(taskId, submission.payload, submission.key)
                if (snapshots.sessionId() != login) return@launch
                pending = null
                mutableState.update {
                    it.copy(
                        sendingComment = false,
                        commentUncertain = false,
                        commentText = "",
                        comments = it.comments.copy(
                            rows = (it.comments.rows + comment).distinctBy(TaskComment::id).sortedBy(TaskComment::createdAt),
                        ),
                    )
                }
            } catch (failure: WorkspaceFailure) {
                if (snapshots.sessionId() != login) return@launch
                val uncertain = failure.needsIdempotentRetry
                if (!uncertain) pending = null
                mutableState.update {
                    it.copy(
                        sendingComment = false,
                        commentUncertain = uncertain,
                        loadFailure = failure.takeIf { it.accessLost } ?: it.loadFailure,
                        commentError = when {
                            uncertain -> R.string.task_unconfirmed
                            failure.code == "IDEMPOTENCY_KEY_REUSED" -> R.string.task_comment_request_changed
                            else -> failure.displayMessage()
                        },
                    )
                }
            }
        }
    }

    fun move(columnId: String, index: Int) {
        val details = state.value.details ?: return
        if (!canUseServer() || state.value.busy || state.value.sendingComment) return
        val move = BoardMove.prepare(details.board, taskId, columnId, index) ?: return
        val login = snapshots.sessionId() ?: return

        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val outcome = move.execute(
                save = tasks::moveTask,
                reload = { snapshots.refreshBoard(workspaceId, projectId) },
                publish = { board -> if (snapshots.sessionId() == login) publishBoard(details, board) },
                onFailure = { failure ->
                    val message = when {
                        failure.needsIdempotentRetry -> R.string.board_move_unconfirmed
                        failure.status == 409 -> R.string.board_move_conflict
                        else -> R.string.task_move_failed
                    }
                    if (snapshots.sessionId() == login) mutableState.update { it.copy(notice = message) }
                },
            )
            if (snapshots.sessionId() != login) return@launch
            publishBoard(details, outcome.board)
            outcome.syncError?.let(::recordReadFailure)
            mutableState.update { it.copy(busy = false) }
            if (outcome.syncError == null) refreshActivity()
        }
    }

    fun delete() {
        if (!canUseServer() || state.value.busy || state.value.sendingComment) return
        val login = snapshots.sessionId() ?: return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                tasks.deleteTask(taskId)
                if (snapshots.sessionId() != login) return@launch
                snapshots.removeTask(workspaceId, projectId, taskId, session = login)
                if (snapshots.sessionId() == login) mutableState.update { it.copy(deleted = true) }
            } catch (failure: WorkspaceFailure) {
                if (snapshots.sessionId() != login) return@launch
                if (failure.status == 404) loadDetails()
                mutableState.update { it.copy(notice = failure.displayMessage()) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun clearNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    private suspend fun loadDetails(): Boolean {
        val login = snapshots.sessionId() ?: return false
        loadingDetails = true
        mutableState.update { it.copy(loadFailure = null) }
        return try {
            if (state.value.details == null) {
                snapshots.cachedTaskBoard(workspaceId, projectId)?.let { snapshot ->
                    if (snapshots.sessionId() != login) return false
                    snapshot.board.findTask(taskId)?.let { task -> publishDetails(snapshot, task, cached = true) }
                }
            }
            if (snapshots.sessionId() != login) return false
            if (!connectivity.isConnected()) throw WorkspaceFailure("NETWORK_ERROR")
            val snapshot = snapshots.refreshTaskBoard(workspaceId, projectId)
            if (snapshots.sessionId() != login) return false
            val task = snapshot.board.findTask(taskId) ?: throw WorkspaceFailure("TASK_NOT_FOUND", 404)
            publishDetails(snapshot, task, cached = false)
            true
        } catch (failure: WorkspaceFailure) {
            if (snapshots.sessionId() == login) recordReadFailure(failure)
            false
        } finally {
            loadingDetails = false
        }
    }

    private fun canUseServer(): Boolean = state.value.canUseServer && !loadingDetails && connectivity.isConnected()

    private fun publishDetails(snapshot: TaskBoardSnapshot, task: Task, cached: Boolean) {
        val details = TaskDetails(task, snapshot.project.name, snapshot.workspace.role, snapshot.board)
        mutableState.update { it.copy(details = details, cached = cached, loadFailure = null) }
    }

    private fun recordReadFailure(failure: WorkspaceFailure) {
        mutableState.update {
            when {
                failure.accessLost -> TaskDetailState(loadFailure = failure, busy = it.busy)
                else -> it.copy(loadFailure = failure, cached = it.details != null && failure.temporaryReadFailure)
            }
        }
    }

    private fun refreshActivity() = loadActivity(fromStart = true)

    private fun publishBoard(previous: TaskDetails, board: Board) {
        val task = board.findTask(taskId)
        mutableState.update {
            if (task == null) it.copy(loadFailure = WorkspaceFailure("TASK_NOT_FOUND", 404))
            else it.copy(details = previous.copy(task = task, board = board))
        }
    }

    private fun <T> fetchPage(
        select: (TaskDetailState) -> DiscussionPage<T>,
        load: suspend (Int) -> List<T>,
        identifier: (T) -> String,
        fromStart: Boolean,
        store: (TaskDetailState, DiscussionPage<T>) -> TaskDetailState,
    ) {
        if (!canUseServer()) return
        val login = snapshots.sessionId() ?: return
        val page = select(state.value)
        val next = if (fromStart) 1 else page.next ?: return
        if (page.loading) return
        mutableState.update { store(it, page.copy(next = next, loading = true, failure = null)) }
        viewModelScope.launch {
            try {
                val rows = load(next)
                if (snapshots.sessionId() != login) return@launch
                mutableState.update { current ->
                    val existing = if (next == 1) emptyList() else select(current).rows
                    store(
                        current,
                        DiscussionPage(
                            rows = (existing + rows).distinctBy(identifier),
                            next = (next + 1).takeIf { rows.size == DISCUSSION_PAGE_SIZE },
                        ),
                    )
                }
            } catch (failure: WorkspaceFailure) {
                if (snapshots.sessionId() != login) return@launch
                if (failure.accessLost) {
                    recordReadFailure(failure)
                    return@launch
                }
                mutableState.update { store(it, select(it).copy(loading = false, failure = failure)) }
            }
        }
    }

    private companion object {
        const val DISCUSSION_PAGE_SIZE = 20
    }
}
