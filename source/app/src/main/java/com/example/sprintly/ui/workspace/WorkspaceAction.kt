package com.example.sprintly.ui.workspace

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.example.sprintly.R

internal enum class WorkspaceAction(
    @StringRes val title: Int,
    @StringRes val submit: Int,
    @StringRes val field: Int? = null,
    val hasDescription: Boolean = false,
    @StringRes val consequences: Int? = null,
) {
    CreateWorkspace(
        R.string.workspace_create,
        R.string.workspace_create,
        R.string.workspace_name,
        hasDescription = true,
    ),
    EditWorkspace(
        R.string.workspace_edit,
        R.string.workspace_save,
        R.string.workspace_name,
        hasDescription = true,
    ),
    DeleteWorkspace(
        R.string.workspace_delete_title,
        R.string.workspace_delete_confirm,
        consequences = R.string.workspace_delete_message,
    ),
    CreateProject(
        R.string.workspace_project_create,
        R.string.workspace_project_create,
        R.string.workspace_project_name,
        hasDescription = true,
    ),
    EditProject(
        R.string.workspace_project_edit,
        R.string.workspace_save,
        R.string.workspace_project_name,
        hasDescription = true,
    ),
    DeleteProject(
        R.string.workspace_delete_title,
        R.string.workspace_delete_confirm,
        consequences = R.string.workspace_project_delete_message,
    ),
    AddMember(
        R.string.workspace_member_add,
        R.string.workspace_member_submit,
        R.string.workspace_member_email,
    ),
    RemoveMember(
        R.string.workspace_member_remove_title,
        R.string.workspace_member_remove,
        consequences = R.string.workspace_member_remove_message,
    ),
    AddColumn(
        R.string.workspace_column_add,
        R.string.workspace_column_add,
        R.string.workspace_column_name,
    ),
    RenameColumn(
        R.string.workspace_column_edit,
        R.string.workspace_save,
        R.string.workspace_column_name,
    );

    companion object {
        const val KEY = "workspaceAction"
        const val SCOPE_ID = "scopeId"
        const val TARGET_ID = "targetId"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val ACCOUNT_ID = "accountId"
        const val RESULT_ID = "resultId"
        const val SUCCEEDED = "succeeded"
    }
}

internal fun Fragment.openAction(
    action: WorkspaceAction,
    scopeId: String? = null,
    targetId: String? = null,
    name: String? = null,
    description: String? = null,
) {
    val account = currentAccount()?.takeUnless { it.needsRetry } ?: return
    if (childFragmentManager.findFragmentByTag(WorkspaceAction.KEY) != null) return
    WorkspaceActionSheet().apply {
        arguments = bundleOf(
            WorkspaceAction.KEY to action.name,
            WorkspaceAction.SCOPE_ID to scopeId,
            WorkspaceAction.TARGET_ID to targetId,
            WorkspaceAction.NAME to name,
            WorkspaceAction.DESCRIPTION to description,
            WorkspaceAction.ACCOUNT_ID to account.user.id,
        )
    }.showNow(childFragmentManager, WorkspaceAction.KEY)
}

internal fun Fragment.listenForWorkspaceAction(receive: (WorkspaceAction, Bundle) -> Unit) {
    childFragmentManager.setFragmentResultListener(WorkspaceAction.KEY, viewLifecycleOwner) { _, result ->
        receive(result.actionKind(), result)
    }
}

internal fun Bundle.actionSucceeded(): Boolean = getBoolean(WorkspaceAction.SUCCEEDED)
