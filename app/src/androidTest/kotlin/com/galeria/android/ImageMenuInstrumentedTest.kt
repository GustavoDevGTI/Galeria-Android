package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ImageMenuInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun imageMenuShowsToolsRenamesAndStartsPresentation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.currentTimeMillis()
        val albumPath = "Pictures/GaleriaImageMenuTest-$suffix/"
        val firstUri = insertImage(context, albumPath, "menu-first-$suffix.png")
        val secondName = "menu-second-$suffix.png"
        val secondUri = insertImage(context, albumPath, secondName)

        try {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", firstUri.toString())
                putExtra("name", "menu-first-$suffix.png")
                putExtra("mime", "image/png")
                putExtra("path", albumPath)
                putExtra("album_key", albumPath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ActivityScenario.launch<DetailActivity>(intent).use {
                onView(withContentDescription("Mais opções")).perform(click())
                waitUntilDisplayed(ViewerMenuRules.RENAME)
                onView(withText(ViewerMenuRules.OPEN_WITH)).check(matches(isDisplayed()))
                onView(withText(ViewerMenuRules.INFORMATION)).check(matches(isDisplayed()))
                onView(withText(ViewerMenuRules.PRESENTATION)).check(matches(isDisplayed()))
                onView(withText(ViewerMenuRules.ENABLE_LOOP)).check(doesNotExist())
                onView(withText(ViewerMenuRules.SHOW_ON_MAP)).check(doesNotExist())

                onView(withText(ViewerMenuRules.INFORMATION)).perform(click())
                waitUntilDisplayedInDialog("Informações da imagem")
                onView(withText(containsString("Resolução: 1 × 1")))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
                pressBack()

                onView(withContentDescription("Mais opções")).perform(click())
                waitUntilDisplayed(ViewerMenuRules.RENAME)
                onView(withText(ViewerMenuRules.RENAME)).perform(click())
                onView(isAssignableFrom(EditText::class.java))
                    .perform(replaceText("imagem-renomeada"), closeSoftKeyboard())
                onView(allOf(withText("Renomear"), isClickable())).perform(click())
                waitUntilDisplayed("imagem-renomeada.png")

                Thread.sleep(700L)
                onView(withContentDescription("Mais opções")).perform(click())
                waitUntilDisplayed(ViewerMenuRules.PRESENTATION)
                onView(withText(ViewerMenuRules.PRESENTATION)).perform(click())
                Thread.sleep(4_700L)
                onView(withContentDescription("Visualizador de mídia")).perform(click())
                waitUntilDisplayed(secondName)
                onView(withText(secondName)).check(matches(isDisplayed()))
            }
        } finally {
            context.contentResolver.delete(firstUri, null, null)
            context.contentResolver.delete(secondUri, null, null)
        }
    }

    private fun insertImage(context: Context, albumPath: String, name: String): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, albumPath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        )
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
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Texto não exibido: $text", lastFailure)
    }

    private fun waitUntilDisplayedInDialog(text: String) {
        val deadline = System.currentTimeMillis() + 10_000L
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Texto não exibido no diálogo: $text", lastFailure)
    }

    private companion object {
        val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
