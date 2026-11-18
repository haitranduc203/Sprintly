package com.example.sprintly.ui.workspace

import com.example.sprintly.tasks.Task
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.BoardColumn
import com.example.sprintly.workspace.WorkspaceFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BoardMoveTest {
    @Test
    fun sameColumnIndexUsesTheListAfterRemovingTheDraggedTask() {
        val move = checkNotNull(BoardMove.prepare(board, "a", "todo", 2))

        assertEquals(listOf("b", "c", "a"), move.optimistic.columns.first().tasks.map { it.id })
        assertEquals(listOf(0, 1, 2), move.optimistic.columns.first().tasks.map { it.position })
        assertNull(BoardMove.prepare(board, "a", "todo", 0))
        assertNull(BoardMove.prepare(board, "a", "todo", 3))
    }

    @Test
    fun crossColumnMovePublishesPredictionAndLoadsTheServerOrder() = runBlocking {
        val move = checkNotNull(BoardMove.prepare(board, "a", "doing", 0))
        val serverBoard = move.optimistic.copy(boardVersion = 9)
        val published = mutableListOf<Board>()

        val outcome = move.execute(
            save = { id, columnId, index, taskVersion, boardVersion ->
                assertEquals(listOf("a", "doing", 0, 3L, 8L), listOf(id, columnId, index, taskVersion, boardVersion))
                assertEquals(listOf("a", "d"), published.single().columns.last().tasks.map { it.id })
                task("a", "doing", 0).copy(version = 4, boardVersion = 9)
            },
            reload = { serverBoard },
            publish = published::add,
            onFailure = { error("Unexpected move failure $it") },
        )

        assertSame(serverBoard, outcome.board)
        assertNull(outcome.syncError)
    }

    @Test
    fun conflictAndServerErrorsRestoreTheWholeBoardBeforeReloading() = runBlocking {
        for (failure in listOf(WorkspaceFailure("BOARD_VERSION_CONFLICT", 409), WorkspaceFailure("SERVER_ERROR", 500))) {
            val move = checkNotNull(BoardMove.prepare(board, "a", "doing", 1))
            val published = mutableListOf<Board>()
            val failures = mutableListOf<WorkspaceFailure>()
            val serverBoard = board.copy(boardVersion = 9)

            val outcome = move.execute(
                save = { _, _, _, _, _ -> throw failure },
                reload = {
                    assertSame(board, published.last())
                    assertEquals(listOf(failure), failures)
                    serverBoard
                },
                publish = published::add,
                onFailure = failures::add,
            )

            assertEquals(listOf("d", "a"), published.first().columns.last().tasks.map { it.id })
            assertSame(serverBoard, outcome.board)
        }
    }

    @Test
    fun failedRecoveryKeepsEveryCardInTheRestoredSnapshot() = runBlocking {
        val move = checkNotNull(BoardMove.prepare(board, "a", "doing", 1))
        val offline = WorkspaceFailure("NETWORK_ERROR")

        val outcome = move.execute(
            save = { _, _, _, _, _ -> throw WorkspaceFailure("SERVER_ERROR", 500) },
            reload = { throw offline },
            publish = {},
            onFailure = {},
        )

        assertSame(board, outcome.board)
        assertSame(offline, outcome.syncError)
    }

    private val board = Board(
        boardVersion = 8,
        columns = listOf(
            BoardColumn("todo", "To Do", 0, listOf(task("a", "todo", 0), task("b", "todo", 1), task("c", "todo", 2))),
            BoardColumn("doing", "In Progress", 1, listOf(task("d", "doing", 0))),
        ),
    )

    private fun task(id: String, column: String, position: Int) = Task(
        id = id,
        projectId = "project",
        columnId = column,
        title = id,
        description = null,
        priority = "MEDIUM",
        assigneeId = null,
        assignee = null,
        dueAt = null,
        position = position,
        version = 3,
        createdBy = "user",
        createdAt = "2026-11-18T00:00:00.000Z",
        updatedAt = "2026-11-18T00:00:00.000Z",
        boardVersion = null,
    )
}
