package com.example.sprintly.ui.workspace

import android.view.DragEvent
import androidx.recyclerview.widget.RecyclerView

internal class BoardDragController(
    private val lanes: RecyclerView,
    private val move: (BoardDrag, String, Int) -> Boolean,
) {
    private val density = lanes.resources.displayMetrics.density
    private var drag: BoardDrag? = null
    private var target: Target? = null
    private var x = 0f
    private var y = 0f

    private val scrollWhileDragging = object : Runnable {
        override fun run() {
            if (drag == null || !lanes.isAttachedToWindow) return
            val horizontal = edgeStep(x, lanes.width)
            if (horizontal != 0) lanes.scrollBy(horizontal, 0)
            val lane = findLane()
            if (lane != null) {
                val vertical = edgeStep(cardY(lane.cards), lane.cards.height)
                if (vertical != 0) lane.cards.scrollBy(0, vertical)
            }
            setTarget(findTarget())
            if (horizontal != 0 || lane?.let { edgeStep(cardY(it.cards), it.cards.height) != 0 } == true) {
                lanes.postOnAnimation(this)
            }
        }
    }

    fun attach() {
        lanes.setOnDragListener { _, event ->
            val payload = event.localState as? BoardDrag ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    drag = payload
                    x = event.x
                    y = event.y
                    setTarget(findTarget())
                    lanes.removeCallbacks(scrollWhileDragging)
                    lanes.postOnAnimation(scrollWhileDragging)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    drag = payload
                    x = event.x
                    y = event.y
                    val destination = findTarget()
                    clear()
                    destination?.let { move(payload, it.columnId, it.index) } == true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    clear()
                    true
                }
                else -> true
            }
        }
    }

    private fun clear() {
        drag = null
        lanes.removeCallbacks(scrollWhileDragging)
        setTarget(null)
    }

    private fun findLane(): BoardLaneAdapter.Holder? {
        val view = lanes.findChildViewUnder(x, y) ?: return null
        return lanes.getChildViewHolder(view) as? BoardLaneAdapter.Holder
    }

    private fun findTarget(): Target? {
        val payload = drag ?: return null
        val lane = findLane() ?: return null
        val row = lane.row?.takeIf { it.canWrite && it.boardVersion == payload.boardVersion } ?: return null
        val cards = lane.cards
        val localY = cardY(cards)
        val tasks = row.column.tasks.sortedBy { it.position }
        var rawIndex = 0
        var marker = cards.paddingTop.toFloat()
        for (i in 0 until cards.childCount) {
            val card = cards.getChildAt(i)
            val index = cards.getChildAdapterPosition(card)
            if (index == RecyclerView.NO_POSITION) continue
            if (localY < (card.top + card.bottom) / 2f) {
                rawIndex = index
                marker = card.top.toFloat()
                break
            }
            rawIndex = index + 1
            marker = card.bottom.toFloat()
        }
        rawIndex = rawIndex.coerceIn(0, tasks.size)
        val sourceIndex = tasks.indexOfFirst { it.id == payload.taskId }
        val index = if (sourceIndex in 0 until rawIndex) rawIndex - 1 else rawIndex
        return Target(lane, row.column.id, index, marker.coerceIn(1f, (cards.height - 1f).coerceAtLeast(1f)))
    }

    private fun setTarget(next: Target?) {
        if (target == next) return
        if (target?.lane !== next?.lane) target?.lane?.showDrop(null)
        target = next
        next?.lane?.showDrop(next.marker)
    }

    private fun cardY(cards: RecyclerView): Float {
        val lanesLocation = IntArray(2).also(lanes::getLocationInWindow)
        val cardsLocation = IntArray(2).also(cards::getLocationInWindow)
        return y + lanesLocation[1] - cardsLocation[1]
    }

    private fun edgeStep(coordinate: Float, length: Int): Int {
        val edge = (36 * density).coerceAtMost(length / 3f)
        val step = (12 * density).toInt()
        return when {
            coordinate in 0f..edge -> -step
            coordinate in (length - edge)..length.toFloat() -> step
            else -> 0
        }
    }

    private data class Target(
        val lane: BoardLaneAdapter.Holder,
        val columnId: String,
        val index: Int,
        val marker: Float,
    )
}
