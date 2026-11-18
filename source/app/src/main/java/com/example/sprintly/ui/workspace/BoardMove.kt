package com.example.sprintly.ui.workspace

import com.example.sprintly.tasks.Task
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.WorkspaceFailure

internal class BoardMove private constructor(
    private val original: Board,
    private val task: Task,
    private val targetColumnId: String,
    private val targetIndex: Int,
    val optimistic: Board,
) {
    data class Outcome(val board: Board, val syncError: WorkspaceFailure? = null)

    suspend fun execute(
        save: suspend (String, String, Int, Long, Long) -> Task,
        reload: suspend () -> Board,
        publish: (Board) -> Unit,
        onFailure: (WorkspaceFailure) -> Unit,
    ): Outcome {
        publish(optimistic)
        val snapshot = try {
            val saved = save(task.id, targetColumnId, targetIndex, task.version, original.boardVersion)
            optimistic.copy(
                boardVersion = saved.boardVersion ?: original.boardVersion,
                columns = optimistic.columns.map { column ->
                    if (column.id != targetColumnId) column
                    else column.copy(tasks = column.tasks.map { if (it.id == task.id) saved else it })
                },
            )
        } catch (failure: WorkspaceFailure) {
            publish(original)
            onFailure(failure)
            original
        }

        return try {
            Outcome(reload())
        } catch (failure: WorkspaceFailure) {
            Outcome(snapshot, failure)
        }
    }

    companion object {
        fun prepare(board: Board, taskId: String, targetColumnId: String, targetIndex: Int): BoardMove? {
            val source = board.columns.firstOrNull { column -> column.tasks.any { it.id == taskId } } ?: return null
            val task = source.tasks.first { it.id == taskId }
            val target = board.columns.firstOrNull { it.id == targetColumnId } ?: return null
            val remaining = target.tasks.sortedBy { it.position }.filterNot { it.id == taskId }
            if (targetIndex !in 0..remaining.size) return null
            if (source.id == target.id && targetIndex == target.tasks.sortedBy { it.position }.indexOf(task)) return null

            val reordered = remaining.toMutableList().apply {
                add(targetIndex, task.copy(columnId = targetColumnId))
            }
            val predicted = board.copy(
                columns = board.columns.map { column ->
                    val tasks = when (column.id) {
                        target.id -> reordered
                        source.id -> column.tasks.sortedBy { it.position }.filterNot { it.id == taskId }
                        else -> return@map column
                    }
                    column.copy(tasks = tasks.mapIndexed { index, entry -> entry.copy(position = index) })
                },
            )
            return BoardMove(board, task, targetColumnId, targetIndex, predicted)
        }
    }
}
