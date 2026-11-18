package com.example.sprintly

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
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
            onView(withResourceName("logout_button")).perform(scrollTo(), click())
            awaitLogin()

            enter("email_input", email)
            enter("password_input", "invalid-password")
            submit()
            awaitDisplayed(withText("Email hoặc mật khẩu chưa đúng."))

            enter("password_input", password)
            submit()
            awaitAccount(email)
            onView(withResourceName("logout_button")).perform(scrollTo(), click())
            awaitLogin()
        }
    }

    private fun awaitAccount(email: String) {
        awaitDisplayed(allOf(withResourceName("account_email"), withText(email)))
    }

    private fun enter(field: String, value: String) {
        onView(withResourceName(field)).perform(scrollTo(), replaceText(value))
    }

    private fun submit() {
        onView(withResourceName("submit_button")).perform(closeSoftKeyboard(), scrollTo(), click())
    }
}
