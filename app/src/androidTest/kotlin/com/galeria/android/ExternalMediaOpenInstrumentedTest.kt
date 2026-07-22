package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ExternalMediaOpenInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun appHandlesImageAndVideoViewIntentsAndOpensReceivedUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertGalleryIsViewHandler(context, "image/jpeg", "foto.jpg")
        assertGalleryIsViewHandler(context, "image/gif", "animacao.gif")
        assertGalleryIsViewHandler(context, "video/mp4", "filme.mp4")

        val name = "abertura-externa-${System.currentTimeMillis()}.png"
        val uri = insertImage(context, name)
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClass(context, DetailActivity::class.java)
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<DetailActivity>(intent).use {
                waitUntilDisplayed(name)
                onView(withText(name)).check(matches(isDisplayed()))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun assertGalleryIsViewHandler(context: Context, mimeType: String, fileName: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.galeria.test/$fileName"), mimeType)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "Galeria ausente para $mimeType",
            handlers.any { it.activityInfo.packageName == context.packageName && it.activityInfo.name == DetailActivity::class.java.name }
        )
    }

    private fun insertImage(context: Context, name: String): Uri {
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GaleriaExternalOpenTest/")
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

    private fun waitUntilDisplayed(text: String) {
        val deadline = System.currentTimeMillis() + 10_000L
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

    private companion object {
        val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
