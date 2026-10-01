package com.galeria.android

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionClickFeedbackInstrumentedTest {
    @Test fun repeatedClicksStayImmediateAndRestoreAppearanceWithoutClearingSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            lateinit var action: View
            lateinit var dock: LinearLayout
            var clicks = 0
            scenario.onActivity { activity ->
                dock = LinearLayout(activity)
                action = EditorUi.button(activity, R.drawable.ic_brush, "Pincel") { clicks++ }.apply {
                    isSelected = true
                    alpha = 0.82f
                    scaleX = 0.9f
                    scaleY = 0.9f
                }
                dock.addView(action, LinearLayout.LayoutParams(120, 120))
                activity.setContentView(dock)
            }
            val background = action.background
            repeat(8) { clickIndex ->
                scenario.onActivity {
                    assertTrue(action.performClick())
                    assertEquals(clickIndex + 1, clicks)
                    assertTrue("Cliques rápidos não podem encolher cumulativamente o botão", action.scaleX >= 0.9f * 0.94f - 0.001f)
                    assertTrue(action.isSelected)
                    assertFalse(action.isPressed)
                    assertEquals(0.82f, action.alpha, 0.001f)
                    assertSame(background, action.background)
                }
                Thread.sleep(25)
            }
            Thread.sleep(250)
            val deadline = System.currentTimeMillis() + 5000
            var restored = false
            while (!restored && System.currentTimeMillis() < deadline) {
                scenario.onActivity { restored = kotlin.math.abs(action.scaleX - 0.9f) < 0.001f && kotlin.math.abs(action.scaleY - 0.9f) < 0.001f }
                if (!restored) Thread.sleep(25)
            }
            assertTrue("O feedback não pode ficar permanentemente pressionado", restored)
            scenario.onActivity { assertEquals(8, clicks); assertTrue(action.isSelected) }
        }
    }

    @Test fun sharedFactoriesUseTheSameFeedbackAndDetachingRestoresScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val dock = LinearLayout(activity)
                var clicks = 0
                val controls = listOf(
                    Ui.actionIconButton(activity, R.drawable.ic_movie, Color.WHITE).apply { setOnClickListener { clicks++ } },
                    EditorUi.button(activity, R.drawable.ic_rotate, "Girar") { clicks++ },
                    Ui.selectionAction(activity, R.drawable.ic_heart, "Favoritar") { clicks++ }
                )
                controls.forEach { dock.addView(it, LinearLayout.LayoutParams(120, 120)) }
                activity.setContentView(dock)
                controls.forEach {
                    assertTrue(it is ClickFeedbackImageButton || it is ClickFeedbackActionLayout)
                    it.performClick()
                    dock.removeView(it)
                    assertEquals(1f, it.scaleX, 0.001f)
                    assertEquals(1f, it.scaleY, 0.001f)
                    assertFalse(it.isSelected)
                    assertFalse(it.isPressed)
                }
                assertEquals(3, clicks)
            }
        }
    }
}
