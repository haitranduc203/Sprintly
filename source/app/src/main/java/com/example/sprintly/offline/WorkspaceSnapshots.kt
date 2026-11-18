package com.example.sprintly.offline

import android.database.sqlite.SQLiteException
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.workspace.WorkspaceFailure
import com.example.sprintly.workspace.WorkspaceRepository
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
internal class WorkspaceSnapshots @Inject constructor(
    private val auth: AuthRepository,
    private val remote: WorkspaceRepository,
    private val cache: OfflineCache,
    private val connectivity: OfflineConnectivity,
) {
    private val lists = Mutex()
    private val invalidations = AtomicLong()

    fun sessionId(): String? = auth.cacheSessionId()

    suspend fun cachedWorkspaces() = cached(cache::workspaces)
    suspend fun cachedWorkspace(workspace: String) = cached { cache.workspace(it, workspace) }
    suspend fun cachedProjects(workspace: String) = cached { cache.projects(it, workspace) }
    suspend fun cachedMembers(workspace: String) = cached { cache.members(it, workspace) }
    suspend fun cachedProject(workspace: String, project: String) = cached { cache.project(it, workspace, project) }
    suspend fun cachedBoard(workspace: String, project: String) = cached { cache.board(it, workspace, project) }

    suspend fun refreshWorkspaces() = lists.withLock { refresh(remote::listWorkspaces, cache::saveWorkspaces) }

    suspend fun refreshWorkspace(workspace: String) = refresh(
        load = { remote.getWorkspace(workspace) },
        save = cache::saveWorkspace,
        remove = { cache.removeWorkspace(it, workspace) },
    )

    suspend fun refreshProjects(workspace: String) = lists.withLock {
        refresh(
            load = { remote.listProjects(workspace) },
            save = { session, projects -> cache.saveProjects(session, workspace, projects) },
            remove = { cache.removeWorkspace(it, workspace) },
        )
    }

    suspend fun refreshMembers(workspace: String) = refresh(
        load = { remote.listMembers(workspace) },
        save = { session, members -> cache.saveMembers(session, workspace, members) },
        remove = { cache.removeWorkspace(it, workspace) },
    )

    suspend fun refreshProject(workspace: String, project: String) = refresh(
        load = { remote.getProject(project) },
        save = cache::saveProject,
        remove = { cache.removeProject(it, workspace, project) },
    )

    suspend fun refreshBoard(workspace: String, project: String) = refresh(
        load = { remote.getBoard(project) },
        save = { session, board -> cache.saveBoard(session, workspace, project, board) },
        remove = { cache.removeProject(it, workspace, project) },
    )

    suspend fun removeWorkspace(workspace: String, session: String? = sessionId()) {
        invalidate(session) { cache.removeWorkspace(it, workspace) }
    }

    suspend fun removeProject(workspace: String, project: String, session: String? = sessionId()) {
        invalidate(session) { cache.removeProject(it, workspace, project) }
    }

    suspend fun removeTask(workspace: String, project: String, task: String, session: String? = sessionId()) {
        invalidate(session) { cache.removeTask(it, workspace, project, task) }
    }

    private suspend fun <T> cached(read: suspend (String) -> T?): T? = cacheAccess(sessionId(), read)

    private suspend fun <T : Any> refresh(
        load: suspend () -> T,
        save: suspend (String, T) -> Unit,
        remove: (suspend (String) -> Unit)? = null,
    ): T {
        val session = sessionId() ?: throw WorkspaceFailure("AUTH_RETRY")
        val generation = invalidations.get()
        val value = try {
            loadWithNetworkRetry(session, load)
        } catch (failure: WorkspaceFailure) {
            if (remove != null && (failure.status == 403 || failure.status == 404)) invalidate(session, remove)
            throw failure
        }
        cacheAccess(session) {
            if (invalidations.get() == generation) save(it, value)
        }
        if (sessionId() != session) throw WorkspaceFailure("SESSION_CHANGED")
        return value
    }

    private suspend fun <T> loadWithNetworkRetry(session: String, load: suspend () -> T): T = try {
        load()
    } catch (failure: WorkspaceFailure) {
        // These snapshots come from GET requests, which can be repeated when a connection was just lost.
        if (failure.code != "NETWORK_ERROR" || !connectivity.isConnected()) throw failure
        delay(700)
        if (sessionId() != session) throw WorkspaceFailure("SESSION_CHANGED")
        if (!connectivity.isConnected()) throw failure
        load()
    }

    private suspend fun invalidate(session: String?, remove: suspend (String) -> Unit) {
        cacheAccess(session) {
            // An older response must not repopulate a scope after confirmed access loss or deletion.
            invalidations.incrementAndGet()
            remove(it)
        }
    }

    private suspend fun <T> cacheAccess(session: String?, operation: suspend (String) -> T): T? {
        if (session == null) return null
        return try {
            auth.withSessionCache(session) { operation(session) }
        } catch (_: SQLiteException) {
            // Keep online reads available when the local cache cannot be opened or written.
            null
        }
    }
}
