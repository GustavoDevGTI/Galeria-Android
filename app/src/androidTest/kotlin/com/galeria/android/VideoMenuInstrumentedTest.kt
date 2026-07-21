package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.hamcrest.Matchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class VideoMenuInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun videoMenuShowsCompatibleActionsAndTogglesLoop() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("loop_videos", false)
            .commit()
        val uri = insertEmptyVideo(context)

        try {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", uri.toString())
                putExtra("name", "menu-video.mp4")
                putExtra("mime", "video/mp4")
                putExtra("path", "Pictures/GaleriaVideoMenuTest/")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ActivityScenario.launch<DetailActivity>(intent).use {
                onView(withContentDescription("Mais opções")).perform(click())
                onView(withText(ViewerMenuRules.OPEN_WITH)).check(matches(isDisplayed()))
                onView(withText(ViewerMenuRules.INFORMATION)).check(matches(isDisplayed()))
                onView(withText(ViewerMenuRules.SET_AS)).check(doesNotExist())
                onView(withText(ViewerMenuRules.ENABLE_LOOP)).perform(click())

                onView(withContentDescription("Mais opções")).perform(click())
                onView(withText(ViewerMenuRules.DISABLE_LOOP)).check(matches(isDisplayed()))
                pressBack()

                onView(withContentDescription("Mais opções")).perform(click())
                onView(withText(ViewerMenuRules.INFORMATION)).perform(click())
                waitUntilDisplayed("Informações do vídeo")
                onView(withText(containsString("Formato: video/mp4"))).check(matches(isDisplayed()))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun insertEmptyVideo(context: Context): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "menu-video-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/GaleriaVideoMenuTest/")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        return requireNotNull(uri)
    }

    private fun waitUntilDisplayed(text: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Texto não exibido: $text", lastFailure)
    }
}
