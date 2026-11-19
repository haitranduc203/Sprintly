package com.example.sprintly

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sprintly.offline.OfflineCache
import com.example.sprintly.offline.OfflineDatabase
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.BoardColumn
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineCacheTest {
    @Test
    fun completeListsPruneDescendantsAndLoginsNeverShareSnapshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val cache = OfflineCache(database)
            val workspace = Workspace("workspace", "Saved workspace", null, "OWNER")
            val project = Project("project", workspace.id, "Saved project", null, 1)
            val board = Board(2, listOf(BoardColumn("column", "To Do", 0)))

            cache.saveWorkspaces("login-a", emptyList())
            cache.saveWorkspace("login-a", workspace)
            assertEquals(listOf(workspace), cache.workspaces("login-a"))
            cache.saveProjects("login-a", workspace.id, emptyList())
            cache.saveProject("login-a", project)
            assertEquals(listOf(project), cache.projects("login-a", workspace.id))
            cache.saveBoard("login-a", workspace.id, project.id, board)
            cache.saveBoard("login-a", workspace.id, project.id, board.copy(boardVersion = 1))
            assertEquals(board, cache.board("login-a", workspace.id, project.id))
            assertNull(cache.board("login-b", workspace.id, project.id))

            cache.saveProjects("login-a", workspace.id, emptyList())
            cache.saveBoard("login-a", workspace.id, project.id, board)
            assertEquals(emptyList<Project>(), cache.projects("login-a", workspace.id))
            assertNull(cache.board("login-a", workspace.id, project.id))

            cache.saveWorkspaces("login-b", listOf(workspace))
            cache.saveWorkspaces("login-a", emptyList())
            cache.saveWorkspace("login-a", workspace)
            assertEquals(emptyList<Workspace>(), cache.workspaces("login-a"))
            assertNull(cache.workspace("login-a", workspace.id))
            assertEquals(listOf(workspace), cache.workspaces("login-b"))

            cache.clearSession("login-b")
            assertNull(cache.workspaces("login-b"))
            assertNull(cache.workspace("login-b", workspace.id))
        } finally {
            database.close()
        }
    }
}
