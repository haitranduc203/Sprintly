package com.example.sprintly.tasks

data class Task(
    val id: String,
    val projectId: String,
    val columnId: String,
    val title: String,
    val description: String?,
    val priority: String,
    val assigneeId: String?,
    val assignee: TaskPerson?,
    val dueAt: String?,
    val position: Int,
    val version: Long,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
    val boardVersion: Long? = null,
)

data class TaskInput(
    val columnId: String,
    val title: String,
    val description: String?,
    val priority: String,
    val assigneeId: String?,
    val dueAt: String?,
)

data class TaskPerson(val id: String, val displayName: String, val email: String)

data class TaskComment(val id: String, val author: TaskPerson, val body: String, val createdAt: String)

data class TaskActivity(
    val id: String,
    val actor: TaskPerson,
    val action: String,
    val payload: Map<String, Any?>?,
    val createdAt: String,
)
