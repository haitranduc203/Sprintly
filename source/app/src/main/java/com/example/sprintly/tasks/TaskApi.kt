package com.example.sprintly.tasks

import com.example.sprintly.auth.AccessToken
import com.example.sprintly.auth.Envelope
import com.example.sprintly.workspace.required
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface TaskApi {
    @GET("projects/{projectId}/tasks")
    suspend fun listTasks(
        @Tag access: AccessToken,
        @Path("projectId") projectId: String,
        @Query("q") q: String?,
        @Query("columnId") columnId: String?,
        @Query("assigneeId") assigneeId: String?,
        @Query("priority") priority: String?,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): Response<Envelope<List<TaskPayload?>>>

    @POST("projects/{projectId}/tasks")
    suspend fun createTask(
        @Tag access: AccessToken,
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: JsonObject,
    ): Response<Envelope<TaskPayload>>

    @GET("tasks/{taskId}")
    suspend fun getTask(
        @Tag access: AccessToken,
        @Path("taskId") taskId: String,
    ): Response<Envelope<TaskPayload>>

    @PATCH("tasks/{taskId}")
    suspend fun updateTask(
        @Tag access: AccessToken,
        @Path("taskId") taskId: String,
        @Body request: JsonObject,
    ): Response<Envelope<TaskPayload>>

    @DELETE("tasks/{taskId}")
    suspend fun deleteTask(@Tag access: AccessToken, @Path("taskId") taskId: String): Response<Unit>

    @PATCH("tasks/{taskId}/move")
    suspend fun moveTask(
        @Tag access: AccessToken,
        @Path("taskId") taskId: String,
        @Body request: MoveTaskRequest,
    ): Response<Envelope<TaskPayload>>

    @GET("tasks/{taskId}/comments")
    suspend fun listComments(
        @Tag access: AccessToken,
        @Path("taskId") taskId: String,
        @Query("page") page: Int,
    ): Response<Envelope<List<TaskCommentPayload?>>>

    @POST("tasks/{taskId}/comments")
    suspend fun createComment(
        @Tag access: AccessToken,
        @Path("taskId") taskId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CommentRequest,
    ): Response<Envelope<TaskCommentPayload>>

    @GET("tasks/{taskId}/activities")
    suspend fun listActivities(
        @Tag access: AccessToken,
        @Path("taskId") taskId: String,
        @Query("page") page: Int,
    ): Response<Envelope<List<TaskActivityPayload?>>>
}

internal fun TaskInput.toRequest() = JsonObject().apply {
    addProperty("columnId", columnId)
    addProperty("title", title)
    addProperty("description", description)
    addProperty("priority", priority)
    addProperty("assigneeId", assigneeId)
    addProperty("dueAt", dueAt)
}

internal class MoveTaskRequest(
    @SerializedName("targetColumnId") val targetColumnId: String,
    @SerializedName("targetIndex") val targetIndex: Int,
    @SerializedName("expectedVersion") val expectedVersion: Long,
    @SerializedName("expectedBoardVersion") val expectedBoardVersion: Long,
)

internal class CommentRequest(@SerializedName("body") val body: String)

internal class TaskPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("projectId") val projectId: String?,
    @SerializedName("columnId") val columnId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("priority") val priority: String?,
    @SerializedName("assigneeId") val assigneeId: String?,
    @SerializedName("assignee") val assignee: TaskPersonPayload?,
    @SerializedName("dueAt") val dueAt: String?,
    @SerializedName("position") val position: Int?,
    @SerializedName("version") val version: Long?,
    @SerializedName("createdBy") val createdBy: String?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?,
    @SerializedName("boardVersion") val boardVersion: Long?,
) {
    fun toModel() = Task(
        id = id.required(),
        projectId = projectId.required(),
        columnId = columnId.required(),
        title = title.required(),
        description = description,
        priority = priority.required(),
        assigneeId = assigneeId,
        assignee = assignee?.toModel(),
        dueAt = dueAt,
        position = position.required(),
        version = version.required(),
        createdBy = createdBy.required(),
        createdAt = createdAt.required(),
        updatedAt = updatedAt.required(),
        boardVersion = boardVersion,
    )
}

internal class TaskPersonPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("email") val email: String?,
) {
    fun toModel() = TaskPerson(id.required(), displayName.required(), email.required())
}

internal class TaskCommentPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("author") val author: TaskPersonPayload?,
    @SerializedName("body") val body: String?,
    @SerializedName("createdAt") val createdAt: String?,
) {
    fun toModel() = TaskComment(id.required(), author.required().toModel(), body.required(), createdAt.required())
}

internal class TaskActivityPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("actor") val actor: TaskPersonPayload?,
    @SerializedName("action") val action: String?,
    @SerializedName("payload") val payload: Map<String, Any?>?,
    @SerializedName("createdAt") val createdAt: String?,
) {
    fun toModel() = TaskActivity(id.required(), actor.required().toModel(), action.required(), payload, createdAt.required())
}
