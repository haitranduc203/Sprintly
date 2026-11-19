package com.example.sprintly.ui.workspace

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import com.example.sprintly.R
import com.example.sprintly.databinding.FragmentWorkspaceListBinding
import com.example.sprintly.offline.OfflineConnectivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class WorkspaceListFragment : Fragment(R.layout.fragment_workspace_list) {
    private val model: WorkspaceListViewModel by viewModels()
    @Inject internal lateinit var connectivity: OfflineConnectivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkspaceListBinding.bind(view)
        val rows = WorkspaceCardsAdapter { workspace -> openWorkspace(workspace.id) }
        val footer = WorkspaceFooterAdapter {
            val state = model.state.value
            if (state is ScreenState.Ready && !state.cached && connectivity.isConnected()) {
                openAction(WorkspaceAction.CreateWorkspace)
            }
        }
        binding.workspaceItems.bindRows(ConcatAdapter(rows, footer))
        binding.workspaceProfile.setOnClickListener { findNavController().navigate(R.id.account_fragment) }
        val retry = { retryOfflineWorkspace(connectivity, model::refresh) }
        binding.workspaceStatus.statusRetry.setOnClickListener { retry() }

        listenForWorkspaceAction { action, result ->
            val id = result.getString(WorkspaceAction.RESULT_ID)
            if (action == WorkspaceAction.CreateWorkspace && id != null) openWorkspace(id)
            else model.refresh()
        }
        refreshWithOfflineSession(view, connectivity, model::refresh)
        collectScreen(model.state.combine(connectivity.connected, ::Pair)) { (state, online) ->
            binding.workspaceItems.isVisible = state is ScreenState.Ready
            val ready = state as? ScreenState.Ready
            val needsRetry = currentAccount()?.needsRetry != false
            val readOnly = !online || ready?.cached == true || needsRetry
            val showBanner = !online || needsRetry || ready?.refreshFailed == true
            binding.workspaceOfflineBanner.showCached(ready != null && showBanner, online, retry)
            when (state) {
                ScreenState.Loading -> binding.workspaceStatus.showLoading()
                is ScreenState.Failed -> {
                    rows.submitList(emptyList())
                    if (!online) binding.workspaceStatus.showNotCached()
                    else binding.workspaceStatus.showError(state.error)
                }
                is ScreenState.Ready -> {
                    rows.submitList(state.data)
                    footer.update(empty = state.data.isEmpty(), canCreate = !readOnly)
                    binding.workspaceStatus.root.isVisible = false
                }
            }
        }
    }
}
