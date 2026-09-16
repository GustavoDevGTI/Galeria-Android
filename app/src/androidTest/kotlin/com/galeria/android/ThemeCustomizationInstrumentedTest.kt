package com.galeria.android

import android.content.Context
import android.graphics.Color
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeCustomizationInstrumentedTest {
    @Test
    fun systemBarIconsKeepContrastForLightAndDarkThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hadTheme = prefs.contains("theme_color")
        val originalTheme = prefs.getInt("theme_color", Color.rgb(18, 18, 18))
        try {
            prefs.edit().putInt("theme_color", Color.rgb(232, 214, 166)).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    assertTrue(controller.isAppearanceLightStatusBars)
                    assertTrue(controller.isAppearanceLightNavigationBars)
                }
            }

            prefs.edit().putInt("theme_color", Color.rgb(24, 28, 32)).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    assertFalse(controller.isAppearanceLightNavigationBars)
                }
            }
        } finally {
            Ui.clearThemePreview()
            prefs.edit().apply {
                if (hadTheme) putInt("theme_color", originalTheme) else remove("theme_color")
            }.commit()
        }
    }

    @Test
    fun colorWheelShowsLivePreviewAndCancelKeepsSavedTheme() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hadTheme = prefs.contains("theme_color")
        val originalTheme = prefs.getInt("theme_color", Color.rgb(18, 18, 18))
        val savedTheme = Color.rgb(70, 80, 86)
        prefs.edit().putInt("theme_color", savedTheme).commit()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                onView(withText(context.getString(R.string.settings_theme_color))).perform(click())
                onView(withTagValue(equalTo(ThemeColorWheelView.TAG))).check(matches(isDisplayed()))
                    .perform(selectWheelColor(Color.RED))

                scenario.onActivity { activity ->
                    val hsv = FloatArray(3)
                    Color.colorToHSV(Ui.themeSeed(activity), hsv)
                    assertTrue(hsv[1] <= ThemeColorWheelView.MAX_SATURATION + 0.001f)
                    assertTrue(Ui.themeSeed(activity) != Ui.normalizeThemeSeed(savedTheme))
                }

                onView(withText(context.getString(R.string.action_cancel))).perform(click())
                scenario.onActivity { activity ->
                    assertEquals(Ui.normalizeThemeSeed(savedTheme), Ui.themeSeed(activity))
                    val topInset = WindowInsetsCompat.toWindowInsetsCompat(
                        activity.window.decorView.rootWindowInsets,
                        activity.window.decorView
                    ).getInsets(WindowInsetsCompat.Type.statusBars()).top
                    val settingsTitle = findText(activity.window.decorView, context.getString(R.string.action_settings))
                    val location = IntArray(2)
                    settingsTitle.getLocationOnScreen(location)
                    assertTrue(location[1] >= topInset)
                }
            }
        } finally {
            Ui.clearThemePreview()
            prefs.edit().apply {
                if (hadTheme) putInt("theme_color", originalTheme) else remove("theme_color")
            }.commit()
        }
    }

    private fun findText(root: android.view.View, text: String): android.widget.TextView {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                runCatching { return findText(root.getChildAt(index), text) }
            }
        }
        error("Texto não encontrado: $text")
    }

    private fun selectWheelColor(color: Int): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<android.view.View> =
            isAssignableFrom(ThemeColorWheelView::class.java)

        override fun getDescription(): String = "selecionar uma cor no círculo"

        override fun perform(uiController: UiController, view: android.view.View) {
            (view as ThemeColorWheelView).setColor(color, notify = true)
            uiController.loopMainThreadUntilIdle()
        }
    }
}
