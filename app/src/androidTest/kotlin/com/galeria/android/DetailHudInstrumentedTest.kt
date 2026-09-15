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
import org.hamcrest.Matchers.not
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class DetailHudInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun singleTapHidesAndShowsViewerControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.currentTimeMillis()
        val name = "hud-$suffix.png"
        val uri = insertImage(context, name)

        try {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", uri.toString())
                putExtra("name", name)
                putExtra("mime", "image/png")
                putExtra("path", HUD_ALBUM_PATH)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ActivityScenario.launch<DetailActivity>(intent).use {
                waitForDisplayed(name)
                onView(withContentDescription("Visualizador de mídia")).perform(click())
                waitForHidden(name)
                onView(withContentDescription("Visualizador de mídia")).perform(click())
                waitForDisplayed(name)
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun insertImage(context: Context, name: String): Uri {
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, HUD_ALBUM_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        )
        assertNotNull(uri)
        val mediaUri = requireNotNull(uri)
        context.contentResolver.openOutputStream(mediaUri)?.use { it.write(ONE_PIXEL_PNG) }
        context.contentResolver.update(
            mediaUri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        return mediaUri
    }

    private fun waitForDisplayed(text: String) = waitForView {
        onView(withText(text)).check(matches(isDisplayed()))
    }

    private fun waitForHidden(text: String) = waitForView {
        onView(withText(text)).check(matches(not(isDisplayed())))
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
        throw AssertionError("Estado do HUD não foi exibido.", lastFailure)
    }

    private companion object {
        const val HUD_ALBUM_PATH = "Pictures/GaleriaHudTest/"
        val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
