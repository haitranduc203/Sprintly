package com.example.sprintly.workspace

import com.example.sprintly.auth.AccessToken
import com.example.sprintly.auth.AuthRemoteFailure
import com.example.sprintly.auth.Envelope
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface WorkspaceApi {
    @GET("workspaces")
    suspend fun listWorkspaces(@Tag access: AccessToken): Response<Envelope<List<WorkspacePayload?>>>

    @GET("workspaces/{workspaceId}")
    suspend fun getWorkspace(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
    ): Response<Envelope<WorkspacePayload>>

    @POST("workspaces")
    suspend fun createWorkspace(
        @Tag access: AccessToken,
        @Body request: NamedEntryRequest,
    ): Response<Envelope<WorkspacePayload>>

    @PATCH("workspaces/{workspaceId}")
    suspend fun updateWorkspace(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
        @Body request: NamedEntryRequest,
    ): Response<Envelope<WorkspacePayload>>

    @DELETE("workspaces/{workspaceId}")
    suspend fun deleteWorkspace(@Tag access: AccessToken, @Path("workspaceId") workspaceId: String): Response<Unit>

    @GET("workspaces/{workspaceId}/members")
    suspend fun listMembers(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
    ): Response<Envelope<List<MemberPayload?>>>

    @GET("workspaces/{workspaceId}/users/search")
    suspend fun findUser(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
        @Query("email") email: String,
    ): Response<Envelope<DirectoryUserPayload>>

    @POST("workspaces/{workspaceId}/members")
    suspend fun addMember(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
        @Body request: AddMemberRequest,
    ): Response<Envelope<MemberPayload>>

    @DELETE("workspaces/{workspaceId}/members/{userId}")
    suspend fun removeMember(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
        @Path("userId") userId: String,
    ): Response<Unit>

    @GET("workspaces/{workspaceId}/projects")
    suspend fun listProjects(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
    ): Response<Envelope<List<ProjectPayload?>>>

    @POST("workspaces/{workspaceId}/projects")
    suspend fun createProject(
        @Tag access: AccessToken,
        @Path("workspaceId") workspaceId: String,
        @Body request: NamedEntryRequest,
    ): Response<Envelope<ProjectPayload>>

    @GET("projects/{projectId}")
    suspend fun getProject(
        @Tag access: AccessToken,
        @Path("projectId") projectId: String,
    ): Response<Envelope<ProjectPayload>>

    @PATCH("projects/{projectId}")
    suspend fun updateProject(
        @Tag access: AccessToken,
        @Path("projectId") projectId: String,
        @Body request: NamedEntryRequest,
    ): Response<Envelope<ProjectPayload>>

    @DELETE("projects/{projectId}")
    suspend fun deleteProject(@Tag access: AccessToken, @Path("projectId") projectId: String): Response<Unit>

    @GET("projects/{projectId}/board")
    suspend fun getBoard(@Tag access: AccessToken, @Path("projectId") projectId: String): Response<Envelope<BoardPayload>>

    @POST("projects/{projectId}/columns")
    suspend fun addColumn(
        @Tag access: AccessToken,
        @Path("projectId") projectId: String,
        @Body request: ColumnRequest,
    ): Response<Envelope<BoardColumnPayload>>

    @PATCH("columns/{columnId}")
    suspend fun renameColumn(
        @Tag access: AccessToken,
        @Path("columnId") columnId: String,
        @Body request: ColumnRequest,
    ): Response<Envelope<BoardColumnPayload>>
}

internal class NamedEntryRequest(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
)

internal class AddMemberRequest(@SerializedName("userId") val userId: String) {
    @SerializedName("role") val role = "MEMBER"
}

internal class ColumnRequest(@SerializedName("name") val name: String)

internal class WorkspacePayload(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("role") val role: String?,
) {
    fun toModel() = Workspace(id.required(), name.required(), description, role.required())
}

internal class MemberPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("role") val role: String?,
) {
    fun toModel() = Member(id.required(), displayName.required(), email.required(), role.required())
}

internal class DirectoryUserPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("email") val email: String?,
) {
    fun toModel() = DirectoryUser(id.required(), displayName.required(), email.required())
}

internal class ProjectPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("workspaceId") val workspaceId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("boardVersion") val boardVersion: Long?,
) {
    fun toModel() = Project(id.required(), workspaceId.required(), name.required(), description, boardVersion.required())
}

internal class BoardPayload(
    @SerializedName("boardVersion") val boardVersion: Long?,
    @SerializedName("columns") val columns: List<BoardColumnPayload?>?,
) {
    fun toModel() = Board(boardVersion.required(), columns.required().map { it.required().toModel() })
}

internal class BoardColumnPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("position") val position: Int?,
) {
    fun toModel() = BoardColumn(id.required(), name.required(), position.required())
}

internal fun <T : Any> T?.required(): T = this ?: throw AuthRemoteFailure("SERVER_ERROR")
