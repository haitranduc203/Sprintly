package com.example.sprintly.tasks

import com.example.sprintly.auth.AuthRemote
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.workspace.WorkspaceRequests
import com.example.sprintly.workspace.required
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject internal constructor(auth: AuthRepository, remote: AuthRemote) {
    private val requests = WorkspaceRequests(auth, remote, TaskApi::class.java)

    suspend fun listTasks(
        projectId: String,
        q: String? = null,
        columnId: String? = null,
        assigneeId: String? = null,
        priority: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): List<Task> = requests.data({ listTasks(it, projectId, q, columnId, assigneeId, priority, page, pageSize) }) { tasks ->
        tasks.map { it.required().toModel() }
    }

    suspend fun createTask(projectId: String, input: TaskInput, idempotencyKey: String): Task {
        val request = input.toRequest()
        return requests.data({ createTask(it, projectId, idempotencyKey, request) }, TaskPayload::toModel)
    }

    suspend fun getTask(id: String): Task = requests.data({ getTask(it, id) }, TaskPayload::toModel)

    suspend fun updateTask(id: String, input: TaskInput, expectedVersion: Long, expectedBoardVersion: Long?): Task {
        val request = input.toRequest().apply {
            addProperty("expectedVersion", expectedVersion)
            expectedBoardVersion?.let { addProperty("expectedBoardVersion", it) }
        }
        return requests.data({ updateTask(it, id, request) }, TaskPayload::toModel)
    }

    suspend fun deleteTask(id: String): Unit = requests.request({ deleteTask(it, id) }) {}

    suspend fun moveTask(
        id: String,
        targetColumnId: String,
        targetIndex: Int,
        expectedVersion: Long,
        expectedBoardVersion: Long,
    ): Task {
        val request = MoveTaskRequest(targetColumnId, targetIndex, expectedVersion, expectedBoardVersion)
        return requests.data({ moveTask(it, id, request) }, TaskPayload::toModel)
    }

    suspend fun listComments(id: String, page: Int = 1): List<TaskComment> =
        requests.data({ listComments(it, id, page) }) { comments -> comments.map { it.required().toModel() } }

    suspend fun createComment(id: String, body: String, idempotencyKey: String): TaskComment =
        requests.data({ createComment(it, id, idempotencyKey, CommentRequest(body)) }, TaskCommentPayload::toModel)

    suspend fun listActivities(id: String, page: Int = 1): List<TaskActivity> =
        requests.data({ listActivities(it, id, page) }) { activities -> activities.map { it.required().toModel() } }
}
