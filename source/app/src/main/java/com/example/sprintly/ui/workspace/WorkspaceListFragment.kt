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
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WorkspaceListFragment : Fragment(R.layout.fragment_workspace_list) {
    private val model: WorkspaceListViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkspaceListBinding.bind(view)
        val rows = WorkspaceCardsAdapter { workspace -> openWorkspace(workspace.id) }
        val footer = WorkspaceFooterAdapter { openAction(WorkspaceAction.CreateWorkspace) }
        binding.workspaceItems.bindRows(ConcatAdapter(rows, footer))
        binding.workspaceProfile.setOnClickListener { findNavController().navigate(R.id.account_fragment) }
        binding.workspaceStatus.statusRetry.setOnClickListener { retryWorkspace(model::refresh) }

        listenForWorkspaceAction { action, result ->
            val id = result.getString(WorkspaceAction.RESULT_ID)
            if (action == WorkspaceAction.CreateWorkspace && id != null) openWorkspace(id)
            else model.refresh()
        }
        refreshWithSession(view, model::refresh)
        collectScreen(model.state) { state ->
            binding.workspaceItems.isVisible = state is ScreenState.Ready
            when (state) {
                ScreenState.Loading -> binding.workspaceStatus.showLoading()
                is ScreenState.Failed -> {
                    rows.submitList(emptyList())
                    binding.workspaceStatus.showError(state.error)
                }
                is ScreenState.Ready -> {
                    rows.submitList(state.data)
                    footer.update(empty = state.data.isEmpty(), canCreate = currentAccount()?.needsRetry == false)
                    binding.workspaceStatus.root.isVisible = false
                }
            }
        }
    }
}
