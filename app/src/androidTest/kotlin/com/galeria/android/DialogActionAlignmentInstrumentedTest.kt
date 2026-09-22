package com.galeria.android

import android.view.Gravity
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DialogActionAlignmentInstrumentedTest {
    @Test
    fun firstAccessChoicesShareTheSameHeightAndActOnTap() {
        val standardChosen = AtomicBoolean(false)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Ui.showActionChoiceDialog(
                    activity,
                    "Acesso à galeria",
                    "Escolha como usar o aplicativo",
                    "Acesso padrão",
                    "Acesso completo",
                    onFirst = { standardChosen.set(true) },
                    onSecond = {}
                )
            }
            val firstLocation = IntArray(2)
            onView(withText("Acesso padrão")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                view.getLocationOnScreen(firstLocation)
            }
            onView(withText("Acesso completo")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                val secondLocation = IntArray(2)
                view.getLocationOnScreen(secondLocation)
                assertEquals(firstLocation[1], secondLocation[1])
            }
            onView(withText("OK")).inRoot(isDialog()).check(doesNotExist())
            onView(withText("Cancelar")).inRoot(isDialog()).check(doesNotExist())
            onView(withText("Acesso padrão")).inRoot(isDialog()).perform(click())
            assertTrue(standardChosen.get())
        }
    }

    @Test
    fun singleAndMultiChoicesApplyImmediatelyWithoutConfirmation() {
        val singleChoice = AtomicInteger(-1)
        val multiChoice = AtomicBoolean(false)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Ui.showChoiceDialog(activity, "Escolha única", arrayOf("Primeira", "Segunda"), 0) {
                    singleChoice.set(it)
                }
            }
            onView(withText("Segunda")).inRoot(isDialog()).perform(click())
            assertEquals(1, singleChoice.get())

            scenario.onActivity { activity ->
                Ui.showMultiChoiceDialog(
                    activity,
                    "Painel lateral",
                    arrayOf("Opção"),
                    booleanArrayOf(false)
                ) { multiChoice.set(it[0]) }
            }
            onView(withText("OK")).inRoot(isDialog()).check(doesNotExist())
            onView(withText("Cancelar")).inRoot(isDialog()).check(doesNotExist())
            onView(withText("Opção")).inRoot(isDialog()).perform(click())
            assertTrue(multiChoice.get())
        }
    }

    @Test
    fun confirmationActionInCenteredPanelRemainsRightAligned() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> Ui.showMessageDialog(activity, "Aviso", "Conteúdo", "OK") }
            assertActionGravity(Gravity.RIGHT)
        }
    }

    @Test
    fun confirmationChoicesShareTheSameHeight() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Ui.showConfirmationDialog(activity, "Confirmação", "Continuar?", "Confirmar") {}
            }
            val firstLocation = IntArray(2)
            onView(withText("Cancelar")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                view.getLocationOnScreen(firstLocation)
            }
            onView(withText("Confirmar")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                val secondLocation = IntArray(2)
                view.getLocationOnScreen(secondLocation)
                assertEquals(firstLocation[1], secondLocation[1])
            }
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
