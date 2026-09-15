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
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class DetailPlaybackInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun muteAndSpeedSurviveActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertEmptyVideo(context)
        val intent = Intent(context, DetailActivity::class.java).apply {
            putExtra("uri", uri.toString())
            putExtra("name", "estado-video.mp4")
            putExtra("mime", "video/mp4")
            putExtra("path", "Pictures/GaleriaPlaybackTest/")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
                onView(withContentDescription("Desativar som")).perform(click())
                onView(withContentDescription("Ativar som")).check(matches(isDisplayed()))

                onView(withText("1x")).perform(click())
                onView(withText("1,5x")).perform(click())
                onView(withText("1,5x")).check(matches(isDisplayed()))

                scenario.recreate()

                onView(withContentDescription("Ativar som")).check(matches(isDisplayed()))
                onView(withText("1,5x")).check(matches(isDisplayed()))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun insertEmptyVideo(context: Context): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "playback-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/GaleriaPlaybackTest/")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        return requireNotNull(uri)
    }
}
