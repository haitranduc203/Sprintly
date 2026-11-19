package com.example.sprintly.tasks

import com.example.sprintly.auth.AuthRemote
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.auth.AuthResult
import com.example.sprintly.auth.SessionStorage
import com.example.sprintly.auth.StoredSession
import com.example.sprintly.workspace.WorkspaceFailure
import com.example.sprintly.workspace.WorkspaceRepository
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: TaskRepository
    private lateinit var workspaces: WorkspaceRepository

    @Before
    fun setUp() = runBlocking {
        server.start()
        val remote = AuthRemote(server.url("/api/v1/").toString())
        val auth = AuthRepository(remote, MemorySessions(), scope)
        repository = TaskRepository(auth, remote)
        workspaces = WorkspaceRepository(auth, remote)
        server.enqueue(data("""{
            "user":$personJson,"accessToken":"original","refreshToken":"refresh-original","expiresIn":900
        }"""))
        assertEquals(AuthResult.Success, auth.signIn("pat@example.test", "test-password"))
        request("POST", "/api/v1/auth/login", bearer = null)
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun decodesTasksInServerBoardOrderAndKeepsEmptyColumns() = runBlocking {
        val secondTask = taskJson.replace("t-1", "t-2").replace("\"position\":0", "\"position\":1")
        server.enqueue(data("""{
            "boardVersion":4000000001,"columns":[
                {"id":"c-1","name":"To Do","position":0,"tasks":[$taskJson,$secondTask]},
                {"id":"c-2","name":"In Progress","position":1,"tasks":[]},
                {"id":"c-3","name":"Done","position":2}
            ]
        }"""))

        val board = workspaces.getBoard("p-1")

        assertEquals(4_000_000_001L, board.boardVersion)
        assertEquals(listOf("t-1", "t-2"), board.columns.first().tasks.map(Task::id))
        assertEquals(task, board.columns.first().tasks.first())
        assertTrue(board.columns.drop(1).all { it.tasks.isEmpty() })
        request("GET", "/api/v1/projects/p-1/board")
        Unit
    }

    @Test
    fun taskSearchEncodesFiltersAndPagination() = runBlocking {
        server.enqueue(json(200, """{"data":[$taskJson],"meta":{"page":2,"pageSize":10,"total":12}}"""))

        val page = repository.listTasksPage("p-1", "A+B checklist", "c-1", "u-1", "HIGH", 2, 10)
        assertEquals(TaskSearchPage(listOf(task), page = 2, pageSize = 10, total = 12), page)
        assertFalse(page.hasMore)

        val url = requireNotNull(request("GET", "/api/v1/projects/p-1/tasks").requestUrl)
        assertEquals("A+B checklist", url.queryParameter("q"))
        assertEquals("c-1", url.queryParameter("columnId"))
        assertEquals("u-1", url.queryParameter("assigneeId"))
        assertEquals("HIGH", url.queryParameter("priority"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("10", url.queryParameter("pageSize"))
    }

    @Test
    fun taskCreateKeepsTheSameIdempotencyKeyAndPayloadAfterTokenRefresh() = runBlocking {
        server.enqueue(error(401, "UNAUTHORIZED"))
        server.enqueue(data("""{"accessToken":"replacement","refreshToken":"refresh-replacement","expiresIn":900}"""))
        server.enqueue(data(taskJson, 201))

        assertEquals(task, repository.createTask("p-1", taskInput, idempotencyKey))

        val initial = request("POST", "/api/v1/projects/p-1/tasks")
        request("POST", "/api/v1/auth/refresh", bearer = null)
        val retried = request("POST", "/api/v1/projects/p-1/tasks", bearer = "replacement")
        assertEquals(idempotencyKey, initial.getHeader("Idempotency-Key"))
        assertEquals(idempotencyKey, retried.getHeader("Idempotency-Key"))
        val expectedBody = """{
            "columnId":"c-1","title":"Checklist","description":"Release notes","priority":"HIGH",
            "assigneeId":"u-1","dueAt":"2026-11-22T00:00:00.000Z"
        }"""
        assertBody(initial, expectedBody)
        assertBody(retried, expectedBody)
    }

    @Test
    fun loadsSingleTaskAndDeletesItWithNoResponseBody() = runBlocking {
        server.enqueue(data(taskJson))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(task, repository.getTask("t-1"))
        repository.deleteTask("t-1")

        request("GET", "/api/v1/tasks/t-1")
        request("DELETE", "/api/v1/tasks/t-1")
        Unit
    }

    @Test
    fun patchSendsExplicitNullsWhenClearingOptionalTaskFields() = runBlocking {
        server.enqueue(data(taskJson))
        val cleared = taskInput.copy(description = null, assigneeId = null, dueAt = null)

        repository.updateTask("t-1", cleared, expectedVersion = 3, expectedBoardVersion = null)

        val patch = request("PATCH", "/api/v1/tasks/t-1")
        assertBody(patch, """{
            "columnId":"c-1","title":"Checklist","description":null,"priority":"HIGH",
            "assigneeId":null,"dueAt":null,"expectedVersion":3
        }""")
    }

    @Test
    fun patchSendsTheBoardVersionWhenChangingColumns() = runBlocking {
        server.enqueue(data(taskJson.replace("c-1", "c-2")))

        val result = repository.updateTask("t-1", taskInput.copy(columnId = "c-2"), 3, 4_000_000_000L)

        assertEquals("c-2", result.columnId)
        assertBody(request("PATCH", "/api/v1/tasks/t-1"), """{
            "columnId":"c-2","title":"Checklist","description":"Release notes","priority":"HIGH",
            "assigneeId":"u-1","dueAt":"2026-11-22T00:00:00.000Z","expectedVersion":3,
            "expectedBoardVersion":4000000000
        }""")
    }

    @Test
    fun moveSendsTargetAndBothVersionsAndReadsTheUpdatedBoardVersion() = runBlocking {
        val movedJson = taskJson.replace("c-1", "c-2").replace("\"version\":3", "\"version\":4")
        server.enqueue(data(movedJson.dropLast(1) + """, "boardVersion":4000000001}"""))

        val moved = repository.moveTask("t-1", "c-2", 0, 3, 4_000_000_000L)

        assertEquals(task.copy(columnId = "c-2", version = 4, boardVersion = 4_000_000_001L), moved)
        assertBody(request("PATCH", "/api/v1/tasks/t-1/move"), """{
            "targetColumnId":"c-2","targetIndex":0,"expectedVersion":3,"expectedBoardVersion":4000000000
        }""")
    }

    @Test
    fun exposesMoveConflictsAndInvalidTargetsWithoutReissuingTheRequest() = runBlocking {
        val failures = listOf(
            Triple(409, "TASK_VERSION_CONFLICT", "TASK_VERSION_CONFLICT"),
            Triple(409, "BOARD_VERSION_CONFLICT", "BOARD_VERSION_CONFLICT"),
            Triple(422, "INVALID_TARGET_INDEX", "INVALID_TARGET_INDEX"),
            Triple(500, "UNEXPECTED", "SERVER_ERROR"),
        )
        for ((status, responseCode, expectedCode) in failures) {
            server.enqueue(error(status, responseCode))
            val failure = runCatching { repository.moveTask("t-1", "c-2", 9, 3, 7) }.exceptionOrNull()

            assertTrue("Expected WorkspaceFailure, got $failure", failure is WorkspaceFailure)
            assertEquals(expectedCode, (failure as WorkspaceFailure).code)
            assertEquals(status, failure.status)
            request("PATCH", "/api/v1/tasks/t-1/move")
        }
        assertEquals(1 + failures.size, server.requestCount)
    }

    @Test
    fun createsAndPagesCommentsWithTheirAuthorAndIdempotencyKey() = runBlocking {
        val expected = TaskComment("comment-1", person, "Ready to ship", "2026-11-18T08:00:00.000Z")
        server.enqueue(data(commentJson, 201))
        server.enqueue(data("[$commentJson]"))

        assertEquals(expected, repository.createComment("t-1", "Ready to ship", idempotencyKey))
        assertEquals(listOf(expected), repository.listComments("t-1", page = 2))

        val created = request("POST", "/api/v1/tasks/t-1/comments")
        assertEquals(idempotencyKey, created.getHeader("Idempotency-Key"))
        assertBody(created, """{"body":"Ready to ship"}""")
        assertEquals("2", request("GET", "/api/v1/tasks/t-1/comments").requestUrl?.queryParameter("page"))
    }

    @Test
    fun pagesActivitiesWithTheirActorAndOptionalPayload() = runBlocking {
        val createdAt = "2026-11-18T08:00:00.000Z"
        server.enqueue(data("""[
            {"id":"activity-2","actor":$personJson,"action":"MOVED","payload":{"targetColumnId":"c-2"},"createdAt":"$createdAt"},
            {"id":"activity-1","actor":$personJson,"action":"CREATED","payload":null,"createdAt":"$createdAt"}
        ]"""))

        val activities = repository.listActivities("t-1", page = 3)

        assertEquals(TaskActivity("activity-2", person, "MOVED", mapOf("targetColumnId" to "c-2"), createdAt), activities.first())
        assertNull(activities[1].payload)
        assertEquals("3", request("GET", "/api/v1/tasks/t-1/activities").requestUrl?.queryParameter("page"))
    }

    private fun request(method: String, path: String, bearer: String? = "original"): RecordedRequest {
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(method, request.method)
        assertEquals(path, request.requestUrl?.encodedPath)
        assertEquals(bearer?.let { "Bearer $it" }, request.getHeader("Authorization"))
        return request
    }

    private fun assertBody(request: RecordedRequest, expected: String) {
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(request.body.readUtf8()))
    }

    private fun data(value: String, status: Int = 200) = json(status, """{"data":$value}""")

    private fun error(status: Int, code: String) = json(status, """{"error":{"code":"$code","message":"Task error"}}""")

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class MemorySessions : SessionStorage {
        private var session: StoredSession? = null

        override fun read() = session

        override fun write(session: StoredSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private companion object {
        const val idempotencyKey = "b8b11417-bd0e-4d25-a091-c7255a35341f"
        const val personJson = """{"id":"u-1","displayName":"Pat","email":"pat@example.test"}"""
        const val taskJson = """{
            "id":"t-1","projectId":"p-1","columnId":"c-1","title":"Checklist","description":"Release notes",
            "priority":"HIGH","assigneeId":"u-1","assignee":$personJson,"dueAt":"2026-11-22T00:00:00.000Z",
            "position":0,"version":3,"createdBy":"u-1","createdAt":"2026-11-18T08:00:00.000Z",
            "updatedAt":"2026-11-18T09:00:00.000Z"
        }"""
        const val commentJson = """{
            "id":"comment-1","author":$personJson,"body":"Ready to ship","createdAt":"2026-11-18T08:00:00.000Z"
        }"""

        val person = TaskPerson("u-1", "Pat", "pat@example.test")
        val taskInput = TaskInput("c-1", "Checklist", "Release notes", "HIGH", "u-1", "2026-11-22T00:00:00.000Z")
        val task = Task(
            id = "t-1",
            projectId = "p-1",
            columnId = "c-1",
            title = "Checklist",
            description = "Release notes",
            priority = "HIGH",
            assigneeId = "u-1",
            assignee = person,
            dueAt = "2026-11-22T00:00:00.000Z",
            position = 0,
            version = 3,
            createdBy = "u-1",
            createdAt = "2026-11-18T08:00:00.000Z",
            updatedAt = "2026-11-18T09:00:00.000Z",
            boardVersion = null,
        )
    }
}
