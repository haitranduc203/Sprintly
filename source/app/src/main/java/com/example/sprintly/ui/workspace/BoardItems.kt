package com.example.sprintly.ui.workspace

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sprintly.R
import com.example.sprintly.databinding.ItemBoardTaskBinding
import com.example.sprintly.databinding.ItemWorkspaceColumnBinding
import com.example.sprintly.tasks.Task
import com.example.sprintly.workspace.BoardColumn
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import kotlin.math.hypot

internal data class BoardLaneRow(val column: BoardColumn, val boardVersion: Long, val canWrite: Boolean)

internal class BoardLaneAdapter(
    private val addTask: (String) -> Unit,
    private val renameColumn: (BoardColumn) -> Unit,
    private val openTask: (Task) -> Unit,
    private val moveTask: (View, Task) -> Unit,
) : ListAdapter<BoardLaneRow, BoardLaneAdapter.Holder>(DIFF) {
    var laneWidth = 0
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemWorkspaceColumnBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.render(getItem(position))

    inner class Holder(val binding: ItemWorkspaceColumnBinding) : RecyclerView.ViewHolder(binding.root) {
        val cards = binding.columnTasks
        private val tasks = BoardTaskAdapter(openTask, moveTask)
        private var revealId: String? = null
        private val dropMarker = BoardDropMarker(cards)
        var row: BoardLaneRow? = null
            private set

        init {
            ViewCompat.setAccessibilityHeading(binding.columnLabel, true)
            binding.columnLabel.isFocusable = true
            cards.layoutManager = LinearLayoutManager(cards.context)
            cards.adapter = tasks
            cards.itemAnimator = null
            cards.isNestedScrollingEnabled = false
            cards.addItemDecoration(dropMarker)
        }

        fun render(data: BoardLaneRow) {
            if (laneWidth > 0) binding.root.layoutParams = binding.root.layoutParams.apply { width = laneWidth }
            val column = data.column
            val context = binding.root.context
            if (row?.column?.id != column.id) {
                cards.scrollToPosition(0)
                revealId = null
                showDrop(null)
            }
            row = data
            binding.columnLabel.text = column.name
            val locale = context.resources.configuration.locales[0]
            binding.columnTaskCount.text = NumberFormat.getIntegerInstance(locale).format(column.tasks.size)
            binding.columnTaskCount.contentDescription =
                context.resources.getQuantityString(R.plurals.board_task_count, column.tasks.size, column.tasks.size)
            val dotColor = when (column.position) {
                0 -> R.color.orbix_info_container
                1 -> R.color.orbix_warning_container
                2 -> R.color.orbix_success_container
                else -> R.color.orbix_divider
            }
            binding.columnDot.backgroundTintList = context.tint(dotColor)
            binding.columnEmpty.isVisible = column.tasks.isEmpty()
            binding.columnMore.isEnabled = data.canWrite
            binding.columnMore.contentDescription = context.getString(R.string.workspace_column_actions, column.name)
            binding.columnMore.setOnClickListener {
                it.showActions(R.string.workspace_column_edit to { renameColumn(column) })
            }
            binding.columnTaskCreate.isEnabled = data.canWrite
            binding.columnTaskCreate.contentDescription = context.getString(R.string.board_task_add_to, column.name)
            binding.columnTaskCreate.setOnClickListener { addTask(column.id) }
            tasks.update(column.tasks.sortedBy { it.position }.map { BoardTaskRow(it, data.boardVersion, data.canWrite) })
            scrollToRequestedTask()
        }

        fun showDrop(y: Float?) {
            binding.root.isActivated = y != null
            dropMarker.y = y
            cards.invalidate()
        }

        fun revealTask(taskId: String) {
            revealId = taskId
            scrollToRequestedTask()
        }

        private fun scrollToRequestedTask() {
            val taskId = revealId ?: return
            val index = tasks.currentList.indexOfFirst { it.task.id == taskId }
            if (index < 0) return
            revealId = null
            cards.scrollToPosition(index)
            cards.doOnNextLayout {
                val card = cards.findViewHolderForAdapterPosition(index)?.itemView ?: return@doOnNextLayout
                card.requestFocus()
                if (card.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true) {
                    ViewCompat.performAccessibilityAction(card, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BoardLaneRow>() {
            override fun areItemsTheSame(old: BoardLaneRow, new: BoardLaneRow) = old.column.id == new.column.id
            override fun areContentsTheSame(old: BoardLaneRow, new: BoardLaneRow) = old == new
        }
    }
}

internal data class BoardDrag(val taskId: String, val boardVersion: Long)
private data class BoardTaskRow(val task: Task, val boardVersion: Long, val canWrite: Boolean)

private class BoardTaskAdapter(
    private val openTask: (Task) -> Unit,
    private val moveTask: (View, Task) -> Unit,
) : RecyclerView.Adapter<BoardTaskAdapter.Holder>() {
    var currentList = emptyList<BoardTaskRow>()
        private set

    fun update(rows: List<BoardTaskRow>) {
        val old = currentList
        if (old == rows) return
        // Publish both lanes in the same layout pass when a task changes columns.
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = rows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].task.id == rows[newItemPosition].task.id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition] == rows[newItemPosition]
        })
        currentList = rows
        changes.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemBoardTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount() = currentList.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.render(currentList[position])

    inner class Holder(private val binding: ItemBoardTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun render(row: BoardTaskRow) {
            val (task, boardVersion, canWrite) = row
            val context = binding.root.context
            binding.boardTaskTitle.text = task.title
            binding.root.setOnClickListener { openTask(task) }
            binding.boardTaskDrag.isEnabled = canWrite
            binding.boardTaskActions.isEnabled = canWrite
            binding.boardTaskDrag.contentDescription = context.getString(R.string.board_task_drag, task.title)
            binding.boardTaskActions.contentDescription = context.getString(R.string.board_task_actions, task.title)
            binding.boardTaskActions.setOnClickListener { anchor ->
                anchor.showActions(R.string.board_task_move to { moveTask(anchor, task) })
            }
            binding.boardTaskDrag.setOnClickListener { moveTask(it, task) }
            binding.boardTaskDrag.bindDrag(BoardDrag(task.id, boardVersion), binding.root, canWrite)

            val (label, color, background) = when (task.priority) {
                "HIGH" -> Triple(R.string.board_task_high, R.color.orbix_warning, R.color.orbix_warning_container)
                "URGENT" -> Triple(R.string.board_task_urgent, R.color.orbix_error, R.color.orbix_error_container)
                "LOW" -> Triple(R.string.board_task_low, R.color.orbix_on_surface_variant, R.color.orbix_divider)
                else -> Triple(R.string.board_task_medium, R.color.orbix_on_surface_variant, R.color.orbix_divider)
            }
            binding.boardTaskPriority.setText(label)
            binding.boardTaskPriority.setTextColor(context.getColor(color))
            binding.boardTaskPriority.backgroundTintList = context.tint(background)

            val due = task.dueAt?.let(::parseDueDate)
            val overdue = due?.isBefore(Instant.now()) == true
            binding.boardTaskDue.text = due?.let {
                val date = android.text.format.DateFormat.getDateFormat(context).format(Date.from(it))
                context.getString(if (overdue) R.string.board_task_overdue else R.string.board_task_due, date)
            }.orEmpty()
            binding.boardTaskDue.setTextColor(
                context.getColor(if (overdue) R.color.orbix_error else R.color.orbix_on_surface_variant),
            )
            val assignee = task.assignee?.displayName
            binding.boardTaskAssignee.text = assignee?.let(::initials) ?: context.getString(R.string.board_task_unassigned)
            binding.boardTaskAssignee.contentDescription = assignee?.let { context.getString(R.string.board_task_assignee, it) }
            binding.boardTaskAssignee.setBackgroundResource(if (assignee == null) 0 else R.drawable.bg_workspace_avatar)
            ViewCompat.setScreenReaderFocusable(binding.root, true)
        }
    }

}

// The handler consumes drags and leaves ordinary taps to the button's click handler.
@SuppressLint("ClickableViewAccessibility")
private fun View.bindDrag(drag: BoardDrag, card: View, canWrite: Boolean) {
    if (!canWrite) {
        setOnLongClickListener(null)
        setOnTouchListener(null)
        return
    }
    fun start(): Boolean = startDragAndDrop(
        ClipData.newPlainText("", ""),
        View.DragShadowBuilder(card),
        drag,
        0,
    )
    setOnLongClickListener { start() }
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var startX = 0f
    var startY = 0f
    var dragging = false
    setOnTouchListener { handle, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
                handle.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.x - startX, event.y - startY) > touchSlop) {
                    dragging = start()
                    if (dragging) handle.cancelLongPress()
                }
                dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handle.parent.requestDisallowInterceptTouchEvent(false)
                false
            }
            else -> false
        }
    }
}

private fun parseDueDate(value: String): Instant? = try {
    Instant.parse(value)
} catch (_: DateTimeParseException) {
    null
}

private class BoardDropMarker(view: View) : RecyclerView.ItemDecoration() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = view.context.getColor(R.color.orbix_selection)
        strokeWidth = 2 * view.resources.displayMetrics.density
    }
    var y: Float? = null

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val line = y ?: return
        canvas.drawLine(parent.paddingLeft.toFloat(), line, (parent.width - parent.paddingRight).toFloat(), line, paint)
    }
}
