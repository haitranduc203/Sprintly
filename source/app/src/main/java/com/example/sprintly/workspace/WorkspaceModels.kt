package com.example.sprintly.workspace

import androidx.annotation.Keep
import com.example.sprintly.tasks.Task

@Keep
data class Workspace(
    val id: String,
    val name: String,
    val description: String?,
    val role: String,
)

@Keep
data class Member(val id: String, val displayName: String, val email: String, val role: String)

data class DirectoryUser(val id: String, val displayName: String, val email: String)

@Keep
data class Project(
    val id: String,
    val workspaceId: String,
    val name: String,
    val description: String?,
    val boardVersion: Long,
)

@Keep
data class Board(val boardVersion: Long, val columns: List<BoardColumn>)

@Keep
data class BoardColumn(
    val id: String,
    val name: String,
    val position: Int,
    val tasks: List<Task> = emptyList(),
)

class WorkspaceFailure(val code: String, val status: Int? = null) : Exception(code)
