package com.example.sprintly

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AuthApiFlowTest {
    @Test
    fun registersRestoresActivitySignsOutAndSignsBackIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sprintlyAuthE2e") == "true")
        val email = "android-${UUID.randomUUID()}@example.org"
        val password = "Sprintly-${UUID.randomUUID()}!"

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitLogin()
            onView(withText("Đăng ký")).perform(scrollTo(), click())
            enter("display_name_input", "Sprintly Android Test")
            enter("email_input", email)
            enter("password_input", password)
            enter("confirm_input", password)
            submit()
            awaitAccount(email)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitAccount(email)
            signOutFromProfile()
            awaitLogin()

            enter("email_input", email)
            enter("password_input", "invalid-password")
            submit()
            awaitDisplayed(withText("Email hoặc mật khẩu chưa đúng."))

            enter("password_input", password)
            submit()
            awaitAccount(email)
            signOutFromProfile()
            awaitLogin()
        }
    }

    private fun awaitAccount(email: String) {
        awaitDisplayed(withId(R.id.workspace_profile))
        onView(withId(R.id.workspace_profile)).perform(click())
        awaitDisplayed(allOf(withResourceName("account_email"), withText(email)))
    }

    private fun enter(field: String, value: String) {
        onView(withResourceName(field)).perform(scrollTo(), replaceText(value))
    }

    private fun submit() {
        onView(withResourceName("submit_button")).perform(closeSoftKeyboard(), scrollTo(), click())
    }
}

internal fun signOutFromWorkspace() {
    awaitDisplayed(withId(R.id.workspace_profile))
    onView(withId(R.id.workspace_profile)).perform(click())
    signOutFromProfile()
    awaitLogin()
}

private fun signOutFromProfile() {
    awaitDisplayed(withId(R.id.logout_button))
    onView(withId(R.id.logout_button)).perform(scrollTo(), click())
    awaitDisplayed(withText(R.string.auth_logout_confirm))
    onView(withId(android.R.id.button1)).perform(click())
}
