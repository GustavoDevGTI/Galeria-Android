package com.galeria.android

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class FolderRecoveryInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "Pictures/RecoveryTest-${System.nanoTime()}/"

    @After fun cleanUp() {
        SecurityStore(context).removeFolder(key)
        SecuritySession.lockAll()
    }

    @Test fun theSecretAnswerOpensTheFolderAndOffersANewPassword() {
        val store = SecurityStore(context)
        store.setFolderPassword(key, "1234")
        store.setRecovery(key, RecoveryRules.PRESET_PREFIX + "0", "Rex")
        SecuritySession.lockAll()
        val opened = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { SecurityGate.requireFolder(it, key, "Cofre") { opened.set(true) } }
            onView(withText(R.string.security_forgot_password)).inRoot(isDialog()).perform(click())
            // The ready question shows in the phone language.
            onView(withText(context.resources.getStringArray(R.array.security_recovery_questions)[0]))
                .inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withTagValue(`is`("recovery_answer"))).inRoot(isDialog()).perform(replaceText("pato"))
            onView(withText(R.string.security_unlock)).inRoot(isDialog()).perform(click())
            onView(withText(R.string.security_recovery_wrong)).inRoot(isDialog()).check(matches(isDisplayed()))
            assertFalse(opened.get())
            // Case, accents and spaces do not matter.
            onView(withTagValue(`is`("recovery_answer"))).inRoot(isDialog()).perform(replaceText("  REX "))
            onView(withText(R.string.security_unlock)).inRoot(isDialog()).perform(click())
            waitFor { assertTrue(opened.get()) }
            assertTrue(SecuritySession.isFolderUnlocked(key))
            // Then a new password, which replaces the old one.
            onView(withText(context.getString(R.string.security_recovery_new_password_title, "Cofre")))
                .inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withTagValue(`is`("password_field"))).inRoot(isDialog()).perform(replaceText("98765"))
            onView(withTagValue(`is`("password_confirm_field"))).inRoot(isDialog()).perform(replaceText("98765"))
            onView(withText(R.string.security_unlock)).inRoot(isDialog()).perform(click())
            waitFor { assertTrue(store.verifyFolderPassword(key, "98765")) }
            assertFalse(store.verifyFolderPassword(key, "1234"))
        }
    }

    @Test fun aFolderProtectedBeforeTheQuestionExistedAsksForItAfterTheRightPassword() {
        val store = SecurityStore(context)
        store.setFolderPassword(key, "1234")
        SecuritySession.lockAll()
        val opened = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { SecurityGate.requireFolder(it, key, "Antiga") { opened.set(true) } }
            onView(withTagValue(`is`("password_field"))).inRoot(isDialog()).perform(replaceText("1234"))
            onView(withText(R.string.security_unlock)).inRoot(isDialog()).perform(click())
            waitFor { assertTrue(opened.get()) }
            onView(withText(context.getString(R.string.security_recovery_add_title, "Antiga")))
                .inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withTagValue(`is`("recovery_question_picker"))).inRoot(isDialog()).perform(click())
            onView(withText(R.string.security_recovery_custom)).inRoot(isDialog()).perform(click())
            onView(withTagValue(`is`("recovery_custom_question"))).inRoot(isDialog()).perform(replaceText("Apelido do avô?"))
            onView(withTagValue(`is`("recovery_answer"))).inRoot(isDialog()).perform(replaceText("Zé"))
            onView(withText(R.string.security_recovery_save)).inRoot(isDialog()).perform(click())
            waitFor { assertTrue(store.hasRecovery(key)) }
            assertTrue(store.verifyRecovery(key, "ze"))
        }
    }

    private fun waitFor(check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            try { check(); return } catch (error: Throwable) {
                if (SystemClock.elapsedRealtime() > deadline) throw error
                Thread.sleep(100L)
            }
        }
    }
}
