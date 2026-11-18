package com.example.sprintly.workspace

import com.example.sprintly.auth.AuthRemote
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.auth.AuthResult
import com.example.sprintly.auth.AuthState
import com.example.sprintly.auth.SessionStorage
import com.example.sprintly.auth.StoredSession
import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storage = TestStorage()
    private lateinit var auth: AuthRepository
    private lateinit var repository: WorkspaceRepository

    @Before
    fun setUp() = runBlocking {
        server.start()
        val remote = AuthRemote(server.url("/api/v1/").toString())
        auth = AuthRepository(remote, storage, scope)
        repository = WorkspaceRepository(auth, remote)
        server.enqueue(login("original"))
        signIn()
        assertRequest("POST", "/api/v1/auth/login", bearer = null)
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun providesWorkspaceMemberAndDirectoryDataWithRealRestRequests() = runBlocking {
        val expected = Workspace("w-1", "Studio", null, "OWNER")
        server.enqueue(data("[$workspaceJson]"))
        assertEquals(listOf(expected), repository.listWorkspaces())
        assertRequest("GET", "/api/v1/workspaces")

        server.enqueue(data(workspaceJson, 201))
        assertEquals(expected, repository.createWorkspace("Studio", null))
        val created = assertRequest("POST", "/api/v1/workspaces")
        assertBody(created, """{"name":"Studio","description":null}""")

        server.enqueue(data(workspaceJson))
        assertEquals(expected, repository.getWorkspace("w-1"))
        assertRequest("GET", "/api/v1/workspaces/w-1")

        server.enqueue(data(workspaceJson))
        assertEquals(expected, repository.updateWorkspace("w-1", "Studio", null))
        val updated = assertRequest("PATCH", "/api/v1/workspaces/w-1")
        assertBody(updated, """{"name":"Studio","description":null}""")

        val member = Member("u-2", "Pat", "pat+test@example.test", "MEMBER")
        server.enqueue(data("[$memberJson]"))
        assertEquals(listOf(member), repository.listMembers("w-1"))
        assertRequest("GET", "/api/v1/workspaces/w-1/members")

        server.enqueue(data("""{"id":"u-2","displayName":"Pat","email":"pat+test@example.test"}"""))
        assertEquals(DirectoryUser("u-2", "Pat", "pat+test@example.test"), repository.findUser("w-1", "pat+test@example.test"))
        val found = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", found.method)
        assertEquals("/api/v1/workspaces/w-1/users/search", found.requestUrl?.encodedPath)
        assertEquals("pat+test@example.test", found.requestUrl?.queryParameter("email"))
        assertEquals("Bearer original", found.getHeader("Authorization"))

        server.enqueue(data("null"))
        assertNull(repository.findUser("w-1", "nobody@example.test"))
        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        server.enqueue(data(memberJson, 201))
        assertEquals(member, repository.addMember("w-1", "u-2"))
        val added = assertRequest("POST", "/api/v1/workspaces/w-1/members")
        assertBody(added, """{"userId":"u-2","role":"MEMBER"}""")

        server.enqueue(noContent())
        repository.removeMember("w-1", "u-2")
        assertRequest("DELETE", "/api/v1/workspaces/w-1/members/u-2")

        server.enqueue(noContent())
        repository.deleteWorkspace("w-1")
        assertRequest("DELETE", "/api/v1/workspaces/w-1")
        Unit
    }

    @Test
    fun providesProjectBoardAndColumnDataWithRealRestRequests() = runBlocking {
        val project = Project("p-1", "w-1", "Launch", "Soon", 4_000_000_000)
        server.enqueue(data("[$projectJson]"))
        assertEquals(listOf(project), repository.listProjects("w-1"))
        assertRequest("GET", "/api/v1/workspaces/w-1/projects")

        server.enqueue(data(projectJson, 201))
        assertEquals(project, repository.createProject("w-1", "Launch", "Soon"))
        assertRequest("POST", "/api/v1/workspaces/w-1/projects")

        server.enqueue(data(projectJson))
        assertEquals(project, repository.getProject("p-1"))
        assertRequest("GET", "/api/v1/projects/p-1")

        server.enqueue(data(projectJson.replace("\"Soon\"", "null")))
        assertEquals(project.copy(description = null), repository.updateProject("p-1", "Launch", null))
        val updated = assertRequest("PATCH", "/api/v1/projects/p-1")
        assertBody(updated, """{"name":"Launch","description":null}""")

        val column = BoardColumn("c-1", "Todo", 0)
        server.enqueue(data("""{"boardVersion":4000000000,"columns":[$columnJson]}"""))
        assertEquals(Board(4_000_000_000, listOf(column)), repository.getBoard("p-1"))
        assertRequest("GET", "/api/v1/projects/p-1/board")

        server.enqueue(data(columnJson, 201))
        assertEquals(column, repository.addColumn("p-1", "Todo"))
        val added = assertRequest("POST", "/api/v1/projects/p-1/columns")
        assertBody(added, """{"name":"Todo"}""")

        server.enqueue(data(columnJson.replace("Todo", "Backlog")))
        assertEquals(column.copy(name = "Backlog"), repository.renameColumn("c-1", "Backlog"))
        assertRequest("PATCH", "/api/v1/columns/c-1")

        server.enqueue(noContent())
        repository.deleteProject("p-1")
        assertRequest("DELETE", "/api/v1/projects/p-1")
        Unit
    }

    @Test
    fun returnsForbiddenWithoutRefreshingOrSigningOut() = runBlocking {
        server.enqueue(error(403, "FORBIDDEN"))
        failure("FORBIDDEN", 403) { repository.deleteWorkspace("w-1") }
        assertRequest("DELETE", "/api/v1/workspaces/w-1")
        assertEquals(2, server.requestCount)
        assertTrue(auth.state.value is AuthState.SignedIn)
    }

    @Test
    fun concurrentWorkspaceRequestsShareOneRefreshAndRetryWithTheReplacement() = runBlocking {
        val originalRequests = CountDownLatch(2)
        val refreshes = AtomicInteger()
        val retried = AtomicInteger()
        server.dispatcher = routing { request ->
            when (request.path to request.getHeader("Authorization")) {
                "/api/v1/workspaces" to "Bearer original" -> {
                    originalRequests.countDown()
                    check(originalRequests.await(5, TimeUnit.SECONDS))
                    error(401, "UNAUTHORIZED")
                }
                "/api/v1/auth/refresh" to null -> {
                    refreshes.incrementAndGet()
                    refresh("replacement")
                }
                "/api/v1/workspaces" to "Bearer replacement" -> {
                    retried.incrementAndGet()
                    data("[$workspaceJson]")
                }
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }

        val results = awaitAll(async { repository.listWorkspaces() }, async { repository.listWorkspaces() })

        assertEquals(listOf(listOf("w-1"), listOf("w-1")), results.map { workspaces -> workspaces.map(Workspace::id) })
        assertEquals(1, refreshes.get())
        assertEquals(2, retried.get())
        assertEquals("refresh-replacement", storage.session?.refreshToken)
    }

    @Test
    fun retriesMutationOnlyOnceAndBlocksFurtherWritesAfterAnotherUnauthorizedResponse() = runBlocking {
        server.enqueue(error(401, "UNAUTHORIZED"))
        server.enqueue(refresh("replacement"))
        server.enqueue(error(401, "UNAUTHORIZED"))

        failure("AUTH_RETRY") { repository.createWorkspace("Studio", null) }

        assertRequest("POST", "/api/v1/workspaces")
        assertRequest("POST", "/api/v1/auth/refresh", bearer = null)
        assertRequest("POST", "/api/v1/workspaces", bearer = "replacement")
        failure("AUTH_RETRY") { repository.deleteProject("p-1") }
        assertEquals(4, server.requestCount)
        assertTrue((auth.state.value as AuthState.SignedIn).needsRetry)
    }

    @Test
    fun preservesSessionOnTransientRefreshFailureAndRequiresRevalidationBeforeWriting() = runBlocking {
        server.enqueue(error(401, "UNAUTHORIZED"))
        server.enqueue(error(503, "UNAVAILABLE"))

        failure("AUTH_RETRY") { repository.listWorkspaces() }
        failure("AUTH_RETRY") { repository.createProject("w-1", "Launch", null) }
        assertEquals(3, server.requestCount)
        assertEquals("refresh-original", storage.session?.refreshToken)
        assertRequest("GET", "/api/v1/workspaces")
        assertRequest("POST", "/api/v1/auth/refresh", bearer = null)

        server.enqueue(refresh("replacement"))
        server.enqueue(data(userJson))
        server.enqueue(data(projectJson, 201))
        auth.restore()
        assertEquals("p-1", repository.createProject("w-1", "Launch", null).id)
        assertRequest("POST", "/api/v1/auth/refresh", bearer = null)
        assertRequest("GET", "/api/v1/users/me", bearer = "replacement")
        assertRequest("POST", "/api/v1/workspaces/w-1/projects", bearer = "replacement")
        Unit
    }

    @Test
    fun invalidRefreshEndsTheSessionAndClearsSavedCredentials() = runBlocking {
        server.enqueue(error(401, "UNAUTHORIZED"))
        server.enqueue(error(401, "REFRESH_TOKEN_INVALID"))

        failure("SESSION_CHANGED") { repository.listWorkspaces() }

        assertEquals(AuthState.SignedOut(expired = true), auth.state.value)
        assertNull(storage.session)
        failure("UNAUTHORIZED") { repository.createProject("w-1", "Launch", null) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun discardsLateWorkspaceDataFromAnEarlierLoginForTheSameAccount() = runBlocking {
        val workspaceStarted = CompletableDeferred<Unit>()
        val finishWorkspace = CountDownLatch(1)
        server.dispatcher = routing { request ->
            when (request.path) {
                "/api/v1/workspaces" -> {
                    workspaceStarted.complete(Unit)
                    check(finishWorkspace.await(5, TimeUnit.SECONDS))
                    data("[$workspaceJson]")
                }
                "/api/v1/auth/logout" -> noContent()
                "/api/v1/auth/login" -> login("current")
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }

        val reading = async { failure("SESSION_CHANGED") { repository.listWorkspaces() } }
        try {
            withTimeout(5_000) { workspaceStarted.await() }
            auth.signOut()
            signIn()
        } finally {
            finishWorkspace.countDown()
        }
        reading.await()

        assertEquals("refresh-current", storage.session?.refreshToken)
    }

    @Test
    fun refreshFromEarlierLoginCannotRetryAMutationUnderReplacementAccount() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CountDownLatch(1)
        val writes = AtomicInteger()
        server.dispatcher = routing { request ->
            when (request.path) {
                "/api/v1/workspaces" -> {
                    writes.incrementAndGet()
                    error(401, "UNAUTHORIZED")
                }
                "/api/v1/auth/refresh" -> {
                    refreshStarted.complete(Unit)
                    check(finishRefresh.await(5, TimeUnit.SECONDS))
                    refresh("stale")
                }
                "/api/v1/auth/logout" -> noContent()
                "/api/v1/auth/login" -> login("current", userJson.replace("u-1", "u-2"))
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }

        val writing = async { failure("SESSION_CHANGED") { repository.createWorkspace("Studio", null) } }
        try {
            withTimeout(5_000) { refreshStarted.await() }
            signIn()
        } finally {
            finishRefresh.countDown()
        }
        writing.await()

        assertEquals(1, writes.get())
        assertEquals("refresh-current", storage.session?.refreshToken)
        assertEquals("u-2", storage.session?.user?.id)
    }

    private suspend fun signIn() {
        assertEquals(AuthResult.Success, auth.signIn("demo@example.test", "test-password"))
    }

    private suspend fun failure(code: String, status: Int? = null, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected WorkspaceFailure, got $failure", failure is WorkspaceFailure)
        failure as WorkspaceFailure
        assertEquals(code, failure.code)
        assertEquals(status, failure.status)
    }

    private fun assertRequest(method: String, path: String, bearer: String? = "original"): RecordedRequest {
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals(bearer?.let { "Bearer $it" }, request.getHeader("Authorization"))
        return request
    }

    private fun assertBody(request: RecordedRequest, expected: String) {
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(request.body.readUtf8()))
    }

    private fun login(token: String, user: String = userJson) = data(
        """{"user":$user,"accessToken":"$token","refreshToken":"refresh-$token","expiresIn":900}""",
    )

    private fun refresh(token: String) = data(
        """{"accessToken":"$token","refreshToken":"refresh-$token","expiresIn":900}""",
    )

    private fun noContent() = MockResponse().setResponseCode(204)

    private fun data(value: String, status: Int = 200) = json(status, """{"data":$value}""")

    private fun error(status: Int, code: String) = json(status, """{"error":{"code":"$code","message":"Test error"}}""")

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun routing(handler: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = handler(request)
    }

    private class TestStorage : SessionStorage {
        var session: StoredSession? = null

        override fun read() = session

        override fun write(session: StoredSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private companion object {
        const val userJson = """{"id":"u-1","displayName":"Demo","email":"demo@example.test"}"""
        const val workspaceJson = """{"id":"w-1","name":"Studio","description":null,"role":"OWNER"}"""
        const val memberJson = """{"id":"u-2","displayName":"Pat","email":"pat+test@example.test","role":"MEMBER"}"""
        const val projectJson = """{"id":"p-1","workspaceId":"w-1","name":"Launch","description":"Soon","boardVersion":4000000000}"""
        const val columnJson = """{"id":"c-1","name":"Todo","position":0,"tasks":[]}"""
    }
}
