package com.example.sprintly.ui.tasks

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.example.sprintly.R
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.WorkspaceFailure
import java.time.Instant
import java.util.Date
import java.util.UUID

internal val taskPriorities = listOf("LOW", "MEDIUM", "HIGH", "URGENT")

@StringRes
internal fun taskPriorityLabel(priority: String): Int = when (priority) {
    "LOW" -> R.string.task_priority_low
    "MEDIUM" -> R.string.task_priority_medium
    "HIGH" -> R.string.task_priority_high
    "URGENT" -> R.string.task_priority_urgent
    else -> R.string.task_priority_unknown
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

internal data class TaskSubmission<T>(val payload: T, val key: String = UUID.randomUUID().toString())
