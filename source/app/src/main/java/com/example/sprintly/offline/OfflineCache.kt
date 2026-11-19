package com.example.sprintly.offline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.sprintly.workspace.Board
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Scope markers and payloads share a table so a complete list can replace and prune its descendants atomically.
@Entity(tableName = "offline_snapshots", primaryKeys = ["sessionId", "kind", "workspaceId", "projectId"])
internal data class OfflineSnapshot(
    val sessionId: String,
    val kind: String,
    val workspaceId: String,
    val projectId: String,
    val payload: String,
)

@Dao
internal interface OfflineSnapshotDao {
    @Query("SELECT payload FROM offline_snapshots WHERE sessionId = :session AND kind = :kind AND workspaceId = :workspace AND projectId = :project")
    suspend fun get(session: String, kind: String, workspace: String, project: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(snapshot: OfflineSnapshot)

    @Query("SELECT DISTINCT workspaceId FROM offline_snapshots WHERE sessionId = :session AND workspaceId != ''")
    suspend fun workspaceIds(session: String): List<String>

    @Query("SELECT DISTINCT projectId FROM offline_snapshots WHERE sessionId = :session AND workspaceId = :workspace AND projectId != ''")
    suspend fun projectIds(session: String, workspace: String): List<String>

    @Query("DELETE FROM offline_snapshots WHERE sessionId = :session AND workspaceId = :workspace")
    suspend fun deleteWorkspace(session: String, workspace: String)

    @Query("DELETE FROM offline_snapshots WHERE sessionId = :session AND workspaceId = :workspace AND projectId = :project")
    suspend fun deleteProject(session: String, workspace: String, project: String)

    @Query("DELETE FROM offline_snapshots WHERE sessionId = :session AND kind = :kind AND workspaceId = :workspace AND projectId = :project")
    suspend fun delete(session: String, kind: String, workspace: String, project: String)

    @Query("DELETE FROM offline_snapshots WHERE sessionId = :session")
    suspend fun clearSession(session: String)
}

@Database(entities = [OfflineSnapshot::class], version = 1, exportSchema = false)
internal abstract class OfflineDatabase : RoomDatabase() {
    abstract fun snapshots(): OfflineSnapshotDao
}

@Singleton
internal class OfflineCache internal constructor(private val database: OfflineDatabase) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        Room.databaseBuilder(
            context,
            OfflineDatabase::class.java,
            File(context.noBackupFilesDir, "offline.db").absolutePath,
        ).build(),
    )

    private val dao = database.snapshots()
    private val gson = Gson()

    suspend fun workspaces(session: String): List<Workspace>? =
        read(session, "workspaces", Array<Workspace>::class.java)?.toList()

    suspend fun workspace(session: String, workspace: String): Workspace? =
        read(session, "workspace", Workspace::class.java, workspace)
            ?: workspaces(session)?.firstOrNull { it.id == workspace }

    suspend fun projects(session: String, workspace: String): List<Project>? =
        read(session, "projects", Array<Project>::class.java, workspace)?.toList()

    suspend fun project(session: String, workspace: String, project: String): Project? =
        read(session, "project", Project::class.java, workspace, project)
            ?: projects(session, workspace)?.firstOrNull { it.id == project }

    suspend fun members(session: String, workspace: String): List<Member>? =
        read(session, "members", Array<Member>::class.java, workspace)?.toList()

    suspend fun board(session: String, workspace: String, project: String): Board? =
        read(session, "board", Board::class.java, workspace, project)

    suspend fun saveWorkspaces(session: String, value: List<Workspace>) = database.withTransaction {
        val ids = value.mapTo(mutableSetOf(), Workspace::id)
        for (workspace in dao.workspaceIds(session)) {
            if (workspace !in ids) denyWorkspace(session, workspace)
        }
        for (workspace in value) {
            dao.delete(session, DENIED, workspace.id, "")
            write(session, "workspace", workspace, workspace.id)
        }
        write(session, "workspaces", value)
    }

    suspend fun saveWorkspace(session: String, value: Workspace) = inScope(session, value.id) {
        write(session, "workspace", value, value.id)
        workspaces(session)?.let { entries ->
            write(session, "workspaces", entries.replaceOrAppend(value) { it.id == value.id })
        }
    }

    suspend fun saveProjects(session: String, workspace: String, value: List<Project>) = inScope(session, workspace) {
        val ids = value.mapTo(mutableSetOf(), Project::id)
        for (project in dao.projectIds(session, workspace)) {
            if (project !in ids) denyProject(session, workspace, project)
        }
        for (project in value) {
            dao.delete(session, DENIED, workspace, project.id)
            write(session, "project", project, workspace, project.id)
        }
        write(session, "projects", value, workspace)
    }

    suspend fun saveMembers(session: String, workspace: String, value: List<Member>) = inScope(session, workspace) {
        write(session, "members", value, workspace)
    }

    suspend fun saveProject(session: String, value: Project) = inScope(session, value.workspaceId, value.id) {
        write(session, "project", value, value.workspaceId, value.id)
        projects(session, value.workspaceId)?.let { entries ->
            write(session, "projects", entries.replaceOrAppend(value) { it.id == value.id }, value.workspaceId)
        }
    }

    suspend fun saveBoard(session: String, workspace: String, project: String, value: Board) =
        inScope(session, workspace, project) {
            val previous = board(session, workspace, project)
            if (previous == null || previous.boardVersion <= value.boardVersion) {
                write(session, "board", value, workspace, project)
            }
        }

    suspend fun removeWorkspace(session: String, workspace: String) = database.withTransaction {
        denyWorkspace(session, workspace)
        workspaces(session)?.let { write(session, "workspaces", it.filterNot { row -> row.id == workspace }) }
        Unit
    }

    suspend fun removeProject(session: String, workspace: String, project: String) = database.withTransaction {
        denyProject(session, workspace, project)
        projects(session, workspace)?.let { write(session, "projects", it.filterNot { row -> row.id == project }, workspace) }
        Unit
    }

    suspend fun removeTask(session: String, workspace: String, project: String, task: String) =
        inScope(session, workspace, project) {
            board(session, workspace, project)?.let { board ->
                val columns = board.columns.map { column ->
                    val remaining = column.tasks.filterNot { it.id == task }
                    if (remaining.size == column.tasks.size) column else column.copy(
                        tasks = remaining.sortedBy { it.position }.mapIndexed { index, entry -> entry.copy(position = index) },
                    )
                }
                if (columns != board.columns) {
                    write(session, "board", board.copy(columns = columns, boardVersion = board.boardVersion + 1), workspace, project)
                }
            }
        }

    suspend fun clearSession(session: String) = dao.clearSession(session)

    private suspend fun denyWorkspace(session: String, workspace: String) {
        dao.deleteWorkspace(session, workspace)
        write(session, DENIED, true, workspace)
    }

    private suspend fun denyProject(session: String, workspace: String, project: String) {
        dao.deleteProject(session, workspace, project)
        write(session, DENIED, true, workspace, project)
    }

    private suspend fun inScope(session: String, workspace: String, project: String = "", save: suspend () -> Unit) =
        database.withTransaction {
            if (dao.get(session, DENIED, workspace, "") == null &&
                (project.isEmpty() || dao.get(session, DENIED, workspace, project) == null)
            ) save()
        }

    private suspend fun <T> read(
        session: String,
        kind: String,
        type: Class<T>,
        workspace: String = "",
        project: String = "",
    ): T? = dao.get(session, kind, workspace, project)?.let { json ->
        try {
            gson.fromJson(json, type)
        } catch (_: JsonParseException) {
            null
        }
    }

    private suspend fun write(session: String, kind: String, value: Any, workspace: String = "", project: String = "") {
        dao.put(OfflineSnapshot(session, kind, workspace, project, gson.toJson(value)))
    }

    private inline fun <T> List<T>.replaceOrAppend(value: T, sameId: (T) -> Boolean): List<T> {
        val index = indexOfFirst(sameId)
        if (index == -1) return this + value
        return toMutableList().apply { set(index, value) }
    }

    private companion object {
        const val DENIED = "denied"
    }
}
