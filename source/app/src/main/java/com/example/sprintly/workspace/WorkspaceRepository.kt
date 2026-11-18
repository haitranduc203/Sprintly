package com.example.sprintly.workspace

import com.example.sprintly.auth.AccessToken
import com.example.sprintly.auth.AuthRemote
import com.example.sprintly.auth.AuthRemoteFailure
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.auth.Envelope
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

@Singleton
class WorkspaceRepository @Inject internal constructor(
    private val auth: AuthRepository,
    private val remote: AuthRemote,
) {
    private val service = remote.authenticatedApi(WorkspaceApi::class.java)

    suspend fun listWorkspaces(): List<Workspace> =
        data(WorkspaceApi::listWorkspaces) { entries -> entries.map { it.required().toModel() } }

    suspend fun getWorkspace(id: String): Workspace =
        data({ getWorkspace(it, id) }, WorkspacePayload::toModel)

    suspend fun createWorkspace(name: String, description: String?): Workspace =
        data({ createWorkspace(it, NamedEntryRequest(name, description)) }, WorkspacePayload::toModel)

    suspend fun updateWorkspace(id: String, name: String, description: String?): Workspace =
        data({ updateWorkspace(it, id, NamedEntryRequest(name, description)) }, WorkspacePayload::toModel)

    suspend fun deleteWorkspace(id: String) = request({ deleteWorkspace(it, id) }) {}

    suspend fun listMembers(workspaceId: String): List<Member> =
        data({ listMembers(it, workspaceId) }) { entries -> entries.map { it.required().toModel() } }

    suspend fun findUser(workspaceId: String, email: String): DirectoryUser? =
        request({ findUser(it, workspaceId, email) }) { it.required().data?.toModel() }

    suspend fun addMember(workspaceId: String, userId: String): Member =
        data({ addMember(it, workspaceId, AddMemberRequest(userId)) }, MemberPayload::toModel)

    suspend fun removeMember(workspaceId: String, userId: String) = request({ removeMember(it, workspaceId, userId) }) {}

    suspend fun listProjects(workspaceId: String): List<Project> =
        data({ listProjects(it, workspaceId) }) { entries -> entries.map { it.required().toModel() } }

    suspend fun createProject(workspaceId: String, name: String, description: String?): Project =
        data({ createProject(it, workspaceId, NamedEntryRequest(name, description)) }, ProjectPayload::toModel)

    suspend fun getProject(id: String): Project =
        data({ getProject(it, id) }, ProjectPayload::toModel)

    suspend fun updateProject(id: String, name: String, description: String?): Project =
        data({ updateProject(it, id, NamedEntryRequest(name, description)) }, ProjectPayload::toModel)

    suspend fun deleteProject(id: String) = request({ deleteProject(it, id) }) {}

    suspend fun getBoard(projectId: String): Board =
        data({ getBoard(it, projectId) }, BoardPayload::toModel)

    suspend fun addColumn(projectId: String, name: String): BoardColumn =
        data({ addColumn(it, projectId, ColumnRequest(name)) }, BoardColumnPayload::toModel)

    suspend fun renameColumn(columnId: String, name: String): BoardColumn =
        data({ renameColumn(it, columnId, ColumnRequest(name)) }, BoardColumnPayload::toModel)

    private suspend fun <Payload : Any, Model> data(
        operation: suspend WorkspaceApi.(AccessToken) -> Response<Envelope<Payload>>,
        toModel: (Payload) -> Model,
    ): Model = request(operation) { toModel(it?.data.required()) }

    private suspend fun <Body, Model> request(
        operation: suspend WorkspaceApi.(AccessToken) -> Response<Body>,
        toModel: (Body?) -> Model,
    ): Model = try {
        auth.withAccess { access ->
            val api = service ?: throw AuthRemoteFailure("API_NOT_CONFIGURED")
            toModel(remote.execute { api.operation(access) })
        }
    } catch (failure: AuthRemoteFailure) {
        throw WorkspaceFailure(failure.code, failure.status)
    }
}
