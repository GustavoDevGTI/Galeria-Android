package com.galeria.android

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MainActivitySmokeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Before
    fun preventExternalSettingsRedirect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("gallery_albums", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("initial_all_files_requested", true)
            .putBoolean("all_files_prompted", true)
            .commit()
    }

    @Test
    fun mainScreenOpensWithSearchAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withHint("Pesquisar pastas")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun createFolderUsesInternalStorageSubmenus() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Mais opções")).perform(click())
            onView(withText("Criar nova pasta")).perform(clickClickableAncestor())
            waitForView {
                onView(withText("Armazenamento interno")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("Cartão SD")).inRoot(isDialog()).check(matches(isDisplayed()))
            }
            onView(withText("Armazenamento interno")).inRoot(isDialog()).perform(clickClickableAncestor())
            waitForView {
                onView(withText("Criar pasta aqui")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("Escolher armazenamento")).inRoot(isDialog()).check(matches(isDisplayed()))
            }
        }
    }

    private fun waitForView(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000L
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Submenu de criação de pasta não exibido.", lastFailure)
    }
}
