package com.example.sprintly.auth

data class AuthUser(val id: String, val email: String, val displayName: String)

sealed interface AuthState {
    data object Checking : AuthState
    data class SignedOut(val expired: Boolean = false) : AuthState
    data class SignedIn(val user: AuthUser, val needsRetry: Boolean = false) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val code: String) : AuthResult
}
