package com.example.sprintly.ui.workspace

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sprintly.R
import com.example.sprintly.workspace.DirectoryUser
import com.example.sprintly.workspace.WorkspaceFailure
import com.example.sprintly.workspace.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ActionOutcome(val succeeded: Boolean, val id: String? = null)

internal data class WorkspaceActionState(
    val busy: Boolean = false,
    val candidate: DirectoryUser? = null,
    val candidateSelected: Boolean = false,
    @StringRes val error: Int? = null,
    val outcome: ActionOutcome? = null,
)

@HiltViewModel
internal class WorkspaceActionViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val action = WorkspaceAction.valueOf(checkNotNull(savedState[WorkspaceAction.KEY]))
    private val scopeId: String? = savedState[WorkspaceAction.SCOPE_ID]
    private val targetId: String? = savedState[WorkspaceAction.TARGET_ID]
    private val mutableState = MutableStateFlow(WorkspaceActionState())
    val state = mutableState.asStateFlow()

    fun emailChanged(email: String) {
        val state = mutableState.value
        if (state.busy || state.candidate?.email.equals(email.trim(), ignoreCase = true)) return
        mutableState.update { it.copy(candidate = null, candidateSelected = false, error = null) }
    }

    fun selectCandidate(selected: Boolean) {
        mutableState.update { it.copy(candidateSelected = selected && it.candidate != null) }
    }

    fun lookup(email: String) {
        if (mutableState.value.busy) return
        mutableState.value = WorkspaceActionState(busy = true)
        viewModelScope.launch {
            try {
                val match = repository.findUser(checkNotNull(scopeId), email)
                mutableState.value = WorkspaceActionState(
                    candidate = match,
                    error = if (match == null) R.string.workspace_member_not_found else null,
                )
            } catch (failure: WorkspaceFailure) {
                mutableState.update { it.errorState(failure) }
            }
        }
    }

    fun submit(name: String, description: String?) {
        val state = mutableState.value
        if (state.busy || state.outcome != null) return
        if (action == WorkspaceAction.AddMember && !state.candidateSelected) return
        mutableState.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val id = when (action) {
                    WorkspaceAction.CreateWorkspace -> repository.createWorkspace(name, description).id
                    WorkspaceAction.EditWorkspace -> repository
                        .updateWorkspace(checkNotNull(targetId), name, description).id
                    WorkspaceAction.DeleteWorkspace -> {
                        repository.deleteWorkspace(checkNotNull(targetId))
                        null
                    }
                    WorkspaceAction.CreateProject -> repository
                        .createProject(checkNotNull(scopeId), name, description).id
                    WorkspaceAction.EditProject -> repository
                        .updateProject(checkNotNull(targetId), name, description).id
                    WorkspaceAction.DeleteProject -> {
                        repository.deleteProject(checkNotNull(targetId))
                        null
                    }
                    WorkspaceAction.AddMember -> repository
                        .addMember(checkNotNull(scopeId), checkNotNull(state.candidate).id).id
                    WorkspaceAction.RemoveMember -> {
                        repository.removeMember(checkNotNull(scopeId), checkNotNull(targetId))
                        null
                    }
                    WorkspaceAction.AddColumn -> repository.addColumn(checkNotNull(scopeId), name).id
                    WorkspaceAction.RenameColumn -> repository.renameColumn(checkNotNull(targetId), name).id
                }
                mutableState.update { it.copy(busy = false, outcome = ActionOutcome(succeeded = true, id = id)) }
            } catch (failure: WorkspaceFailure) {
                mutableState.update { it.errorState(failure) }
            }
        }
    }

    private fun WorkspaceActionState.errorState(failure: WorkspaceFailure): WorkspaceActionState = when {
        failure.code == "MEMBER_ALREADY_EXISTS" -> copy(busy = false, error = R.string.workspace_member_exists)
        failure.accessLost -> copy(busy = false, outcome = ActionOutcome(succeeded = false))
        else -> copy(busy = false, error = failure.displayMessage())
    }
}
