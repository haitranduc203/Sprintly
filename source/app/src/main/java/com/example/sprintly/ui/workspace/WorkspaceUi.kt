package com.example.sprintly.ui.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.sprintly.R
import com.example.sprintly.auth.AuthState
import com.example.sprintly.databinding.ViewWorkspaceStatusBinding
import com.example.sprintly.ui.SessionViewModel
import com.example.sprintly.workspace.WorkspaceFailure
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun initials(label: String): String = label.trim()
    .split(Regex("\\s+"), limit = 3)
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercase() }
    .joinToString("")

internal val WorkspaceFailure.accessLost: Boolean
    get() = status == 403 || status == 404

internal fun WorkspaceFailure.displayMessage(): Int = when (code) {
    "NETWORK_ERROR" -> R.string.workspace_connection_issue
    "AUTH_RETRY", "SESSION_CHANGED", "UNAUTHORIZED" -> R.string.auth_refresh_unavailable_description
    "RATE_LIMITED" -> R.string.auth_rate_limited
    else -> when (status) {
        403, 404 -> R.string.workspace_access_lost
        409 -> R.string.workspace_conflict
        400, 422 -> R.string.workspace_input_invalid
        else -> R.string.auth_server_error
    }
}

internal fun Fragment.currentAccount(): AuthState.SignedIn? =
    sessionViewModel().state.value as? AuthState.SignedIn

internal fun Fragment.sessionViewModel(): SessionViewModel =
    ViewModelProvider(requireActivity())[SessionViewModel::class.java]

internal fun Fragment.refreshWithSession(view: View, refresh: () -> Unit) {
    val session = sessionViewModel().state.map { it as? AuthState.SignedIn }.distinctUntilChanged()
    collectScreen(session, Lifecycle.State.RESUMED) { signedIn ->
        if (signedIn != null) {
            refresh()
            if (hasAccessLossNotice()) {
                Snackbar.make(view, R.string.workspace_access_lost, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}

internal fun Fragment.retryWorkspace(refresh: () -> Unit) {
    if (currentAccount()?.needsRetry == true) sessionViewModel().restore()
    else refresh()
}

internal fun <T> Fragment.collectScreen(
    flow: Flow<T>,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    render: (T) -> Unit,
) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(state) {
            flow.collect { render(it) }
        }
    }
}

internal fun ViewWorkspaceStatusBinding.showLoading() {
    root.isVisible = true
    statusProgress.isVisible = true
    statusTitle.isVisible = false
    statusDescription.isVisible = false
    statusRetry.isVisible = false
}

internal fun ViewWorkspaceStatusBinding.showEmpty(@StringRes title: Int, @StringRes help: Int? = null) {
    showStatus(title, help, retry = false)
}

internal fun ViewWorkspaceStatusBinding.showError(failure: WorkspaceFailure) {
    showStatus(R.string.workspace_not_loaded, failure.displayMessage(), retry = true)
}

private fun ViewWorkspaceStatusBinding.showStatus(
    @StringRes title: Int,
    @StringRes description: Int?,
    retry: Boolean,
) {
    root.isVisible = true
    statusProgress.isVisible = false
    statusTitle.setText(title)
    statusTitle.isVisible = true
    statusDescription.isVisible = description != null
    description?.let(statusDescription::setText)
    statusRetry.isVisible = retry
}

internal fun RecyclerView.bindRows(rows: RecyclerView.Adapter<*>) {
    layoutManager = LinearLayoutManager(context)
    adapter = rows
}

internal fun View.showActions(vararg actions: Pair<Int, () -> Unit>) {
    val popup = PopupMenu(context, this)
    actions.forEach { (title, _) -> popup.menu.add(0, title, 0, title) }
    popup.setOnMenuItemClickListener { item ->
        val action = actions.firstOrNull { it.first == item.itemId } ?: return@setOnMenuItemClickListener false
        action.second()
        true
    }
    popup.show()
}

internal fun Fragment.openWorkspace(workspaceId: String) {
    findNavController().navigate(
        R.id.workspace_detail_fragment,
        bundleOf("workspaceId" to workspaceId),
    )
}

internal fun Fragment.openBoard(workspaceId: String, projectId: String) {
    findNavController().navigate(
        R.id.workspace_board_fragment,
        bundleOf("workspaceId" to workspaceId, "projectId" to projectId),
    )
}

internal fun Fragment.goBackAfterAccessLoss(destination: Int) {
    val nav = findNavController()
    if (nav.popBackStack(destination, false)) {
        nav.currentBackStackEntry?.savedStateHandle?.set(ACCESS_LOST, true)
    }
}

internal fun Fragment.hasAccessLossNotice(): Boolean =
    findNavController().currentBackStackEntry?.savedStateHandle?.remove<Boolean>(ACCESS_LOST) == true

internal fun Context.tint(colorId: Int): ColorStateList = ColorStateList.valueOf(getColor(colorId))

internal fun Bundle.actionKind(): WorkspaceAction =
    WorkspaceAction.valueOf(checkNotNull(getString(WorkspaceAction.KEY)))

internal const val ACCESS_LOST = "workspaceAccessLost"
