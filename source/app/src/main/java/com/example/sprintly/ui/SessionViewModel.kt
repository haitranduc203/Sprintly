package com.example.sprintly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {
    val state = repository.state
    private val restoring = MutableStateFlow(false)
    val isRestoring = restoring.asStateFlow()

    internal fun loginId(): String? = repository.cacheSessionId()

    init {
        restore()
    }

    fun restore() {
        if (restoring.value) return
        restoring.value = true
        viewModelScope.launch {
            try {
                repository.restore()
            } finally {
                restoring.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
    }
}
