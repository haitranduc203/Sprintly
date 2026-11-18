package com.example.sprintly.workspace

data class Workspace(
    val id: String,
    val name: String,
    val description: String?,
    val role: String,
)

data class Member(val id: String, val displayName: String, val email: String, val role: String)

data class DirectoryUser(val id: String, val displayName: String, val email: String)

data class Project(
    val id: String,
    val workspaceId: String,
    val name: String,
    val description: String?,
    val boardVersion: Long,
)

data class Board(val boardVersion: Long, val columns: List<BoardColumn>)

data class BoardColumn(val id: String, val name: String, val position: Int)

class WorkspaceFailure(val code: String, val status: Int? = null) : Exception(code)
