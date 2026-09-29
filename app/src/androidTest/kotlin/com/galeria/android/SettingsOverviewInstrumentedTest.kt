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
}
