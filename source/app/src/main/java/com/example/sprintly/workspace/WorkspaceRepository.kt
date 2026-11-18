package com.example.sprintly.workspace

import com.example.sprintly.auth.AuthRemote
import com.example.sprintly.auth.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepository @Inject internal constructor(
    auth: AuthRepository,
    remote: AuthRemote,
) {
    private val requests = WorkspaceRequests(auth, remote, WorkspaceApi::class.java)

    suspend fun listWorkspaces(): List<Workspace> =
        requests.data(WorkspaceApi::listWorkspaces) { entries -> entries.map { it.required().toModel() } }

    suspend fun getWorkspace(id: String): Workspace =
        requests.data({ getWorkspace(it, id) }, WorkspacePayload::toModel)

    suspend fun createWorkspace(name: String, description: String?): Workspace =
        requests.data({ createWorkspace(it, NamedEntryRequest(name, description)) }, WorkspacePayload::toModel)

    suspend fun updateWorkspace(id: String, name: String, description: String?): Workspace =
        requests.data({ updateWorkspace(it, id, NamedEntryRequest(name, description)) }, WorkspacePayload::toModel)

    suspend fun deleteWorkspace(id: String) = requests.request({ deleteWorkspace(it, id) }) {}

    suspend fun listMembers(workspaceId: String): List<Member> =
        requests.data({ listMembers(it, workspaceId) }) { entries -> entries.map { it.required().toModel() } }

    suspend fun findUser(workspaceId: String, email: String): DirectoryUser? =
        requests.request({ findUser(it, workspaceId, email) }) { it.required().data?.toModel() }

    suspend fun addMember(workspaceId: String, userId: String): Member =
        requests.data({ addMember(it, workspaceId, AddMemberRequest(userId)) }, MemberPayload::toModel)

    suspend fun removeMember(workspaceId: String, userId: String) =
        requests.request({ removeMember(it, workspaceId, userId) }) {}

    suspend fun listProjects(workspaceId: String): List<Project> =
        requests.data({ listProjects(it, workspaceId) }) { entries -> entries.map { it.required().toModel() } }

    suspend fun createProject(workspaceId: String, name: String, description: String?): Project =
        requests.data({ createProject(it, workspaceId, NamedEntryRequest(name, description)) }, ProjectPayload::toModel)

    suspend fun getProject(id: String): Project =
        requests.data({ getProject(it, id) }, ProjectPayload::toModel)

    suspend fun updateProject(id: String, name: String, description: String?): Project =
        requests.data({ updateProject(it, id, NamedEntryRequest(name, description)) }, ProjectPayload::toModel)

    suspend fun deleteProject(id: String) = requests.request({ deleteProject(it, id) }) {}

    suspend fun getBoard(projectId: String): Board =
        requests.data({ getBoard(it, projectId) }, BoardPayload::toModel)

    suspend fun addColumn(projectId: String, name: String): BoardColumn =
        requests.data({ addColumn(it, projectId, ColumnRequest(name)) }, BoardColumnPayload::toModel)

    suspend fun renameColumn(columnId: String, name: String): BoardColumn =
        requests.data({ renameColumn(it, columnId, ColumnRequest(name)) }, BoardColumnPayload::toModel)
}
