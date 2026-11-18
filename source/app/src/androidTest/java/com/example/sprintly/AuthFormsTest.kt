package com.example.sprintly

import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFormsTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun waitForLogin() {
        awaitLogin()
    }

    @Test
    fun registrationBackReturnsToLogin() {
        onView(withText("Đăng ký")).perform(scrollTo(), click())
        onView(withResourceName("confirm_input")).perform(scrollTo()).check(matches(isDisplayed()))

        pressBack()

        awaitLogin()
        onView(withText("Đăng ký")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun loginRejectsMalformedEmailInline() {
        onView(withResourceName("email_input")).perform(scrollTo(), replaceText("an..ngoc@example.org"))
        onView(withResourceName("password_input")).perform(
            scrollTo(),
            replaceText("ValidPass123!"),
            closeSoftKeyboard(),
        )
        onView(withResourceName("submit_button")).perform(scrollTo(), click())

        onView(withText("Nhập địa chỉ email hợp lệ.")).perform(scrollTo())
            .check(matches(isDisplayed()))
    }

    @Test
    fun registrationRejectsMismatchedPasswordsInline() {
        onView(withText("Đăng ký")).perform(scrollTo(), click())
        onView(withResourceName("display_name_input")).perform(scrollTo(), replaceText("Ngọc An"))
        onView(withResourceName("email_input")).perform(scrollTo(), replaceText("an@example.org"))
        onView(withResourceName("password_input")).perform(scrollTo(), replaceText("ValidPass123!"))
        onView(withResourceName("confirm_input")).perform(
            scrollTo(),
            replaceText("DifferentPass123!"),
            closeSoftKeyboard(),
        )
        onView(withResourceName("submit_button")).perform(scrollTo(), click())

        onView(withText("Hai mật khẩu chưa trùng khớp.")).perform(scrollTo())
            .check(matches(isDisplayed()))
    }

    @Test
    fun recreationPreservesEmailAndClearsPassword() {
        onView(withResourceName("email_input")).perform(scrollTo(), replaceText("an@example.org"))
        onView(withResourceName("password_input")).perform(
            scrollTo(),
            replaceText("ValidPass123!"),
            closeSoftKeyboard(),
        )

        activityRule.scenario.recreate()

        awaitLogin()
        onView(withResourceName("email_input")).check(matches(withText("an@example.org")))
        onView(withResourceName("password_input")).check(matches(withText("")))
    }
}

internal fun awaitLogin() {
    awaitDisplayed(allOf(withResourceName("auth_title"), withText("Đăng nhập")))
}

internal fun awaitDisplayed(matcher: Matcher<View>) {
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (SystemClock.elapsedRealtime() < deadline) {
        try {
            onView(matcher).check(matches(isDisplayed()))
            return
        } catch (_: NoMatchingViewException) {
            // The next destination or its result may still be loading.
        } catch (_: AssertionError) {
            // Wait for the destination to become visible after navigation.
        }
        SystemClock.sleep(100)
    }
    onView(matcher).check(matches(isDisplayed()))
}
