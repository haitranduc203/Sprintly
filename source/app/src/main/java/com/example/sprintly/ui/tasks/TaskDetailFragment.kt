package com.example.sprintly.ui.tasks

import android.graphics.Rect
import android.icu.text.ListFormatter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.sprintly.R
import com.example.sprintly.databinding.FragmentTaskDetailBinding
import com.example.sprintly.databinding.ItemTaskDiscussionBinding
import com.example.sprintly.offline.OfflineConnectivity
import com.example.sprintly.tasks.TaskActivity
import com.example.sprintly.tasks.TaskComment
import com.example.sprintly.ui.workspace.accessLost
import com.example.sprintly.ui.workspace.collectScreen
import com.example.sprintly.ui.workspace.currentAccount
import com.example.sprintly.ui.workspace.displayMessage
import com.example.sprintly.ui.workspace.goBackAfterAccessLoss
import com.example.sprintly.ui.workspace.initials
import com.example.sprintly.ui.workspace.refreshWithOfflineSession
import com.example.sprintly.ui.workspace.retryOfflineWorkspace
import com.example.sprintly.ui.workspace.showCached
import com.example.sprintly.ui.workspace.showError
import com.example.sprintly.ui.workspace.showLoading
import com.example.sprintly.ui.workspace.showNotCached
import com.example.sprintly.ui.workspace.temporaryReadFailure
import com.example.sprintly.workspace.BoardColumn
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class TaskDetailFragment : Fragment(R.layout.fragment_task_detail) {
    private val model: TaskDetailViewModel by viewModels()
    @Inject internal lateinit var connectivity: OfflineConnectivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentTaskDetailBinding.bind(view)
        val nav = findNavController()
        var visibleComments = emptyList<TaskComment>()
        var lastActivity: List<TaskActivity>? = null
        var lastColumnNames: Map<String, String>? = null
        var rendering = false
        val editItem = binding.taskToolbar.menu.add(Menu.NONE, R.id.task_edit, 0, R.string.task_edit)
        editItem.setIcon(R.drawable.ic_task_edit)
        editItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        editItem.setOnMenuItemClickListener {
            if (nav.currentDestination?.id != R.id.task_detail_fragment || !serverAvailable()) {
                return@setOnMenuItemClickListener false
            }
            nav.navigate(
                R.id.task_form_fragment,
                Bundle().apply {
                    putString("workspaceId", model.workspaceId)
                    putString("projectId", model.projectId)
                    putString("taskId", model.taskId)
                },
            )
            true
        }
        val deleteItem = binding.taskToolbar.menu.add(Menu.NONE, R.id.task_delete, 1, R.string.task_delete)
        deleteItem.setOnMenuItemClickListener {
            confirmDelete()
            true
        }
        editItem.isVisible = false
        deleteItem.isVisible = false
        binding.taskToolbar.setNavigationOnClickListener { closeDetail() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { closeDetail() }
        val retry = { retryOfflineWorkspace(connectivity, model::refresh) }
        binding.taskDetailStatus.statusRetry.setOnClickListener { retry() }
        binding.taskCommentsMore.setOnClickListener { if (serverAvailable()) model.loadComments() }
        binding.taskActivityMore.setOnClickListener { if (serverAvailable()) model.loadActivity() }
        binding.taskCommentAdd.setOnClickListener {
            if (binding.taskCommentInput.requestFocus()) {
                WindowCompat.getInsetsController(requireActivity().window, binding.taskCommentInput)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }
        binding.taskMove.setOnClickListener {
            if (serverAvailable()) model.state.value.details?.let(::chooseColumn)
        }
        binding.taskCommentInput.doAfterTextChanged {
            if (!rendering) model.editComment(it.toString())
        }
        binding.taskCommentSubmit.setOnClickListener {
            if (!serverAvailable()) return@setOnClickListener
            requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view.windowToken, 0)
            model.sendComment()
        }
        listOf(binding.taskDetailTitle, binding.taskDescriptionHeading, binding.taskCommentsHeading, binding.taskActivityHeading)
            .forEach { ViewCompat.setAccessibilityHeading(it, true) }

        collectScreen(model.state.combine(connectivity.connected) { state, online -> state to online }) { (state, online) ->
            if (state.deleted) {
                if (nav.currentDestination?.id == R.id.task_detail_fragment) {
                    nav.popBackStack()
                }
                return@collectScreen
            }
            val failure = state.loadFailure
            if (failure?.accessLost == true) {
                val search = nav.previousBackStackEntry?.destination?.id == R.id.task_search_fragment
                val destination = if (search && failure.code == "TASK_NOT_FOUND") R.id.task_search_fragment
                else R.id.workspace_board_fragment
                goBackAfterAccessLoss(destination)
                return@collectScreen
            }
            state.notice?.let {
                Snackbar.make(view, it, Snackbar.LENGTH_LONG).show()
                model.clearNotice()
            }
            val details = state.details
            val needsRetry = currentAccount()?.needsRetry != false
            val readOnly = !online || state.cached || needsRetry
            val showDetails = details != null && (failure == null || failure.temporaryReadFailure)
            binding.taskDetailScroll.isVisible = showDetails
            binding.taskComposer.isVisible = showDetails && (!readOnly || state.commentText.isNotEmpty())
            editItem.isVisible = showDetails && !readOnly
            deleteItem.isVisible = editItem.isVisible && details != null &&
                (details.workspaceRole == "OWNER" || details.task.createdBy == currentAccount()?.user?.id)
            val showBanner = !online || needsRetry || state.cached && failure != null
            binding.taskOfflineBanner.showCached(showDetails && showBanner, online, retry)
            when {
                showDetails -> binding.taskDetailStatus.root.isVisible = false
                !online -> binding.taskDetailStatus.showNotCached()
                failure != null -> binding.taskDetailStatus.showError(failure)
                details == null -> binding.taskDetailStatus.showLoading()
                else -> binding.taskDetailStatus.root.isVisible = false
            }
            if (details == null) return@collectScreen
            val task = details.task
            val canAct = !readOnly && !state.busy && !state.sendingComment && !state.commentUncertain
            editItem.isEnabled = canAct
            deleteItem.isEnabled = canAct
            binding.taskMove.isEnabled = canAct && details.board.columns.any { lane -> lane.tasks.any { it.id == task.id } }
            binding.taskDetailProject.text = details.projectName
            binding.taskDetailTitle.text = task.title
            binding.taskDetailPriority.text = getString(R.string.task_priority_value, getString(taskPriorityLabel(task.priority)))
            binding.taskDetailPriority.applyTaskPriorityColors(task.priority)
            binding.taskDetailAssignee.text = getString(
                R.string.task_assignee_summary, task.assignee?.displayName ?: getString(R.string.task_unassigned),
            )
            binding.taskDetailDue.text = getString(
                R.string.task_due_summary, task.dueAt?.let(requireContext()::taskDateTime) ?: getString(R.string.task_no_due),
            )
            val columnName = details.board.columns.firstOrNull { it.id == task.columnId }?.name
            binding.taskDetailColumn.isVisible = columnName != null
            binding.taskDetailColumn.text = columnName?.let { getString(R.string.task_column_summary, it) }
            binding.taskDetailDescription.text = task.description ?: getString(R.string.task_no_description)

            val comments = state.comments.rows.sortedWith(compareBy(TaskComment::createdAt, TaskComment::id))
            if (comments != visibleComments) {
                visibleComments = comments
                binding.taskCommentRows.removeAllViews()
                visibleComments.forEach {
                    addRow(binding.taskCommentRows, it.author.displayName, it.body, it.createdAt)
                }
            }
            state.confirmedCommentId?.let { id ->
                val position = visibleComments.indexOfFirst { it.id == id }
                if (position >= 0) {
                    val row = binding.taskCommentRows.getChildAt(position)
                    row.doOnLayout {
                        row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), false)
                        model.commentShown(id)
                    }
                }
            }
            val columnNames = details.board.columns.associate { it.id to it.name }
            if (state.activity.rows != lastActivity || columnNames != lastColumnNames) {
                lastActivity = state.activity.rows
                lastColumnNames = columnNames
                binding.taskActivityRows.removeAllViews()
                state.activity.rows.forEach {
                    addRow(binding.taskActivityRows, it.actor.displayName, activitySummary(it, columnNames), it.createdAt)
                }
            }
            binding.taskCommentsHeading.text = if (state.comments.next == null && state.comments.rows.isNotEmpty()) {
                getString(R.string.task_comments_count, state.comments.rows.size)
            } else getString(R.string.task_comments)
            renderPage(state.comments, binding.taskCommentsStatus, binding.taskCommentsMore, R.string.task_no_comments, R.string.task_more_comments, readOnly)
            renderPage(state.activity, binding.taskActivityStatus, binding.taskActivityMore, R.string.task_no_activity, R.string.task_more_activity, readOnly)

            rendering = true
            if (binding.taskCommentInput.text.toString() != state.commentText) {
                binding.taskCommentInput.setText(state.commentText)
            }
            rendering = false
            binding.taskCommentInput.isEnabled = !readOnly && !state.sendingComment && !state.commentUncertain
            binding.taskCommentAdd.isVisible = !readOnly
            binding.taskCommentAdd.isEnabled = canAct
            binding.taskCommentSubmit.isEnabled = !readOnly && !state.busy && !state.sendingComment &&
                !state.comments.loading && !state.comments.awaitingFirstPage
            binding.taskCommentSubmit.setText(
                when {
                    state.sendingComment -> R.string.task_sending
                    state.commentUncertain -> R.string.auth_retry
                    else -> R.string.task_send_comment
                },
            )
            val inputError = state.commentError == R.string.task_comment_length
            binding.taskCommentLayout.error = if (inputError) getString(R.string.task_comment_length) else null
            binding.taskCommentError.isVisible = state.commentError != null && !inputError
            if (state.commentError != null && !inputError) binding.taskCommentError.setText(state.commentError)
        }
        refreshWithOfflineSession(view, connectivity, model::refresh)
    }

    private fun serverAvailable(): Boolean = connectivity.isConnected() &&
        model.state.value.canUseServer && currentAccount()?.needsRetry == false

    private fun closeDetail() {
        val state = model.state.value
        if (state.busy || state.sendingComment) return
        if (state.commentText.isBlank() && !state.commentUncertain) {
            findNavController().navigateUp()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_discard_title)
            .setMessage(if (state.commentUncertain) R.string.task_discard_uncertain else R.string.task_discard_message)
            .setNegativeButton(R.string.task_keep_editing, null)
            .setPositiveButton(R.string.task_discard) { _, _ -> findNavController().navigateUp() }
            .show()
    }

    private fun chooseColumn(details: TaskDetails) {
        val columns = details.board.columns
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_choose_column)
            .setItems(columns.map { it.name }.toTypedArray()) { _, index -> choosePosition(columns[index]) }
            .setNegativeButton(R.string.workspace_cancel, null)
            .show()
    }

    private fun choosePosition(column: BoardColumn) {
        if (!serverAvailable()) return
        val tasks = column.tasks.sortedBy { it.position }.filterNot { it.id == model.taskId }
        val labels = (0..tasks.size).map { index ->
            when {
                index == 0 -> getString(R.string.board_task_first)
                index == tasks.size -> getString(R.string.board_task_last)
                else -> getString(R.string.board_task_before, tasks[index].title)
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_choose_position)
            .setItems(labels.toTypedArray()) { _, index ->
                val currentColumn = model.state.value.details?.board?.columns?.firstOrNull { it.id == column.id }
                if (!serverAvailable()) return@setItems
                if (currentColumn == column) model.move(column.id, index)
                else view?.let { Snackbar.make(it, R.string.task_move_failed, Snackbar.LENGTH_LONG).show() }
            }
            .setNegativeButton(R.string.workspace_cancel, null)
            .show()
    }

    private fun confirmDelete() {
        if (!serverAvailable()) return
        val task = model.state.value.details?.task ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.task_delete_title, task.title))
            .setMessage(R.string.task_delete_message)
            .setNegativeButton(R.string.workspace_cancel, null)
            .setPositiveButton(R.string.task_delete) { _, _ -> if (serverAvailable()) model.delete() }
            .show()
    }

    private fun activitySummary(activity: TaskActivity, columnNames: Map<String, String>): String {
        val payload = activity.payload.orEmpty()
        return when (activity.action) {
            "CREATED" -> getString(R.string.task_activity_created)
            "UPDATED" -> {
                val changes = payload["changes"] as? Map<*, *>
                val fields = listOf(
                    "title" to R.string.task_title,
                    "description" to R.string.task_description,
                    "priority" to R.string.task_priority,
                    "dueAt" to R.string.task_due,
                ).mapNotNull { (field, label) ->
                    if (changes?.containsKey(field) == true) getString(label).replaceFirstChar { it.lowercase() }
                    else null
                }
                if (fields.isEmpty()) getString(R.string.task_activity_updated)
                else {
                    val formatter = ListFormatter.getInstance(resources.configuration.locales[0])
                    getString(R.string.task_activity_updated_fields, formatter.format(fields))
                }
            }
            "MOVED" -> {
                val columnName = (payload["toColumnId"] as? String)?.let(columnNames::get)
                if (columnName == null) getString(R.string.task_activity_moved)
                else getString(R.string.task_activity_moved_to, columnName)
            }
            "ASSIGNEE_CHANGED" -> getString(
                if (payload.containsKey("toAssigneeId") && payload["toAssigneeId"] == null) R.string.task_activity_unassigned
                else R.string.task_activity_assigned,
            )
            else -> getString(R.string.task_activity_other)
        }
    }

    private fun addRow(container: LinearLayout, name: String, body: String, timestamp: String) {
        val row = ItemTaskDiscussionBinding.inflate(layoutInflater, container, true)
        row.discussionMark.text = initials(name)
        row.discussionAuthor.text = name
        row.discussionBody.text = body
        row.discussionTime.text = requireContext().taskDateTime(timestamp)
    }

    private fun renderPage(
        page: DiscussionPage<*>,
        status: TextView,
        more: MaterialButton,
        @StringRes empty: Int,
        @StringRes moreLabel: Int,
        readOnly: Boolean,
    ) {
        val message = when {
            readOnly && (page.next != null || page.failure != null) -> R.string.task_discussion_connect
            page.failure != null -> page.failure.displayMessage()
            page.loading || page.awaitingFirstPage -> R.string.task_loading_discussion
            page.rows.isEmpty() && page.next == null -> empty
            else -> null
        }
        status.isVisible = message != null
        message?.let { if (status.text != getString(it)) status.setText(it) }
        more.isVisible = !readOnly && !page.loading && page.next != null && (page.failure != null || page.rows.isNotEmpty())
        more.setText(if (page.failure != null) R.string.auth_retry else moreLabel)
    }
}
