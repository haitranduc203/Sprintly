package com.example.sprintly.auth

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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val user = AuthUser("user-1", "demo@example.test", "Demo")
    private val storage = InMemorySessionStorage()
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server.start()
        repository = AuthRepository(AuthRemote(server.url("/api/v1/").toString()), storage, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun transientRefreshFailurePreservesSessionAndSuccessfulRetryRotatesIt() = runBlocking {
        storage.session = StoredSession(user, "refresh-original")
        server.enqueue(error(503, "UNAVAILABLE"))
        server.enqueue(refresh("access-replacement", "refresh-replacement"))
        server.enqueue(profile())

        repository.restore()

        assertEquals(AuthState.SignedIn(user, needsRetry = true), repository.state.value)
        assertEquals("refresh-original", storage.session?.refreshToken)

        repository.restore()

        assertEquals(AuthState.SignedIn(user), repository.state.value)
        assertEquals("refresh-replacement", storage.session?.refreshToken)
        assertEquals("/api/v1/auth/refresh", takeRequest().path)
        val retry = takeRequest()
        assertEquals("""{"refreshToken":"refresh-original"}""", retry.body.readUtf8())
        assertNull(retry.getHeader("Authorization"))
        assertEquals("Bearer access-replacement", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun networkRefreshFailurePreservesOfflineSession() = runBlocking {
        storage.session = StoredSession(user, "refresh-original")
        val cacheId = storage.session?.cacheId
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        repository.restore()

        assertEquals(AuthState.SignedIn(user, needsRetry = true), repository.state.value)
        assertEquals("refresh-original", storage.session?.refreshToken)
        assertEquals(cacheId, repository.cacheSessionId())
    }

    @Test
    fun logoutClosesCacheBeforeCleanupAndSameAccountGetsANewNamespace() = runBlocking {
        val cleared = CompletableDeferred<String>()
        repository = AuthRepository(AuthRemote(server.url("/api/v1/").toString()), storage, scope) {
            cleared.complete(it)
        }
        server.dispatcher = routing { request ->
            when (request.path) {
                "/api/v1/auth/login" -> login("access", "refresh")
                "/api/v1/auth/logout" -> MockResponse().setResponseCode(204)
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }
        signIn()
        val firstLogin = checkNotNull(repository.cacheSessionId())
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldRead = async {
            repository.withSessionCache(firstLogin) {
                reading.complete(Unit)
                release.await()
                "saved board"
            }
        }
        withTimeout(5_000) { reading.await() }
        repository.signOut()
        assertNull(repository.cacheSessionId())
        release.complete(Unit)
        assertNull(oldRead.await())
        assertEquals(firstLogin, withTimeout(5_000) { cleared.await() })

        signIn()
        assertEquals("visible", repository.withSessionCache(checkNotNull(repository.cacheSessionId())) { "visible" })
        assertNull(repository.withSessionCache(firstLogin) { "previous login" })
    }

    @Test
    fun rejectedRefreshClearsSessionAndLeavesExpiredStateForSignIn() = runBlocking {
        storage.session = StoredSession(user, "refresh-expired")
        server.enqueue(error(401, "REFRESH_TOKEN_INVALID"))

        repository.restore()
        repository.restore()

        assertEquals(AuthState.SignedOut(expired = true), repository.state.value)
        assertNull(storage.session)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun concurrentUnauthorizedRequestsRotateOnlyOnceAndRetryWithTheReplacement() = runBlocking {
        server.enqueue(login("access-original", "refresh-original"))
        signIn()
        val initialRequests = CountDownLatch(2)
        val refreshes = AtomicInteger()
        val retriedRequests = AtomicInteger()
        server.dispatcher = routing { request ->
            when (request.path to request.getHeader("Authorization")) {
                "/api/v1/users/me" to "Bearer access-original" -> {
                    initialRequests.countDown()
                    check(initialRequests.await(5, TimeUnit.SECONDS))
                    error(401, "UNAUTHORIZED")
                }
                "/api/v1/auth/refresh" to null -> {
                    refreshes.incrementAndGet()
                    refresh("access-replacement", "refresh-replacement")
                }
                "/api/v1/users/me" to "Bearer access-replacement" -> {
                    retriedRequests.incrementAndGet()
                    profile()
                }
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }

        awaitAll(async { repository.restore() }, async { repository.restore() })

        assertEquals(1, refreshes.get())
        assertEquals(2, retriedRequests.get())
        assertEquals(AuthState.SignedIn(user), repository.state.value)
        assertEquals("refresh-replacement", storage.session?.refreshToken)
    }

    @Test
    fun secondUnauthorizedResponseStopsRetryingAndKeepsLocalSession() = runBlocking {
        server.enqueue(login("access-original", "refresh-original"))
        signIn()
        server.enqueue(error(401, "UNAUTHORIZED"))
        server.enqueue(refresh("access-replacement", "refresh-replacement"))
        server.enqueue(error(401, "UNAUTHORIZED"))

        repository.restore()

        assertEquals(4, server.requestCount)
        assertEquals(AuthState.SignedIn(user, needsRetry = true), repository.state.value)
        assertEquals("refresh-replacement", storage.session?.refreshToken)
    }

    @Test
    fun lateRotationCannotOverwriteASecondLoginForTheSameAccount() = runBlocking {
        server.enqueue(login("access-original", "refresh-original"))
        signIn()
        val rotationStarted = CompletableDeferred<Unit>()
        val finishRotation = CountDownLatch(1)
        val revocations = mutableSetOf<String>()
        val bothRevocations = CompletableDeferred<Unit>()
        server.dispatcher = routing { request ->
            when (request.path) {
                "/api/v1/users/me" -> error(401, "UNAUTHORIZED")
                "/api/v1/auth/refresh" -> {
                    rotationStarted.complete(Unit)
                    check(finishRotation.await(5, TimeUnit.SECONDS))
                    refresh("access-stale", "refresh-stale")
                }
                "/api/v1/auth/logout" -> {
                    synchronized(revocations) {
                        revocations.add(request.body.readUtf8())
                        if (revocations.size == 2) bothRevocations.complete(Unit)
                    }
                    MockResponse().setResponseCode(204)
                }
                "/api/v1/auth/login" -> login("access-current", "refresh-current", "Updated Demo")
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }
        val restoring = async { repository.restore() }
        withTimeout(5_000) { rotationStarted.await() }

        repository.signOut()
        assertEquals(AuthState.SignedOut(), repository.state.value)
        assertNull(storage.session)
        signIn()
        finishRotation.countDown()
        restoring.await()
        withTimeout(5_000) { bothRevocations.await() }

        assertEquals(
            setOf("""{"refreshToken":"refresh-original"}""", """{"refreshToken":"refresh-stale"}"""),
            synchronized(revocations) { revocations.toSet() },
        )
        assertEquals("refresh-current", storage.session?.refreshToken)
        assertEquals(AuthState.SignedIn(user.copy(displayName = "Updated Demo")), repository.state.value)
    }

    @Test
    fun lateProfileResponseCannotOverwriteASecondLoginForTheSameAccount() = runBlocking {
        server.enqueue(login("access-original", "refresh-original"))
        signIn()
        val profileStarted = CompletableDeferred<Unit>()
        val finishProfile = CountDownLatch(1)
        server.dispatcher = routing { request ->
            when (request.path) {
                "/api/v1/users/me" -> {
                    profileStarted.complete(Unit)
                    check(finishProfile.await(5, TimeUnit.SECONDS))
                    profile()
                }
                "/api/v1/auth/logout" -> MockResponse().setResponseCode(204)
                "/api/v1/auth/login" -> login("access-current", "refresh-current", "Updated Demo")
                else -> error(500, "UNEXPECTED_REQUEST")
            }
        }
        val restoring = async { repository.restore() }
        withTimeout(5_000) { profileStarted.await() }

        repository.signOut()
        signIn()
        finishProfile.countDown()
        restoring.await()

        assertEquals("Updated Demo", storage.session?.user?.displayName)
        assertEquals(AuthState.SignedIn(user.copy(displayName = "Updated Demo")), repository.state.value)
    }

    private suspend fun signIn() {
        assertEquals(AuthResult.Success, repository.signIn("demo@example.test", "test-password"))
    }

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))

    private fun login(accessToken: String, refreshToken: String, displayName: String = "Demo") = json(
        200,
        """{"data":{"user":{"id":"user-1","email":"demo@example.test","displayName":"$displayName"},"accessToken":"$accessToken","refreshToken":"$refreshToken","expiresIn":900}}""",
    )

    private fun refresh(accessToken: String, refreshToken: String) = json(
        200,
        """{"data":{"accessToken":"$accessToken","refreshToken":"$refreshToken","expiresIn":900}}""",
    )

    private fun profile() = json(
        200,
        """{"data":{"id":"user-1","email":"demo@example.test","displayName":"Demo"}}""",
    )

    private fun error(status: Int, code: String) =
        json(status, """{"error":{"code":"$code","message":"Test error"}}""")

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun routing(handler: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
    }
}

private class InMemorySessionStorage : SessionStorage {
    var session: StoredSession? = null

    override fun read() = session

    override fun write(session: StoredSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}
