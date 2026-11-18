package com.example.sprintly.workspace

import com.example.sprintly.auth.AccessToken
import com.example.sprintly.auth.AuthRemote
import com.example.sprintly.auth.AuthRemoteFailure
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.auth.Envelope
import retrofit2.Response

internal class WorkspaceRequests<Api : Any>(
    private val auth: AuthRepository,
    private val remote: AuthRemote,
    serviceType: Class<Api>,
) {
    private val service = remote.authenticatedApi(serviceType)

    suspend fun <Payload : Any, Model> data(
        operation: suspend Api.(AccessToken) -> Response<Envelope<Payload>>,
        toModel: (Payload) -> Model,
    ): Model = request(operation) { toModel(it?.data.required()) }

    suspend fun <Body, Model> request(
        operation: suspend Api.(AccessToken) -> Response<Body>,
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

internal fun <T : Any> T?.required(): T = this ?: throw AuthRemoteFailure("SERVER_ERROR")
