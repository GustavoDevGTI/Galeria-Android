package com.galeria.android

import android.view.Gravity
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogActionAlignmentInstrumentedTest {
    @Test
    fun primaryActionFollowsPanelPlacement() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Ui.showMessageDialog(activity, "Painel central", "Conteúdo", "OK")
            }
            assertActionGravity(Gravity.RIGHT)
            onView(withText("OK")).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                Ui.showMultiChoiceDialog(
                    activity,
                    "Painel lateral",
                    arrayOf("Opção"),
                    booleanArrayOf(true)
                ) {}
            }
            assertActionGravity(Gravity.LEFT)
        }
    }

    private fun assertActionGravity(expected: Int) {
        onView(withText("OK"))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
            .check { view, exception ->
                if (exception != null) throw exception
                val actual = Gravity.getAbsoluteGravity(
                    (view as TextView).gravity,
                    view.layoutDirection
                ) and Gravity.HORIZONTAL_GRAVITY_MASK
                assertEquals(expected, actual)
            }
    }
}
