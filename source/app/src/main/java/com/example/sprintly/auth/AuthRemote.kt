package com.example.sprintly.auth

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Tag

internal class AccessToken(val value: String)

internal class SessionTokens(val access: AccessToken?, val refresh: String)

internal class AuthenticatedUser(val user: AuthUser, val tokens: SessionTokens)

internal class AuthRemoteFailure(val code: String, val status: Int? = null) : Exception()

internal class AuthRemote(baseUrl: String) {
    private val gson = Gson()
    private val endpoints = baseUrl.takeIf(String::isNotBlank)?.let { Endpoints(it, gson) }

    suspend fun signIn(email: String, password: String): AuthenticatedUser =
        userAndTokens { auth.login(LoginRequest(email, password)) }

    suspend fun register(email: String, displayName: String, password: String): AuthenticatedUser =
        userAndTokens { auth.register(RegisterRequest(email, displayName, password)) }

    suspend fun refresh(refreshToken: String): SessionTokens {
        val payload = call { auth.refresh(RefreshRequest(refreshToken)) }?.data
            ?: throw AuthRemoteFailure("SERVER_ERROR")
        return tokens(payload.accessToken, payload.refreshToken)
    }

    suspend fun logout(refreshToken: String) {
        call { auth.logout(RefreshRequest(refreshToken)) }
    }

    suspend fun profile(access: AccessToken): AuthUser =
        call { users.me(access) }?.data?.toUser() ?: throw AuthRemoteFailure("SERVER_ERROR")

    private suspend fun userAndTokens(
        request: suspend Endpoints.() -> Response<Envelope<LoginPayload>>,
    ): AuthenticatedUser {
        val payload = call(request)?.data ?: throw AuthRemoteFailure("SERVER_ERROR")
        return AuthenticatedUser(
            payload.user?.toUser() ?: throw AuthRemoteFailure("SERVER_ERROR"),
            tokens(payload.accessToken, payload.refreshToken),
        )
    }

    private suspend fun <T> call(request: suspend Endpoints.() -> Response<T>): T? {
        val api = endpoints ?: throw AuthRemoteFailure("API_NOT_CONFIGURED")
        try {
            val response = api.request()
            if (response.isSuccessful) return response.body()

            val status = response.code()
            val code = response.errorBody()?.use {
                try {
                    gson.fromJson(it.charStream(), ErrorEnvelope::class.java)?.error?.code
                } catch (_: JsonParseException) {
                    null
                }
            }
            throw AuthRemoteFailure(
                code = when {
                    status >= 500 -> "SERVER_ERROR"
                    code != null -> code
                    status == 429 -> "RATE_LIMITED"
                    else -> "SERVER_ERROR"
                },
                status = status,
            )
        } catch (_: IOException) {
            throw AuthRemoteFailure("NETWORK_ERROR")
        } catch (_: JsonParseException) {
            throw AuthRemoteFailure("SERVER_ERROR")
        }
    }

    private fun tokens(access: String?, refresh: String?): SessionTokens {
        if (access.isNullOrBlank() || access.any { it !in '!'..'~' } || refresh.isNullOrBlank()) {
            throw AuthRemoteFailure("SERVER_ERROR")
        }
        return SessionTokens(AccessToken(access), refresh)
    }

    private class Endpoints(baseUrl: String, gson: Gson) {
        private val publicClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private val authenticatedClient = publicClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token = checkNotNull(request.tag(AccessToken::class.java))
                chain.proceed(
                    request.newBuilder().header("Authorization", "Bearer ${token.value}").build(),
                )
            }
            .build()

        val auth = retrofit(baseUrl, gson, publicClient).create(AuthApi::class.java)
        val users = retrofit(baseUrl, gson, authenticatedClient).create(UserApi::class.java)

        private fun retrofit(baseUrl: String, gson: Gson, client: OkHttpClient) = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}

private interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<Envelope<LoginPayload>>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<Envelope<LoginPayload>>

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<Envelope<RefreshPayload>>

    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequest): Response<Unit>
}

private interface UserApi {
    @GET("users/me")
    suspend fun me(@Tag token: AccessToken): Response<Envelope<UserPayload>>
}

private class Envelope<T>(@SerializedName("data") val data: T?)

private class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
)

private class RegisterRequest(
    @SerializedName("email") val email: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("password") val password: String,
)

private class RefreshRequest(@SerializedName("refreshToken") val refreshToken: String)

private class LoginPayload(
    @SerializedName("user") val user: UserPayload?,
    @SerializedName("accessToken") val accessToken: String?,
    @SerializedName("refreshToken") val refreshToken: String?,
)

private class RefreshPayload(
    @SerializedName("accessToken") val accessToken: String?,
    @SerializedName("refreshToken") val refreshToken: String?,
)

private class UserPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("displayName") val displayName: String?,
) {
    fun toUser(): AuthUser {
        if (id.isNullOrBlank() || email.isNullOrBlank() || displayName.isNullOrBlank()) {
            throw AuthRemoteFailure("SERVER_ERROR")
        }
        return AuthUser(id, email, displayName)
    }
}

private class ErrorEnvelope(@SerializedName("error") val error: ErrorPayload?)

private class ErrorPayload(@SerializedName("code") val code: String?)
