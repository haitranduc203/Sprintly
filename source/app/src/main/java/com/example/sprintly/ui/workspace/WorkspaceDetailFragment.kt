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
import com.example.sprintly.workspace.Workspace
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WorkspaceDetailFragment : Fragment(R.layout.fragment_workspace_detail) {
    private val model: WorkspaceDetailViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkspaceDetailBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.projectHeading, true)
        ViewCompat.setAccessibilityHeading(binding.memberCount, true)
        val projectRows = WorkspaceRowsAdapter(open = { openBoard(model.workspaceId, it.id) })
        val memberRows = WorkspaceRowsAdapter(remove = { member ->
            openAction(
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
            openAction(WorkspaceAction.CreateProject, scopeId = model.workspaceId)
        }
        binding.memberAdd.setOnClickListener {
            openAction(WorkspaceAction.AddMember, scopeId = model.workspaceId)
        }
        binding.detailStatus.statusRetry.setOnClickListener { retryWorkspace(model::refresh) }

        listenForWorkspaceAction { action, result ->
            val id = result.getString(WorkspaceAction.RESULT_ID)
            when {
                action == WorkspaceAction.CreateProject && id != null -> openBoard(model.workspaceId, id)
                action == WorkspaceAction.DeleteWorkspace && result.actionSucceeded() ->
                    findNavController().popBackStack(R.id.workspace_list_fragment, false)
                else -> model.refresh()
            }
        }
        refreshWithSession(view, model::refresh)
        collectScreen(model.state) { state -> render(binding, projectRows, memberRows, state) }
    }

    private fun render(
        binding: FragmentWorkspaceDetailBinding,
        projects: WorkspaceRowsAdapter,
        members: WorkspaceRowsAdapter,
        state: ScreenState<WorkspaceDetail>,
    ) {
        val detail = (state as? ScreenState.Ready)?.data
        binding.workspaceTabBar.isVisible = detail != null
        binding.workspaceToolbar.title = detail?.workspace?.name.orEmpty()
        val canWrite = currentAccount()?.needsRetry == false
        val isOwner = detail?.workspace?.isOwner == true
        binding.workspaceMore.isVisible = canWrite && isOwner
        binding.workspaceMore.setOnClickListener {
            detail?.workspace?.let { showWorkspaceActions(binding.workspaceMore, it) }
        }
        binding.projectsPanel.isVisible = detail?.projects != null
        binding.membersPanel.isVisible = detail?.members != null
        binding.projectCreate.isEnabled = canWrite
        binding.memberAdd.isVisible = canWrite && isOwner

        when (state) {
            ScreenState.Loading -> binding.detailStatus.showLoading()
            is ScreenState.Failed -> {
                projects.submitList(emptyList())
                members.submitList(emptyList())
                if (state.error.accessLost) goBackAfterAccessLoss(R.id.workspace_list_fragment)
                else binding.detailStatus.showError(state.error)
            }
            is ScreenState.Ready -> {
                val data = state.data
                binding.detailStatus.root.isVisible = data.sectionError != null
                data.sectionError?.let(binding.detailStatus::showError)
                data.projects?.let { items ->
                    projects.submitList(items.map(::WorkspaceRow))
                    binding.projectsStatus.root.isVisible = items.isEmpty()
                    if (items.isEmpty()) binding.projectsStatus.showEmpty(R.string.workspace_project_empty)
                }
                data.members?.let { items ->
                    members.submitList(items.map { WorkspaceRow(it, isOwner && canWrite) })
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
