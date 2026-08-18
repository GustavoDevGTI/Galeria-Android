package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class DetailShuffleRotationInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun rotationKeepsCurrentMediaAndShuffleFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val suffix = System.currentTimeMillis().toString()
        val albumPath = "Pictures/GaleriaRotation-$suffix/"
        val firstName = "rotation-first-$suffix.png"
        val secondName = "rotation-second-$suffix.png"
        val firstUri = insertImage(context, albumPath, firstName)
        val secondUri = insertImage(context, albumPath, secondName)
        MediaStoreRepository.invalidateCache()

        try {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", firstUri.toString())
                putExtra("name", firstName)
                putExtra("mime", "image/png")
                putExtra("path", albumPath)
                putExtra("album_key", albumPath)
                putExtra("shuffle_mode", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
                waitUntilDisplayed(secondName)
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(600L)

                waitUntilDisplayed(secondName)
            }
        } finally {
            resolver.delete(firstUri, null, null)
            resolver.delete(secondUri, null, null)
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun insertImage(context: Context, albumPath: String, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, albumPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        val mediaUri = requireNotNull(uri)
        resolver.openOutputStream(mediaUri)?.use { it.write(ONE_PIXEL_PNG) }
        resolver.update(
            mediaUri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        return mediaUri
    }

    private fun waitUntilDisplayed(text: String) {
        val deadline = System.currentTimeMillis() + 10_000L
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(200L)
            }
        }
        throw AssertionError("A mídia seguinte não foi exibida no fluxo aleatório", lastFailure)
    }

    private companion object {
        val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
