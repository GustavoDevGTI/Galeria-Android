package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MediaTextSearchInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aPhotoIsFoundByTheTextInsideItAndLeavesTheIndexWhenDeleted() {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(MediaTextSettings.ENABLED, true)
            .putBoolean("initial_all_files_requested", true).putBoolean("all_files_prompted", true).commit()
        val uri = insertTextPhoto("NOTA FISCAL GIRASSOL")
        try {
            MediaStoreRepository.invalidateCache()
            MediaStoreRepository.refreshMedia(context, force = true)
            // One reading round limited to the photo just created. The catalog may name it by the
            // files collection, so photos are compared by their canonical key.
            val key = MediaIdentityRules.canonicalKey(uri.toString())
            val dao = GalleryDatabase.get(context).mediaTextDao()
            MediaTextIndexer.indexPending(context, 60_000L, only = setOf(key)) { false }
            val read = GalleryCatalogStore.readMedia(context, false)
                .firstOrNull { MediaIdentityRules.canonicalKey(it.uri.toString()) == key }?.let { dao.text(it.uri.toString()) }
            assertTrue("A foto deve ser achada pelo texto; lido: $read", key in MediaTextSearch.matchingUris(context, "Girassol"))
            assertTrue("Todas as palavras precisam aparecer", key in MediaTextSearch.matchingUris(context, "nota girassol"))
            assertFalse(key in MediaTextSearch.matchingUris(context, "girassol orquidea"))
            assertTrue(MediaTextSearch.results(context, "girassol").any { MediaIdentityRules.canonicalKey(it.uri.toString()) == key })

            // From the main screen: the row under the search box opens the results.
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withHint(R.string.main_search_folders)).perform(replaceText("girassol"))
                onView(withTagValue(`is`("main_text_search"))).check(matches(isDisplayed()))
                onView(withText(context.getString(R.string.search_text_in_photos, "girassol"))).perform(click())
                waitFor {
                    onView(withHint(context.resources.getQuantityString(R.plurals.search_results_hint, 1, "girassol", 1)))
                        .check(matches(isDisplayed()))
                }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
            MediaStoreRepository.invalidateCache()
            MediaStoreRepository.refreshMedia(context, force = true)
            MediaTextIndexer.indexPending(context, 60_000L) { true }
            prefs.edit().putBoolean(MediaTextSettings.ENABLED, false).commit()
        }
        assertFalse("Foto apagada sai do índice",
            MediaIdentityRules.canonicalKey(uri.toString()) in MediaTextSearch.matchingUris(context, "girassol"))
    }

    private fun insertTextPhoto(text: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TextSearch-${System.nanoTime()}/")
            put(MediaStore.MediaColumns.DISPLAY_NAME, "documento.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(1400, 500, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, 40f, 270f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f })
            }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val future = System.currentTimeMillis() / 1000L + 100_000L
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_ADDED, future)
        }, null, null)
        return uri
    }

    private fun waitFor(check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (true) {
            try { check(); return } catch (error: Throwable) {
                if (SystemClock.elapsedRealtime() > deadline) throw error
                Thread.sleep(150L)
            }
        }
    }
}
