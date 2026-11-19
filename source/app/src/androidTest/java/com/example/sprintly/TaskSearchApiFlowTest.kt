package com.example.sprintly

import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskSearchApiFlowTest {
    @Test
    fun combinesTitleAndFiltersAndRestoresResultsAfterReturningAndRecreating() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sprintlySearchE2e") == "true")
        val id = UUID.randomUUID().toString()
        val email = "search-$id@example.org"
        val password = "Sprintly-$id!"
        val ownerName = "Kiểm thử tìm kiếm"
        val workspaceName = "Search ${id.take(8)}"
        val projectName = "Android Search"
        val task = "Audit màn hình"
        seed(email, password, ownerName, workspaceName, projectName, task)

        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            awaitLogin()
            onView(withId(R.id.email_input)).perform(scrollTo(), replaceText(email))
            onView(withId(R.id.password_input)).perform(scrollTo(), replaceText(password), closeSoftKeyboard())
            onView(withResourceName("submit_button")).perform(scrollTo(), click())
            tap(withText(workspaceName))
            tap(withText(projectName))
            tap(withId(R.id.board_task_search))
            awaitCount(5)

            enterKeyword("AUDIT")
            awaitCount(4)
            tap(withId(R.id.search_open_filters))
            tap(withId(R.id.search_pick_column))
            onView(withText("To Do")).inRoot(isDialog()).perform(click())
            tap(withId(R.id.search_pick_priority))
            onView(withText(R.string.task_priority_high)).inRoot(isDialog()).perform(click())
            tap(withId(R.id.search_pick_assignee))
            onView(withText("$ownerName · $email")).inRoot(isDialog()).perform(click())
            tap(withId(R.id.search_apply_filters))

            val result = allOf(withId(R.id.search_result_title), withText(task))
            awaitCount(1)
            tap(result)
            awaitDisplayed(allOf(withId(R.id.task_detail_title), withText(task)))
            goBack()
            awaitDisplayed(result)
            activity.recreate()
            awaitDisplayed(result)
            awaitCount(1)
            onView(withId(R.id.search_keyword)).check(matches(withText("AUDIT")))

            tap(result)
            val move = withId(R.id.task_move)
            tap(move)
            onView(withText("In Progress")).inRoot(isDialog()).perform(click())
            onView(withText(R.string.board_task_first)).inRoot(isDialog()).perform(click())
            awaitDisplayed(allOf(move, isEnabled()))
            goBack()
            awaitDisplayed(withText(R.string.task_search_empty))

            tap(withId(R.id.search_open_filters))
            tap(withId(R.id.search_clear_filters))
            awaitCount(4)
            onView(withId(R.id.search_keyword)).check(matches(withText("AUDIT")))
            enterKeyword("không trùng tiêu đề nào")
            awaitDisplayed(withText(R.string.task_search_empty))
            tap(allOf(withId(R.id.status_retry), withText(R.string.task_search_reset)))
            awaitCount(5)
            onView(withId(R.id.search_keyword)).check(matches(withText("")))

            withAirplaneMode {
                awaitDisplayed(withText(R.string.task_search_offline))
                tap(withText(R.string.task_search_back_to_board))
                awaitDisplayed(withText(R.string.workspace_offline_cached))
            }
            awaitDisplayed(allOf(withId(R.id.board_task_create), isEnabled()))
            repeat(2) { goBack() }
            signOutFromWorkspace()
        }
    }

    private fun seed(
        email: String,
        password: String,
        ownerName: String,
        workspaceName: String,
        projectName: String,
        task: String,
    ) {
        val client = OkHttpClient()
        var accessToken: String? = null

        fun call(path: String, fields: JSONObject? = null, key: String? = null): JSONObject {
            val request = Request.Builder().url(BuildConfig.API_BASE_URL + path)
            accessToken?.let { request.header("Authorization", "Bearer $it") }
            key?.let { request.header("Idempotency-Key", it) }
            if (fields != null) request.post(fields.toString().toRequestBody("application/json".toMediaType()))
            return client.newCall(request.build()).execute().use { response ->
                check(response.isSuccessful) { "Search fixture request to $path returned HTTP ${response.code}" }
                JSONObject(checkNotNull(response.body).string()).getJSONObject("data")
            }
        }

        val account = call("auth/register", JSONObject(mapOf("email" to email, "password" to password, "displayName" to ownerName)))
        val owner = account.getJSONObject("user").getString("id")
        accessToken = account.getString("accessToken")
        val workspace = call("workspaces", JSONObject(mapOf("name" to workspaceName))).getString("id")
        val project = call("workspaces/$workspace/projects", JSONObject(mapOf("name" to projectName))).getString("id")
        val columns = call("projects/$project/board").getJSONArray("columns")
        fun column(name: String) = (0 until columns.length()).asSequence()
            .map(columns::getJSONObject).first { it.getString("name") == name }.getString("id")

        val toDo = column("To Do")
        val inProgress = column("In Progress")
        fun add(title: String, column: String, priority: String, assignee: String? = owner, description: String? = null) {
            val fields = JSONObject(mapOf("title" to title, "columnId" to column, "priority" to priority))
            fields.put("assigneeId", assignee)
            fields.put("description", description)
            call("projects/$project/tasks", fields, UUID.randomUUID().toString())
        }

        add(task, toDo, "HIGH")
        add("Audit cột khác", inProgress, "HIGH")
        add("Audit ưu tiên khác", toDo, "LOW")
        add("Audit chưa giao", toDo, "HIGH", assignee = null)
        add("Triển khai bản thử", toDo, "HIGH", description = "Audit trong mô tả")
    }

    private fun enterKeyword(keyword: String) {
        onView(withId(R.id.search_keyword)).perform(replaceText(keyword), closeSoftKeyboard())
    }

    private fun awaitCount(count: Int) {
        awaitDisplayed(allOf(withId(R.id.search_result_count), withText("$count công việc")))
    }

    private fun goBack() = tap(withContentDescription("Quay lại"))

    private fun withAirplaneMode(checkOffline: () -> Unit) {
        assumeTrue(shell("settings get global airplane_mode_on") != "1")
        val wifiEnabled = shell("settings get global wifi_on").toIntOrNull()?.let { it > 0 } == true
        val dataEnabled = shell("settings get global mobile_data") != "0"
        try {
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable")
            shell("svc data disable")
            checkOffline()
        } finally {
            shell("cmd connectivity airplane-mode disable")
            shell(if (wifiEnabled) "svc wifi enable" else "svc wifi disable")
            shell(if (dataEnabled) "svc data enable" else "svc data disable")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    private fun tap(matcher: Matcher<View>) {
        val enabled = allOf(matcher, isEnabled())
        awaitDisplayed(enabled)
        onView(enabled).perform(click())
    }
}
