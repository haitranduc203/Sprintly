package com.example.sprintly.ui.workspace

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.sprintly.R
import com.example.sprintly.databinding.FragmentWorkspaceBoardBinding
import com.example.sprintly.databinding.ItemWorkspaceColumnBinding
import com.example.sprintly.databinding.ItemWorkspaceColumnChipBinding
import com.example.sprintly.workspace.BoardColumn
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class WorkspaceBoardFragment : Fragment(R.layout.fragment_workspace_board) {
    private val model: WorkspaceBoardViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkspaceBoardBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.boardProjectName, true)
        binding.boardToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.boardRefresh.setOnClickListener { retryWorkspace(model::refresh) }
        binding.boardStatus.statusRetry.setOnClickListener { retryWorkspace(model::refresh) }
        binding.columnAdd.setOnClickListener {
            openAction(WorkspaceAction.AddColumn, scopeId = model.projectId)
        }

        var focusAddedColumn = false
        listenForWorkspaceAction { action, result ->
            if (action == WorkspaceAction.DeleteProject && result.actionSucceeded()) {
                findNavController().popBackStack()
            } else {
                if (action == WorkspaceAction.AddColumn && result.actionSucceeded()) {
                    model.selectedColumn = result.getString(WorkspaceAction.RESULT_ID)
                    focusAddedColumn = true
                }
                model.refresh()
            }
        }
        refreshWithSession(view, model::refresh)
        collectScreen(model.state) { state ->
            val content = (state as? ScreenState.Ready)?.data
            binding.boardContent.isVisible = content != null
            binding.boardMore.isVisible = content != null && currentAccount()?.needsRetry == false
            binding.boardStatus.root.isVisible = content == null
            when (state) {
                ScreenState.Loading -> binding.boardStatus.showLoading()
                is ScreenState.Failed -> {
                    binding.boardColumns.removeAllViews()
                    binding.columnChips.removeAllViews()
                    if (state.error.accessLost) goBackAfterAccessLoss(R.id.workspace_detail_fragment)
                    else binding.boardStatus.showError(state.error)
                }
                is ScreenState.Ready -> {
                    bindBoard(binding, state.data, focusAddedColumn)
                    focusAddedColumn = false
                }
            }
        }
    }

    private fun bindBoard(binding: FragmentWorkspaceBoardBinding, data: WorkspaceBoard, focusColumn: Boolean) {
        binding.boardToolbar.subtitle = data.workspace.name
        binding.boardProjectName.text = data.project.name
        val canWrite = currentAccount()?.needsRetry == false
        binding.columnAdd.isEnabled = canWrite
        binding.boardMore.setOnClickListener { showBoardActions(binding.boardMore, data) }

        val columns = data.board.columns.sortedBy { it.position }
        val selectedColumnId = model.selectedColumn
        binding.columnScroll.setOnScrollChangeListener(null)
        binding.columnChips.removeAllViews()
        binding.boardColumns.removeAllViews()
        val laneViews = columns.map { createLane(binding, it, canWrite) }
        val chipViews = columns.map { column ->
            ItemWorkspaceColumnChipBinding.inflate(layoutInflater, binding.columnChips, false).root.apply {
                id = View.generateViewId()
                text = column.name
                binding.columnChips.addView(this)
            }
        }

        binding.columnScroll.doOnLayout { scroll ->
            val maxWidth = (340 * scroll.resources.displayMetrics.density).roundToInt()
            val laneWidth = (scroll.width * .82f).roundToInt().coerceAtMost(maxWidth)
            laneViews.forEach { it.root.layoutParams = it.root.layoutParams.apply { width = laneWidth } }
            binding.boardColumns.doOnLayout {
                val selected = columns.indexOfFirst { column -> column.id == selectedColumnId }.coerceAtLeast(0)
                if (selected !in laneViews.indices) return@doOnLayout
                selectAndScrollToLane(binding, laneViews[selected].root, smooth = false)
                binding.columnChips.check(chipViews[selected].id)
                model.selectedColumn = columns[selected].id
                if (focusColumn) laneViews[selected].columnLabel.requestFocus()

                chipViews.forEachIndexed { index, chip ->
                    chip.setOnClickListener {
                        model.selectedColumn = columns[index].id
                        selectAndScrollToLane(binding, laneViews[index].root, smooth = true)
                    }
                }
                scroll.setOnScrollChangeListener { _, x, _, _, _ ->
                    val centered = laneViews.indices.minByOrNull { index ->
                        abs(laneViews[index].root.left - x)
                    } ?: return@setOnScrollChangeListener
                    model.selectedColumn = columns[centered].id
                    binding.columnChips.check(chipViews[centered].id)
                }
            }
        }
    }

    private fun selectAndScrollToLane(binding: FragmentWorkspaceBoardBinding, lane: View, smooth: Boolean) {
        if (smooth) binding.columnScroll.smoothScrollTo(lane.left, 0)
        else binding.columnScroll.scrollTo(lane.left, 0)
    }

    private fun createLane(
        binding: FragmentWorkspaceBoardBinding,
        column: BoardColumn,
        canWrite: Boolean,
    ): ItemWorkspaceColumnBinding {
        val lane = ItemWorkspaceColumnBinding.inflate(layoutInflater, binding.boardColumns, true)
        ViewCompat.setAccessibilityHeading(lane.columnLabel, true)
        lane.columnLabel.text = column.name
        lane.columnLabel.isFocusable = true
        val dotColor = when (column.position) {
            0 -> R.color.orbix_info_container
            1 -> R.color.orbix_warning_container
            2 -> R.color.orbix_success_container
            else -> R.color.orbix_divider
        }
        lane.columnDot.backgroundTintList = requireContext().tint(dotColor)
        lane.columnMore.isVisible = canWrite
        lane.columnMore.contentDescription = getString(R.string.workspace_column_actions, column.name)
        lane.columnMore.setOnClickListener {
            lane.columnMore.showActions(R.string.workspace_column_edit to {
                openAction(WorkspaceAction.RenameColumn, targetId = column.id, name = column.name)
            })
        }
        return lane
    }

    private fun showBoardActions(anchor: View, data: WorkspaceBoard) {
        val actions = mutableListOf(
            R.string.workspace_column_add to {
                openAction(WorkspaceAction.AddColumn, scopeId = data.project.id)
            },
            R.string.workspace_project_edit to {
                openAction(
                    WorkspaceAction.EditProject,
                    targetId = data.project.id,
                    name = data.project.name,
                    description = data.project.description,
                )
            },
        )
        if (data.workspace.isOwner) {
            actions += R.string.workspace_project_delete to {
                openAction(WorkspaceAction.DeleteProject, targetId = data.project.id, name = data.project.name)
            }
        }
        anchor.showActions(*actions.toTypedArray())
    }
}
