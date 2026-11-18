package com.example.sprintly.ui.workspace

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.sprintly.R
import com.example.sprintly.databinding.FragmentWorkspaceBoardBinding
import com.example.sprintly.databinding.ItemWorkspaceColumnChipBinding
import com.example.sprintly.tasks.Task
import com.example.sprintly.workspace.BoardColumn
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class WorkspaceBoardFragment : Fragment(R.layout.fragment_workspace_board) {
    private val model: WorkspaceBoardViewModel by viewModels()
    private var taskToReveal: Pair<String, String>? = null
    private var moveDialog: AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkspaceBoardBinding.bind(view)
        val board = binding.boardColumns
        val layout = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val lanes = BoardLaneAdapter(
            addTask = ::openTaskForm,
            renameColumn = { openAction(WorkspaceAction.RenameColumn, targetId = it.id, name = it.name) },
            openTask = ::openTask,
            moveTask = ::showMove,
        )
        board.layoutManager = layout
        board.adapter = lanes
        board.itemAnimator = null
        val maxLaneWidth = (340 * resources.displayMetrics.density).roundToInt()
        lanes.laneWidth = (resources.displayMetrics.widthPixels * .82f).roundToInt().coerceAtMost(maxLaneWidth)
        board.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                board.post { lanes.laneWidth = (board.width * .82f).roundToInt().coerceAtMost(maxLaneWidth) }
            }
        }
        BoardDragController(board) { drag, columnId, index -> commitMove(view, drag, columnId, index) }.attach()

        var chipViews = emptyMap<String, Chip>()
        var chipLabels = emptyList<Pair<String, String>>()
        var restoreColumn = true
        var focusAddedColumn = false
        board.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx == 0) return
                val closest = (0 until board.childCount).map(board::getChildAt).minByOrNull {
                    abs(layout.getDecoratedLeft(it) - board.paddingLeft)
                } ?: return
                val lane = board.getChildViewHolder(closest) as? BoardLaneAdapter.Holder ?: return
                val columnId = lane.row?.column?.id ?: return
                model.selectedColumn = columnId
                chipViews[columnId]?.let { chip ->
                    binding.columnChips.check(chip.id)
                    binding.columnChipScroll.smoothScrollTo(chip.left, 0)
                }
            }
        })

        ViewCompat.setAccessibilityHeading(binding.boardProjectName, true)
        binding.boardToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.boardRefresh.setOnClickListener { retryWorkspace(model::refresh) }
        binding.boardStatus.statusRetry.setOnClickListener { retryWorkspace(model::refresh) }
        binding.columnAdd.setOnClickListener {
            openAction(WorkspaceAction.AddColumn, scopeId = model.projectId)
        }
        binding.boardTaskCreate.setOnClickListener { openTaskForm(model.selectedColumn) }

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
        findNavController().currentBackStackEntry?.savedStateHandle?.let { entry ->
            val columnId = entry.remove<String>("boardCreatedColumnId")
            val taskId = entry.remove<String>("boardCreatedTaskId")
            if (taskId != null && columnId != null) taskToReveal = columnId to taskId
        }
        refreshWithSession(view, model::refresh)
        collectScreen(model.moveFailures) { failure ->
            val message = when (failure.status) {
                409 -> R.string.board_move_conflict
                422 -> R.string.board_move_invalid
                else -> if (failure.code == "NETWORK_ERROR") R.string.board_move_unconfirmed else R.string.board_move_failed
            }
            Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.auth_retry) { retryWorkspace(model::refresh) }
                .show()
        }
        collectScreen(model.state) { state ->
            val content = (state as? ScreenState.Ready)?.data
            binding.boardContent.isVisible = content != null
            binding.boardStatus.root.isVisible = content == null
            binding.boardMore.isVisible = content != null && canWrite(content)
            binding.boardRefresh.isEnabled = content?.busy != true
            when (state) {
                ScreenState.Loading -> binding.boardStatus.showLoading()
                is ScreenState.Failed -> {
                    if (state.error.accessLost) goBackAfterAccessLoss(R.id.workspace_detail_fragment)
                    else binding.boardStatus.showError(state.error)
                }
                is ScreenState.Ready -> {
                    val data = state.data
                    bindBoard(binding, data)
                    val columns = data.board.columns.sortedBy { it.position }
                    val labels = columns.map { it.id to it.name }
                    if (labels != chipLabels) {
                        chipLabels = labels
                        chipViews = bindChips(binding, columns, layout)
                        restoreColumn = true
                    }
                    val writable = canWrite(data)
                    lanes.submitList(columns.map { BoardLaneRow(it, data.board.boardVersion, writable) }) {
                        val reveal = taskToReveal?.takeIf { (columnId, taskId) ->
                            columns.any { column -> column.id == columnId && column.tasks.any { it.id == taskId } }
                        }
                        if (!restoreColumn && !focusAddedColumn && reveal == null) return@submitList
                        val selected = columns.indexOfFirst { it.id == (reveal?.first ?: model.selectedColumn) }
                            .coerceAtLeast(0)
                        val column = columns.getOrNull(selected) ?: return@submitList
                        val focus = focusAddedColumn
                        restoreColumn = false
                        focusAddedColumn = false
                        if (reveal != null) taskToReveal = null
                        model.selectedColumn = column.id
                        chipViews[column.id]?.let { chip ->
                            binding.columnChips.check(chip.id)
                            binding.columnChipScroll.post { binding.columnChipScroll.scrollTo(chip.left, 0) }
                        }
                        layout.scrollToPositionWithOffset(selected, 0)
                        if (focus || reveal != null) {
                            board.doOnNextLayout {
                                val holder = board.findViewHolderForAdapterPosition(selected) as? BoardLaneAdapter.Holder
                                if (focus) holder?.binding?.columnLabel?.requestFocus()
                                reveal?.let { holder?.revealTask(it.second) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        moveDialog?.dismiss()
        moveDialog = null
        super.onDestroyView()
    }

    private fun bindBoard(binding: FragmentWorkspaceBoardBinding, data: WorkspaceBoard) {
        binding.boardToolbar.subtitle = data.workspace.name
        binding.boardProjectName.text = data.project.name
        val canWrite = canWrite(data)
        binding.columnAdd.isEnabled = canWrite
        binding.boardTaskCreate.isEnabled = canWrite && data.board.columns.isNotEmpty()
        binding.boardMore.setOnClickListener { showBoardActions(binding.boardMore, data) }
        binding.boardSyncError.isVisible = data.syncError != null
        binding.boardSyncError.setText(R.string.board_sync_error)
        val count = data.board.columns.sumOf { it.tasks.size }
        binding.boardTaskCount.text = resources.getQuantityString(R.plurals.board_task_count, count, count)
    }

    private fun bindChips(
        binding: FragmentWorkspaceBoardBinding,
        columns: List<BoardColumn>,
        layout: LinearLayoutManager,
    ): Map<String, Chip> {
        binding.columnChips.removeAllViews()
        return columns.mapIndexed { index, column ->
            column.id to ItemWorkspaceColumnChipBinding.inflate(layoutInflater, binding.columnChips, false).root.apply {
                id = View.generateViewId()
                text = column.name
                binding.columnChips.addView(this)
                setOnClickListener {
                    model.selectedColumn = column.id
                    layout.scrollToPositionWithOffset(index, 0)
                }
            }
        }.toMap()
    }

    private fun openTask(task: Task) {
        val navigation = findNavController()
        if (navigation.currentDestination?.id != R.id.workspace_board_fragment) return
        navigation.navigate(
            R.id.task_detail_fragment,
            bundleOf("workspaceId" to model.workspaceId, "projectId" to model.projectId, "taskId" to task.id),
        )
    }

    private fun openTaskForm(columnId: String?) {
        val data = (model.state.value as? ScreenState.Ready)?.data ?: return
        if (!canWrite(data)) return
        val navigation = findNavController()
        if (navigation.currentDestination?.id != R.id.workspace_board_fragment) return
        navigation.navigate(
            R.id.task_form_fragment,
            bundleOf("workspaceId" to model.workspaceId, "projectId" to model.projectId, "columnId" to columnId),
        )
    }

    private fun showMove(anchor: View, task: Task) {
        val data = (model.state.value as? ScreenState.Ready)?.data ?: return
        if (!canWrite(data)) return
        val drag = BoardDrag(task.id, data.board.boardVersion)
        val columns = data.board.columns.sortedBy { it.position }
        moveDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.board_task_destination)
            .setItems(columns.map { it.name }.toTypedArray()) { _, index ->
                showMovePositions(anchor, drag, columns[index])
            }
            .setNegativeButton(R.string.workspace_cancel) { _, _ -> anchor.requestFocus() }
            .setOnCancelListener { anchor.requestFocus() }
            .show()
    }

    private fun showMovePositions(anchor: View, drag: BoardDrag, column: BoardColumn) {
        val ordered = column.tasks.sortedBy { it.position }
        val remaining = ordered.filterNot { it.id == drag.taskId }
        val labels = List(remaining.size + 1) { index ->
            when (index) {
                0 -> getString(R.string.board_task_first)
                remaining.size -> getString(R.string.board_task_last)
                else -> getString(R.string.board_task_before, remaining[index].title)
            }
        }
        var selected = ordered.indexOfFirst { it.id == drag.taskId }.takeIf { it >= 0 } ?: remaining.size
        moveDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.board_task_position)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { _, index -> selected = index }
            .setPositiveButton(R.string.board_task_move) { _, _ ->
                if (!commitMove(requireView(), drag, column.id, selected)) anchor.requestFocus()
            }
            .setNegativeButton(R.string.workspace_cancel) { _, _ -> anchor.requestFocus() }
            .setOnCancelListener { anchor.requestFocus() }
            .show()
    }

    private fun commitMove(view: View, drag: BoardDrag, columnId: String, index: Int): Boolean {
        val data = (model.state.value as? ScreenState.Ready)?.data ?: return false
        if (!canWrite(data) || drag.boardVersion != data.board.boardVersion) {
            Snackbar.make(view, R.string.board_move_conflict, Snackbar.LENGTH_LONG).show()
            return false
        }
        taskToReveal = columnId to drag.taskId
        if (!model.moveTask(drag.taskId, columnId, index)) {
            taskToReveal = null
            return false
        }
        val task = data.board.columns.asSequence().flatMap { it.tasks.asSequence() }.first { it.id == drag.taskId }
        val column = data.board.columns.first { it.id == columnId }
        view.announceForAccessibility(getString(R.string.board_move_in_progress, task.title, column.name))
        return true
    }

    private fun canWrite(data: WorkspaceBoard): Boolean =
        !data.busy && data.syncError == null && currentAccount()?.needsRetry == false

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
