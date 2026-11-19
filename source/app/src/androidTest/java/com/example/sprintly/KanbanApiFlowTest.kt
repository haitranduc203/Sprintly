package com.example.sprintly

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isFocused
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import java.util.UUID
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class KanbanApiFlowTest {
    @Test
    fun draggingBetweenColumnsPersistsAndAppearsInTaskHistory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sprintlyTaskE2e") == "true")
        val id = UUID.randomUUID()
        val password = "Sprintly-$id!"
        val task = "Kéo thả ${id.toString().take(8)}"
        val existingTask = "Đang làm ${id.toString().take(8)}"
        val editedTitle = "$task đã sửa"
        val comment = "Đã kiểm tra vị trí mới."

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitLogin()
            onView(withText("Đăng ký")).perform(scrollTo(), click())
            fill(R.id.display_name_input, "Kiểm thử Kanban")
            fill(R.id.email_input, "kanban-$id@example.org")
            fill(R.id.password_input, password)
            fill(R.id.confirm_input, password)
            onView(withResourceName("submit_button")).perform(scrollTo(), click())

            tap(withId(R.id.workspace_create))
            fill(R.id.action_name_input, "Kanban $id")
            tap(withId(R.id.action_submit))
            tap(withId(R.id.project_create))
            fill(R.id.action_name_input, "Android Kanban")
            tap(withId(R.id.action_submit))

            tap(withId(R.id.board_task_create))
            fillTaskTitle(task)
            tap(withId(R.id.task_submit))
            val title = allOf(withId(R.id.board_task_title), withText(task))
            awaitDisplayed(allOf(title, isDescendantOfA(lane("To Do"))))

            jumpTo("In Progress")
            tap(withContentDescription("Thêm công việc vào In Progress"))
            fillTaskTitle(existingTask)
            tap(withId(R.id.task_submit))
            awaitDisplayed(allOf(withId(R.id.board_task_title), withText(existingTask), isDescendantOfA(lane("In Progress"))))

            jumpTo("To Do")
            awaitDisplayed(allOf(title, isDescendantOfA(lane("To Do"))))
            val dragHandle = allOf(withId(R.id.board_task_drag), isEnabled(), isDescendantOfA(lane("To Do")))
            awaitDisplayed(dragHandle)
            onView(dragHandle).perform(dragTo("In Progress"))
            jumpTo("In Progress")
            val movedTitle = allOf(title, isDescendantOfA(lane("In Progress")))
            awaitDisplayed(movedTitle)
            val refresh = allOf(withId(R.id.board_refresh), isEnabled())
            tap(refresh)
            awaitDisplayed(refresh)
            awaitDisplayed(movedTitle)
            onView(allOf(withId(R.id.column_tasks), isDescendantOfA(lane("In Progress")))).check { view, _ ->
                val cards = view as RecyclerView
                val titles = (0 until cards.childCount).map {
                    cards.getChildAt(it).findViewById<TextView>(R.id.board_task_title).text.toString()
                }
                assertEquals(listOf(task, existingTask), titles.take(2))
            }

            tap(movedTitle)
            awaitDisplayed(allOf(withId(R.id.task_detail_title), withText(task)))
            val activityLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.task_activity_moved)
            val moveActivity = allOf(withText(startsWith(activityLabel)), isDescendantOfA(withId(R.id.task_activity_rows)))
            awaitScrolledTo(moveActivity)

            val commentInput = withId(R.id.task_comment_input)
            val savedComment = allOf(withText(comment), isDescendantOfA(withId(R.id.task_comment_rows)))
            awaitScrolledTo(allOf(withId(R.id.task_comments_status), withText(R.string.task_no_comments)))
            val addComment = withText("Thêm bình luận")
            awaitScrolledTo(addComment)
            tap(addComment)
            awaitDisplayed(allOf(commentInput, isFocused()))
            onView(commentInput).perform(replaceText(" "), closeSoftKeyboard())
            tap(withId(R.id.task_comment_submit))
            awaitDisplayed(withText(R.string.task_comment_length))
            awaitDisplayed(commentInput)
            onView(commentInput).perform(replaceText(comment), closeSoftKeyboard())
            tap(allOf(withId(R.id.task_comment_submit), isEnabled()))
            awaitDisplayed(savedComment)

            tap(withId(R.id.task_edit))
            awaitDisplayed(allOf(withId(R.id.task_title_input), withText(task)))
            fillTaskTitle(editedTitle)
            tap(withId(R.id.task_submit))
            val editedDetailTitle = allOf(withId(R.id.task_detail_title), withText(editedTitle))
            awaitScrolledTo(editedDetailTitle)
            tap(withContentDescription("Quay lại"))
            tap(allOf(withId(R.id.board_task_title), withText(editedTitle), isDescendantOfA(lane("In Progress"))))
            awaitDisplayed(editedDetailTitle)
            awaitScrolledTo(savedComment)
            awaitScrolledTo(moveActivity)
            awaitScrolledTo(allOf(withText("Đã cập nhật tiêu đề"), isDescendantOfA(withId(R.id.task_activity_rows))))

            tap(withContentDescription("Quay lại"))
            tap(withContentDescription("Quay lại"))
            tap(withContentDescription("Quay lại"))
            signOutFromWorkspace()
        }
    }

    private fun lane(label: String): Matcher<View> =
        allOf(withId(R.id.board_lane), hasDescendant(allOf(withId(R.id.column_label), withText(label))))

    private fun dragTo(label: String): ViewAction = object : ViewAction {
        override fun getDescription() = "Drag the task handle into $label"

        override fun getConstraints(): Matcher<View> = allOf(isDisplayed(), isEnabled())

        override fun perform(ui: UiController, handle: View) {
            val board = handle.rootView.findViewById<RecyclerView>(R.id.board_columns)
            val target = (0 until board.childCount).map(board::getChildAt).single { lane ->
                lane.findViewById<TextView>(R.id.column_label).text.toString() == label
            }
            val firstTask = checkNotNull(target.findViewById<RecyclerView>(R.id.column_tasks).getChildAt(0))
            val grip = Rect()
            val destination = Rect()
            val insertion = Rect()
            val boardBounds = Rect()
            assertTrue(board.getGlobalVisibleRect(boardBounds))
            assertTrue(target.getGlobalVisibleRect(destination))
            val edge = 36 * board.resources.displayMetrics.density
            val safeRight = boardBounds.right - edge - 2
            // Expose part of the destination outside the board's auto-scroll region.
            val offset = (destination.left + edge / 2 - safeRight).toInt()
            if (offset > 0) {
                board.scrollBy(offset, 0)
                ui.loopMainThreadUntilIdle()
            }
            assertTrue(handle.getGlobalVisibleRect(grip))
            assertTrue(target.getGlobalVisibleRect(destination))
            assertTrue(firstTask.getGlobalVisibleRect(insertion))
            val rootLocation = IntArray(2).also(handle.rootView::getLocationOnScreen)
            val downTime = SystemClock.uptimeMillis()
            val startX = grip.exactCenterX() + rootLocation[0]
            val startY = grip.exactCenterY() + rootLocation[1]
            val targetX = min(destination.exactCenterX(), safeRight)
            assertTrue("The target needs a drop point inside the viewport", targetX > destination.left)
            val endX = targetX + rootLocation[0]
            val endY = insertion.top + insertion.height() / 4f + rootLocation[1]

            fun inject(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    assertTrue(ui.injectMotionEvent(event))
                } finally {
                    event.recycle()
                }
            }

            inject(MotionEvent.ACTION_DOWN, startX, startY)
            ui.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            for (step in 1..8) {
                val fraction = step / 8f
                inject(MotionEvent.ACTION_MOVE, startX + fraction * (endX - startX), startY + fraction * (endY - startY))
                ui.loopMainThreadForAtLeast(16)
            }
            val hoverDeadline = SystemClock.uptimeMillis() + 2_000
            while (!target.isActivated && SystemClock.uptimeMillis() < hoverDeadline) {
                ui.loopMainThreadForAtLeast(16)
            }
            val highlighted = target.isActivated
            inject(MotionEvent.ACTION_UP, endX, endY)
            ui.loopMainThreadForAtLeast(150)
            assertTrue("The hovered column should show its drop indicator", highlighted)
        }
    }

    private fun jumpTo(label: String) = tap(allOf(isAssignableFrom(Chip::class.java), withText(label)))

    private fun fill(id: Int, value: String) {
        onView(withId(id)).perform(scrollTo(), replaceText(value), closeSoftKeyboard())
    }

    private fun fillTaskTitle(value: String) {
        awaitDisplayed(withId(R.id.task_title_input))
        fill(R.id.task_title_input, value)
    }

    private fun tap(matcher: Matcher<View>) {
        val enabled = allOf(matcher, isEnabled())
        awaitDisplayed(enabled)
        onView(enabled).perform(click())
    }

    private fun awaitScrolledTo(matcher: Matcher<View>) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(matcher).perform(scrollTo()).check(matches(isDisplayed()))
                return
            } catch (_: NoMatchingViewException) {
                SystemClock.sleep(100)
            }
        }
        onView(matcher).perform(scrollTo()).check(matches(isDisplayed()))
    }
}
