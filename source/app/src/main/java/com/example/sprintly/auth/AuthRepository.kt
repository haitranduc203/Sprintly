package com.example.sprintly.auth

import android.database.sqlite.SQLiteException
import com.example.sprintly.offline.OfflineCache
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class AuthRepository internal constructor(
    private val remote: AuthRemote,
    private val storage: SessionStorage,
    private val scope: CoroutineScope,
    private val clearCache: suspend (String) -> Unit = {},
) {
    @Inject internal constructor(
        remote: AuthRemote,
        storage: SessionStorage,
        @AuthScope scope: CoroutineScope,
        cache: OfflineCache,
    ) : this(remote, storage, scope, cache::clearSession)

    private val lock = Any()
    private val cacheLock = Mutex()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Checking)
    private var session: ActiveSession? = null
    private var authAttempt = 0L

    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    internal fun cacheSessionId(): String? = synchronized(lock) {
        session?.cacheId?.takeIf { mutableState.value is AuthState.SignedIn }
    }

    internal suspend fun <T> withSessionCache(id: String, action: suspend () -> T): T? = cacheLock.withLock {
        if (cacheSessionId() != id) return@withLock null
        val value = action()
        value.takeIf { cacheSessionId() == id }
    }

    suspend fun restore() {
        scope.async {
            val (active, tokens) = snapshot() ?: return@async
            validateProfile(active, tokens)
        }.await()
    }

    suspend fun signIn(email: String, password: String): AuthResult =
        authenticate { remote.signIn(email, password) }

    suspend fun register(email: String, displayName: String, password: String): AuthResult =
        authenticate { remote.register(email, displayName, password) }

    internal suspend fun <T> withAccess(request: suspend (AccessToken) -> T): T {
        val (active, initial) = synchronized(lock) {
            val current = session ?: throw AuthRemoteFailure(
                if (mutableState.value == AuthState.Checking) "AUTH_RETRY" else "UNAUTHORIZED",
            )
            current to readyCredentials(current)
        }
        val (initialTokens, initialAccess) = initial

        val result = try {
            request(initialAccess)
        } catch (failure: AuthRemoteFailure) {
            synchronized(lock) { requireCurrentSession(active) }
            if (failure.status != 401) throw failure

            val refreshed = refresh(active, initialTokens)
            val (retryTokens, retryAccess) = synchronized(lock) {
                requireCurrentSession(active)
                if (refreshed?.access == null) throw AuthRemoteFailure("AUTH_RETRY")
                readyCredentials(active)
            }
            retryRequest(active, retryTokens, retryAccess, request)
        }

        synchronized(lock) { requireCurrentSession(active) }
        return result
    }

    suspend fun signOut() {
        scope.async {
            val refreshToken = synchronized(lock) {
                authAttempt++
                closeSession(expired = false)
            }
            refreshToken?.let(::revoke)
        }.await()
    }

    private suspend fun authenticate(request: suspend () -> AuthenticatedUser): AuthResult {
        val attempt = synchronized(lock) { ++authAttempt }
        return scope.async {
            val signedIn = try {
                request()
            } catch (failure: AuthRemoteFailure) {
                return@async AuthResult.Failure(failure.code)
            }

            val (result, tokenToRevoke) = synchronized(lock) {
                val next = ActiveSession(signedIn.user, signedIn.tokens)
                when {
                    authAttempt != attempt ->
                        AuthResult.Failure("SESSION_CHANGED") to next.tokens.refresh
                    !persist(next) ->
                        AuthResult.Failure("STORAGE_ERROR") to next.tokens.refresh
                    else -> {
                        val previous = session
                        session = next
                        mutableState.value = AuthState.SignedIn(next.user)
                        previous?.let { discardCache(it.cacheId) }
                        AuthResult.Success to previous?.tokens?.refresh
                    }
                }
            }
            tokenToRevoke?.let(::revoke)
            result
        }.await()
    }

    private fun snapshot(): Pair<ActiveSession, SessionTokens>? = synchronized(lock) {
        if (session == null && mutableState.value == AuthState.Checking) {
            val persisted = try {
                storage.read()
            } catch (_: SessionStorageException) {
                closeSession(expired = true)
                return null
            }
            if (persisted == null) {
                mutableState.value = AuthState.SignedOut()
            } else {
                session = ActiveSession(persisted.user, SessionTokens(null, persisted.refreshToken), persisted.cacheId)
            }
        }
        session?.let { it to it.tokens }
    }

    private suspend fun validateProfile(active: ActiveSession, initialTokens: SessionTokens) {
        var tokens = initialTokens
        try {
            if (tokens.access == null) {
                tokens = refresh(active, tokens) ?: return
            }
            val user = try {
                remote.profile(currentAccess(active, tokens) ?: return)
            } catch (failure: AuthRemoteFailure) {
                if (failure.status != 401 || initialTokens.access == null) throw failure
                tokens = refresh(active, tokens) ?: return
                remote.profile(currentAccess(active, tokens) ?: return)
            }
            synchronized(lock) {
                if (session !== active || active.tokens !== tokens) return
                if (user.id != active.user.id) {
                    closeSession(expired = true)
                    return
                }
                if (user != active.user) {
                    active.user = user
                    active.persisted = false
                }
                val needsRetry = !active.persisted && !persist(active)
                mutableState.value = AuthState.SignedIn(active.user, needsRetry)
            }
        } catch (_: AuthRemoteFailure) {
            synchronized(lock) {
                if (session === active && active.tokens === tokens) {
                    mutableState.value = AuthState.SignedIn(active.user, needsRetry = true)
                }
            }
        }
    }

    private fun currentAccess(active: ActiveSession, tokens: SessionTokens): AccessToken? =
        synchronized(lock) {
            tokens.access.takeIf { session === active && active.tokens === tokens }
        }

    // Called with the lock held so the session identity and credentials are checked together.
    private fun requireCurrentSession(active: ActiveSession) {
        if (session !== active) throw AuthRemoteFailure("SESSION_CHANGED")
    }

    private fun readyCredentials(active: ActiveSession): Pair<SessionTokens, AccessToken> {
        val state = mutableState.value
        val tokens = active.tokens
        val access = tokens.access
        if (state !is AuthState.SignedIn || state.needsRetry || access == null) {
            throw AuthRemoteFailure("AUTH_RETRY")
        }
        return tokens to access
    }

    private suspend fun <T> retryRequest(
        active: ActiveSession,
        tokens: SessionTokens,
        access: AccessToken,
        request: suspend (AccessToken) -> T,
    ): T {
        try {
            return request(access)
        } catch (failure: AuthRemoteFailure) {
            synchronized(lock) {
                requireCurrentSession(active)
                if (failure.status != 401) throw failure
                if (active.tokens === tokens) {
                    mutableState.value = AuthState.SignedIn(active.user, needsRetry = true)
                }
            }
            throw AuthRemoteFailure("AUTH_RETRY")
        }
    }

    // Retrofit retries a protected call at the repository layer, where the login identity
    // and the rejected credentials can be compared before and after refresh.
    private suspend fun refresh(active: ActiveSession, rejected: SessionTokens): SessionTokens? {
        val task = synchronized(lock) {
            if (session !== active) return null
            if (active.tokens !== rejected) return active.tokens
            active.refresh?.takeIf { it.rejected === rejected }?.task ?: scope.async(
                start = CoroutineStart.LAZY,
            ) {
                rotate(active, rejected)
            }.also { active.refresh = Refresh(rejected, it) }
        }
        return task.await()
    }

    private suspend fun rotate(active: ActiveSession, rejected: SessionTokens): SessionTokens? {
        try {
            val replacement = remote.refresh(rejected.refresh)
            synchronized(lock) {
                if (session === active && active.tokens === rejected) {
                    active.tokens = replacement
                    active.persisted = false
                    if (!persist(active)) {
                        mutableState.value = AuthState.SignedIn(active.user, needsRetry = true)
                        return null
                    }
                    return replacement
                }
            }
            revoke(replacement.refresh)
            return null
        } catch (failure: AuthRemoteFailure) {
            synchronized(lock) {
                if (session === active && active.tokens === rejected) {
                    if (failure.status == 401 && failure.code == "REFRESH_TOKEN_INVALID") {
                        closeSession(expired = true)
                    } else {
                        // Older requests that already got a 401 must not rotate this token again.
                        // An explicit retry takes a fresh snapshot and can try again.
                        active.tokens = SessionTokens(null, rejected.refresh)
                        mutableState.value = AuthState.SignedIn(active.user, needsRetry = true)
                    }
                }
            }
            return null
        } finally {
            synchronized(lock) {
                if (active.refresh?.rejected === rejected) active.refresh = null
            }
        }
    }

    private fun persist(active: ActiveSession): Boolean = try {
        storage.write(StoredSession(active.user, active.tokens.refresh, active.cacheId))
        active.persisted = true
        true
    } catch (_: SessionStorageException) {
        false
    }

    private fun closeSession(expired: Boolean): String? {
        val previous = session
        session = null
        try {
            storage.clear()
        } catch (_: SessionStorageException) {
            // Keep access closed even if the platform cannot remove its local record.
        }
        mutableState.value = AuthState.SignedOut(expired)
        previous?.let { discardCache(it.cacheId) }
        return previous?.tokens?.refresh
    }

    private fun discardCache(id: String) {
        scope.launch {
            cacheLock.withLock {
                try {
                    clearCache(id)
                } catch (_: SQLiteException) {
                    // An ended login can no longer read or write its cache namespace.
                }
            }
        }
    }

    private fun revoke(refreshToken: String) {
        scope.launch {
            try {
                remote.logout(refreshToken)
            } catch (_: AuthRemoteFailure) {
                // Logout has already closed the local session.
            }
        }
    }

    private class ActiveSession(var user: AuthUser, var tokens: SessionTokens, val cacheId: String = UUID.randomUUID().toString()) {
        var persisted = false
        var refresh: Refresh? = null
    }

    private class Refresh(val rejected: SessionTokens, val task: Deferred<SessionTokens?>)
}
