package com.example.sprintly

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import java.util.UUID
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceApiFlowTest {
    @Test
    fun ownerManagesMembersAndBothRolesManageProjects() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sprintlyWorkspaceE2e") == "true")
        val runId = UUID.randomUUID().toString()
        val owner = "owner-$runId@example.org"
        val member = "member-$runId@example.org"
        val password = "Sprintly-$runId!"
        val workspace = "Workspace ${runId.take(8)}"
        val renamedWorkspace = "$workspace v2"
        val project = "Android ${runId.take(8)}"
        val renamedProject = "$project v2"
        val column = "Review"
        val renamedColumn = "Ready for QA"

        ActivityScenario.launch(MainActivity::class.java).use {
            register(member, "Thành viên kiểm thử", password)
            signOutFromWorkspace()
            register(owner, "Chủ workspace kiểm thử", password)

            tap(withId(R.id.workspace_create))
            fill(R.id.action_name_input, workspace)
            submitAction()
            awaitDisplayed(withText("Chưa có dự án"))

            tap(withContentDescription("Thao tác workspace"))
            tap(withText("Sửa workspace"))
            fill(R.id.action_name_input, renamedWorkspace)
            submitAction()
            awaitDisplayed(allOf(withParent(withId(R.id.workspace_toolbar)), withText(renamedWorkspace)))

            tap(withText("Thành viên"))
            tap(withId(R.id.member_add))
            fill(R.id.action_name_input, member)
            tap(withId(R.id.member_lookup))
            tap(withId(R.id.member_candidate))
            submitAction()
            awaitDisplayed(allOf(withId(R.id.row_description), withText(member)))

            tap(withContentDescription("Quay lại"))
            signOutFromWorkspace()
            login(member, password)
            tap(withText(renamedWorkspace))
            tap(withText("Thành viên"))
            awaitDisplayed(allOf(withId(R.id.row_description), withText(member)))
            onView(withId(R.id.member_add)).check(matches(not(isDisplayed())))

            tap(withText("Dự án"))
            tap(withId(R.id.project_create))
            fill(R.id.action_name_input, project)
            submitAction()
            awaitDisplayed(withText("Bảng Kanban"))
            listOf("To Do", "In Progress", "Done").forEach { label ->
                awaitDisplayed(allOf(isAssignableFrom(Chip::class.java), withText(label)))
            }
            awaitDisplayed(allOf(withId(R.id.column_label), withText("To Do")))
            tap(withContentDescription("Thao tác bảng"))
            onView(withText("Xóa dự án")).check(doesNotExist())
            tap(withText("Sửa dự án"))
            fill(R.id.action_name_input, renamedProject)
            submitAction()
            awaitDisplayed(allOf(withId(R.id.board_project_name), withText(renamedProject)))

            tap(withId(R.id.column_add))
            fill(R.id.action_name_input, column)
            submitAction()
            awaitDisplayed(allOf(withId(R.id.column_label), withText(column)))
            tap(withContentDescription("Thao tác cột $column"))
            tap(withText("Đổi tên cột"))
            fill(R.id.action_name_input, renamedColumn)
            submitAction()
            awaitDisplayed(allOf(withId(R.id.column_label), withText(renamedColumn)))

            tap(withContentDescription("Quay lại"))
            tap(withContentDescription("Quay lại"))
            signOutFromWorkspace()
            login(owner, password)
            tap(withText(renamedWorkspace))
            tap(withText(renamedProject))
            tap(withContentDescription("Thao tác bảng"))
            tap(withText("Xóa dự án"))
            submitAction()
            awaitDisplayed(withText("Chưa có dự án"))

            tap(withText("Thành viên"))
            tap(withContentDescription("Thao tác cho Thành viên kiểm thử"))
            tap(withText("Gỡ thành viên"))
            submitAction()
            awaitDisplayed(withText("Chưa có thành viên khác"))

            tap(withContentDescription("Thao tác workspace"))
            tap(withText("Xóa workspace"))
            submitAction()
            awaitDisplayed(withText("Chưa có workspace"))
            signOutFromWorkspace()
            login(member, password)
            awaitDisplayed(withText("Chưa có workspace"))
            signOutFromWorkspace()
        }
    }

    private fun register(email: String, displayName: String, password: String) {
        awaitLogin()
        onView(withText("Đăng ký")).perform(scrollTo(), click())
        fill(R.id.display_name_input, displayName)
        fill(R.id.email_input, email)
        fill(R.id.password_input, password)
        fill(R.id.confirm_input, password)
        submitAuth()
    }

    private fun login(email: String, password: String) {
        awaitLogin()
        fill(R.id.email_input, email)
        fill(R.id.password_input, password)
        submitAuth()
    }

    private fun fill(id: Int, value: String) {
        onView(withId(id)).perform(scrollTo(), replaceText(value), closeSoftKeyboard())
    }

    private fun submitAction() = tap(withId(R.id.action_submit))

    private fun submitAuth() {
        onView(withResourceName("submit_button")).perform(scrollTo(), click())
        awaitDisplayed(withId(R.id.workspace_profile))
    }

    private fun tap(matcher: Matcher<View>) {
        awaitDisplayed(matcher)
        onView(matcher).perform(click())
    }
}
