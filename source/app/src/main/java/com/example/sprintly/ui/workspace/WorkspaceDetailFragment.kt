package com.example.sprintly.ui.workspace

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.sprintly.R
import com.example.sprintly.databinding.FragmentWorkspaceDetailBinding
import com.example.sprintly.offline.OfflineConnectivity
import com.example.sprintly.workspace.Workspace
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class WorkspaceDetailFragment : Fragment(R.layout.fragment_workspace_detail) {
    private val model: WorkspaceDetailViewModel by viewModels()
    @Inject internal lateinit var connectivity: OfflineConnectivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkspaceDetailBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.projectHeading, true)
        ViewCompat.setAccessibilityHeading(binding.memberCount, true)
        val projectRows = WorkspaceRowsAdapter(open = { openBoard(model.workspaceId, it.id) })
        val memberRows = WorkspaceRowsAdapter(remove = { member ->
            if (canWrite()) openAction(
                WorkspaceAction.RemoveMember,
                scopeId = model.workspaceId,
                targetId = member.id,
                name = member.title,
            )
        })
        binding.projectRows.bindRows(projectRows)
        binding.memberRows.bindRows(memberRows)
        binding.workspaceToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.workspaceTabs.getTabAt(model.tab.ordinal)?.select()
        binding.workspaceTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                model.selectTab(WorkspaceTab.entries[tab.position])
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.projectCreate.setOnClickListener {
            if (canWrite()) openAction(WorkspaceAction.CreateProject, scopeId = model.workspaceId)
        }
        binding.memberAdd.setOnClickListener {
            if (canWrite()) openAction(WorkspaceAction.AddMember, scopeId = model.workspaceId)
        }
        val retry = { retryOfflineWorkspace(connectivity, model::refresh) }
        binding.detailStatus.statusRetry.setOnClickListener { retry() }

        listenForWorkspaceAction { action, result ->
            val id = result.getString(WorkspaceAction.RESULT_ID)
            when {
                action == WorkspaceAction.CreateProject && id != null -> openBoard(model.workspaceId, id)
                action == WorkspaceAction.DeleteWorkspace && result.actionSucceeded() ->
                    findNavController().popBackStack(R.id.workspace_list_fragment, false)
                else -> model.refresh()
            }
        }
        refreshWithOfflineSession(view, connectivity, model::refresh)
        collectScreen(model.state.combine(connectivity.connected, ::Pair)) { (state, online) ->
            render(binding, projectRows, memberRows, state, online, retry)
        }
    }

    private fun render(
        binding: FragmentWorkspaceDetailBinding,
        projects: WorkspaceRowsAdapter,
        members: WorkspaceRowsAdapter,
        state: ScreenState<WorkspaceDetail>,
        online: Boolean,
        retry: () -> Unit,
    ) {
        val ready = state as? ScreenState.Ready
        val detail = ready?.data
        binding.workspaceTabBar.isVisible = detail != null
        binding.workspaceToolbar.title = detail?.workspace?.name.orEmpty()
        val writable = online && canWrite()
        val showBanner = !online || currentAccount()?.needsRetry != false || ready?.refreshFailed == true
        binding.workspaceOfflineBanner.showCached(detail != null && showBanner, online, retry)
        val isOwner = detail?.workspace?.isOwner == true
        binding.workspaceMore.isVisible = writable && isOwner
        binding.workspaceMore.setOnClickListener {
            if (canWrite()) detail?.workspace?.let { showWorkspaceActions(binding.workspaceMore, it) }
        }
        binding.projectsPanel.isVisible = detail?.projects != null
        binding.membersPanel.isVisible = detail?.members != null
        binding.projectCreate.isEnabled = writable
        binding.memberAdd.isVisible = writable && isOwner

        when (state) {
            ScreenState.Loading -> binding.detailStatus.showLoading()
            is ScreenState.Failed -> {
                projects.submitList(emptyList())
                members.submitList(emptyList())
                if (state.error.accessLost) goBackAfterAccessLoss(R.id.workspace_list_fragment)
                else if (!online) binding.detailStatus.showNotCached()
                else binding.detailStatus.showError(state.error)
            }
            is ScreenState.Ready -> {
                val data = state.data
                binding.detailStatus.root.isVisible = data.sectionError != null
                data.sectionError?.let(binding.detailStatus::showError)
                if (state.cached && data.projects == null && data.members == null) {
                    if (!online || state.refreshFailed) binding.detailStatus.showNotCached()
                    else binding.detailStatus.showLoading()
                }
                data.projects?.let { items ->
                    projects.submitList(items.map(::WorkspaceRow))
                    binding.projectsStatus.root.isVisible = items.isEmpty()
                    if (items.isEmpty()) binding.projectsStatus.showEmpty(R.string.workspace_project_empty)
                }
                data.members?.let { items ->
                    members.submitList(items.map { WorkspaceRow(it, isOwner && writable) })
                    binding.memberCount.text = resources.getQuantityString(
                        R.plurals.workspace_member_count,
                        items.size,
                        items.size,
                    )
                    binding.otherMembersEmpty.isVisible = items.none { !it.isOwner }
                }
            }
        }
    }

    private fun canWrite(): Boolean {
        val ready = model.state.value as? ScreenState.Ready ?: return false
        return !ready.cached && connectivity.isConnected() && currentAccount()?.needsRetry == false
    }

    private fun showWorkspaceActions(anchor: View, workspace: Workspace) {
        anchor.showActions(
            R.string.workspace_edit to {
                openAction(
                    WorkspaceAction.EditWorkspace,
                    targetId = workspace.id,
                    name = workspace.name,
                    description = workspace.description,
                )
            },
            R.string.workspace_delete to {
                openAction(WorkspaceAction.DeleteWorkspace, targetId = workspace.id, name = workspace.name)
            },
        )
    }
}
