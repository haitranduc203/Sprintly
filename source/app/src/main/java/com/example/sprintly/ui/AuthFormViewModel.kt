package com.example.sprintly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.auth.AuthRepository
import com.example.sprintly.auth.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthFormViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {
    data class Submission(val isRunning: Boolean = false, val errorCode: String? = null)

    private val submission = MutableStateFlow(Submission())
    val state = submission.asStateFlow()

    fun submit(email: String, password: String, displayName: String?) {
        if (submission.value.isRunning) return
        submission.value = Submission(isRunning = true)
        viewModelScope.launch {
            val result = if (displayName == null) {
                repository.signIn(email, password)
            } else {
                repository.register(email, displayName, password)
            }
            submission.value = when (result) {
                AuthResult.Success -> Submission()
                is AuthResult.Failure -> Submission(errorCode = result.code)
            }
        }
    }

    fun clearError() {
        if (submission.value.errorCode != null) submission.value = Submission()
    }
}
