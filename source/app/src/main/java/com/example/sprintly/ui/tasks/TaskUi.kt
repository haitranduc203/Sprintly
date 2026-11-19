package com.example.sprintly.ui.tasks

import android.content.Context
import android.text.format.DateFormat
import android.widget.TextView
import androidx.annotation.StringRes
import com.example.sprintly.R
import com.example.sprintly.offline.WorkspaceSnapshots
import com.example.sprintly.ui.workspace.tint
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace
import com.example.sprintly.workspace.WorkspaceFailure
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal val taskPriorities = listOf("LOW", "MEDIUM", "HIGH", "URGENT")

@StringRes
internal fun taskPriorityLabel(priority: String): Int = when (priority) {
    "LOW" -> R.string.task_priority_low
    "MEDIUM" -> R.string.task_priority_medium
    "HIGH" -> R.string.task_priority_high
    "URGENT" -> R.string.task_priority_urgent
    else -> R.string.task_priority_unknown
}

internal fun TextView.applyTaskPriorityColors(priority: String) {
    val (text, background) = when (priority) {
        "URGENT" -> R.color.orbix_error to R.color.orbix_error_container
        "HIGH" -> R.color.orbix_warning to R.color.orbix_warning_container
        else -> R.color.orbix_on_surface_variant to R.color.orbix_divider
    }
    setTextColor(context.getColor(text))
    backgroundTintList = context.tint(background)
}

internal fun Context.taskDateTime(instant: String): String {
    val date = Date.from(Instant.parse(instant))
    return "${DateFormat.getMediumDateFormat(this).format(date)} · ${DateFormat.getTimeFormat(this).format(date)}"
}

internal fun String.withinTaskLimit(limit: Int): Boolean =
    isNotBlank() && codePointCount(0, length) <= limit

internal val WorkspaceFailure.needsIdempotentRetry: Boolean
    get() = code == "NETWORK_ERROR" || code == "AUTH_RETRY" || (status ?: 0) >= 500

internal fun Board.findTask(id: String) =
    columns.firstNotNullOfOrNull { column -> column.tasks.firstOrNull { it.id == id } }

internal data class TaskBoardSnapshot(val workspace: Workspace, val project: Project, val board: Board)

internal suspend fun WorkspaceSnapshots.cachedTaskBoard(workspaceId: String, projectId: String): TaskBoardSnapshot? {
    val board = cachedBoard(workspaceId, projectId) ?: return null
    val project = cachedProject(workspaceId, projectId) ?: return null
    val workspace = cachedWorkspace(workspaceId) ?: return null
    return TaskBoardSnapshot(workspace, project, board)
}

internal suspend fun WorkspaceSnapshots.refreshTaskBoard(workspaceId: String, projectId: String): TaskBoardSnapshot =
    coroutineScope {
        val workspace = async { refreshWorkspace(workspaceId) }
        val project = async { refreshProject(workspaceId, projectId) }
        val board = async { refreshBoard(workspaceId, projectId) }
        TaskBoardSnapshot(workspace.await(), project.await(), board.await())
    }

internal data class TaskSubmission<T>(val payload: T, val key: String = UUID.randomUUID().toString())
