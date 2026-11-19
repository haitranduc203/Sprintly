package com.example.sprintly

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineApiFlowTest {
    @Test
    fun reopensSavedBoardAndTaskOfflineAndDoesNotShowThemAfterSwitchingAccounts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sprintlyOfflineE2e") == "true")
        assumeTrue(shell("settings get global airplane_mode_on") != "1")
        val wifiEnabled = shell("settings get global wifi_on").toIntOrNull()?.let { it > 0 } == true
        val dataEnabled = shell("settings get global mobile_data") != "0"
        val runId = UUID.randomUUID().toString()
        val workspace = "Offline ${runId.take(8)}"
        val project = "Android ${runId.take(8)}"
        val task = "Việc đã tải ${runId.take(8)}"
        val draft = "Bản nháp chưa lưu"
        val password = "Sprintly-$runId!"
        val saved = withText("Đang xem dữ liệu đã lưu")
        val boardTask = allOf(withId(R.id.board_task_title), withText(task))

        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                register("offline-a-$runId@example.org", password)
                tap(withId(R.id.workspace_create))
                fill(R.id.action_name_input, workspace)
                tap(withId(R.id.action_submit))
                tap(withId(R.id.project_create))
                fill(R.id.action_name_input, project)
                tap(withId(R.id.action_submit))
                tap(allOf(withId(R.id.board_task_create), isEnabled()))
                fill(R.id.task_title_input, task)
                tap(allOf(withId(R.id.task_submit), isEnabled()))
                tap(boardTask)
                awaitDisplayed(allOf(withId(R.id.task_detail_title), withText(task)))
                tap(allOf(withId(R.id.task_edit), isEnabled()))
                fill(R.id.task_title_input, draft)

                disconnect()
                awaitDisplayed(saved)
                onView(withId(R.id.task_submit)).check(matches(not(isEnabled())))
                activity.recreate()
                awaitDisplayed(allOf(withId(R.id.task_title_input), withText(draft)))
                onView(withId(R.id.task_submit)).check(matches(not(isEnabled())))
                tap(withId(R.id.task_cancel))
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())

                awaitDisplayed(saved)
                onView(withId(R.id.task_move)).check(matches(not(isEnabled())))
                goBack()
                goBack()
                goBack()
                tap(withText(workspace))
                tap(withText(project))
                awaitDisplayed(saved)
                awaitDisplayed(boardTask)
                onView(withId(R.id.board_task_create)).check(matches(not(isEnabled())))
                tap(boardTask)
                awaitDisplayed(allOf(withId(R.id.task_detail_title), withText(task)))
                onView(withId(R.id.task_move)).check(matches(not(isEnabled())))
                restoreNetwork(wifiEnabled, dataEnabled)
                awaitNetwork()
                awaitDisplayed(allOf(withId(R.id.task_move), isEnabled()))
                disconnect()
                awaitDisplayed(saved)
                goBack()
                goBack()
                goBack()
                signOutFromWorkspace()

                restoreNetwork(wifiEnabled, dataEnabled)
                awaitNetwork()
                register("offline-b-$runId@example.org", password)
                awaitDisplayed(withText("Chưa có workspace"))
                onView(withText(workspace)).check(doesNotExist())
                signOutFromWorkspace()
            }
        } finally {
            restoreNetwork(wifiEnabled, dataEnabled)
        }
    }

    private fun register(email: String, password: String) {
        awaitLogin()
        onView(withText("Đăng ký")).perform(scrollTo(), click())
        fill(R.id.display_name_input, "Kiểm thử offline")
        fill(R.id.email_input, email)
        fill(R.id.password_input, password)
        fill(R.id.confirm_input, password)
        onView(withResourceName("submit_button")).perform(scrollTo(), click())
        awaitDisplayed(withId(R.id.workspace_profile))
    }

    private fun fill(id: Int, value: String) {
        awaitDisplayed(withId(id))
        onView(withId(id)).perform(scrollTo(), replaceText(value), closeSoftKeyboard())
    }

    private fun tap(matcher: Matcher<View>) {
        val enabled = allOf(matcher, isEnabled())
        awaitDisplayed(enabled)
        onView(enabled).perform(click())
    }

    private fun goBack() = tap(withContentDescription("Quay lại"))

    private fun disconnect() {
        shell("cmd connectivity airplane-mode enable")
        shell("svc wifi disable")
        shell("svc data disable")
    }

    private fun restoreNetwork(wifiEnabled: Boolean, dataEnabled: Boolean) {
        shell("cmd connectivity airplane-mode disable")
        shell(if (wifiEnabled) "svc wifi enable" else "svc wifi disable")
        shell(if (dataEnabled) "svc data enable" else "svc data disable")
    }

    private fun awaitNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (manager.activeNetwork == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertNotNull("The emulator needs a network to authenticate the next account", manager.activeNetwork)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }
}
