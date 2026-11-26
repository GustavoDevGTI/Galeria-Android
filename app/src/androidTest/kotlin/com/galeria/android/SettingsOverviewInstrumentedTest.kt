package com.galeria.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsOverviewInstrumentedTest {
    @Test fun advancedSettingsRemainAvailableWithoutCrowdingInitialScreen() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.settings_section_videos))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withText("Opções avançadas  ▾")).perform(scrollTo(), click())
            onView(withText(R.string.settings_section_videos))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withText(R.string.settings_autoplay_videos))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        }
    }

    @Test fun involvedOptionsExplainThemselvesAndSwitchesShowTheirStateClearly() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withText("Opções avançadas  ▾")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                // Every option with details has its "i"; the switches keep a solid track with contrast on the row.
                SettingsActivity.SETTING_DETAILS.keys.forEach { key ->
                    org.junit.Assert.assertNotNull("Falta o i de $key", root.findViewWithTag<android.view.View>("settings_info_$key"))
                }
                val surface = Ui.surface(activity)
                fun all(view: android.view.View): Sequence<android.view.View> = sequence {
                    yield(view)
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
                }
                val switches = all(root).filterIsInstance<android.widget.Switch>().toList()
                org.junit.Assert.assertTrue(switches.isNotEmpty())
                switches.forEach { toggle ->
                    val track = toggle.trackDrawable as android.graphics.drawable.GradientDrawable
                    val colors = track.color!!
                    val on = colors.getColorForState(intArrayOf(android.R.attr.state_checked), 0)
                    val off = colors.getColorForState(intArrayOf(-android.R.attr.state_checked), 0)
                    org.junit.Assert.assertTrue("Trilha ligada sem contraste", ThemeToneRules.contrastRatio(on, surface) >= ThemeToneRules.MIN_SWITCH_CONTRAST)
                    org.junit.Assert.assertTrue("Ligado e desligado parecidos demais", ThemeToneRules.contrastRatio(on, off) >= 1.4)
                }
            }
            // The "i" opens the details of the option.
            onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.`is`("settings_info_${GalleryPreferences.AUTO_PICTURE_IN_PICTURE}")))
                .perform(scrollTo(), click())
            onView(withText(R.string.settings_details_auto_picture_in_picture))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        }
    }
}
