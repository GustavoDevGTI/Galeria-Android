package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class GalleryBrowseBarInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before fun avoidPermissionOnboarding() {
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("initial_all_files_requested", true).putBoolean("all_files_prompted", true).commit()
    }

    @Test fun mainBarHasFourEqualActionsAndOverflowKeepsTheRemainingFeatures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertBar(it.window.decorView) }
            onView(withTagValue(`is`("browse_sort"))).perform(click())
            onView(withText(R.string.action_sort_by)).inRoot(isDialog()).check(matches(isDisplayed()))
            pressBack()
            onView(withTagValue(`is`("browse_filter"))).perform(click())
            onView(withText(R.string.action_filter_media)).inRoot(isDialog()).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription(R.string.action_more_options)).perform(click())
            for (text in listOf(R.string.main_folder_organization, R.string.action_create_folder, R.string.action_refresh)) {
                onView(withText(text)).check(matches(isDisplayed()))
            }
            onView(withText(R.string.action_sort_by)).check(doesNotExist())
            onView(withText(R.string.main_folder_visibility)).check(doesNotExist())
            pressBack()
            onView(withTagValue(`is`("gallery_browse_bar"))).check(matches(isDisplayed()))
            scenario.onActivity { assertTrue(it.hasWindowFocus()); assertFalse(it.isFinishing); assertBar(it.window.decorView) }
            screenshot("browse-main-qa.png")
        }
    }

    @Test fun albumBarReturnsAfterSelectionAndHiddenPanelKeepsTheOriginalAlbumAndRevealSession() {
        val folder = "Pictures/BrowseBar-${System.nanoTime()}/"
        val uri = insertPhoto(folder)
        try {
            ActivityScenario.launch<AlbumMediaActivity>(Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", folder); putExtra("album_name", "Álbum de teste")
            }).use { scenario ->
                waitUntil { var count = 0; scenario.onActivity { count = adapter(it).getCount() }; count == 1 }
                scenario.onActivity { assertBar(it.window.decorView) }
                onView(withContentDescription("browse-photo.png")).perform(longClick())
                onView(withTagValue(`is`("gallery_browse_bar"))).check(matches(not(isDisplayed())))
                onView(withContentDescription(R.string.action_move)).check(matches(isDisplayed()))
                pressBack()
                onView(withTagValue(`is`("gallery_browse_bar"))).check(matches(isDisplayed()))
                TemporaryAlbumVisibility.toggle(folder)
                onView(withTagValue(`is`("browse_hidden"))).perform(click())
                onView(withText(R.string.main_folder_visibility)).inRoot(isDialog()).check(matches(isDisplayed()))
                assertTrue("Abrir Ocultos não pode encerrar a revelação existente", folder in TemporaryAlbumVisibility.activeKeys())
                pressBack()
                waitUntil {
                    var resumed = false
                    scenario.onActivity { resumed = it.hasWindowFocus() && !it.isFinishing }
                    resumed
                }
                scenario.onActivity {
                    assertEquals(folder, it.intent.getStringExtra("album_key"))
                    assertEquals(1, adapter(it).getCount())
                    assertBar(it.window.decorView)
                }
                assertTrue("Fechar o painel não equivale a fechar o app", folder in TemporaryAlbumVisibility.activeKeys())
                onView(withContentDescription(R.string.action_more_options)).perform(click())
                for (text in listOf(R.string.album_view_mode, R.string.album_random, R.string.album_choose_cover, R.string.album_cinema_mode)) {
                    onView(withText(text)).check(matches(isDisplayed()))
                }
                pressBack()
                screenshot("browse-album-qa.png")
            }
        } finally {
            TemporaryAlbumVisibility.hide(folder)
            context.contentResolver.delete(uri, null, null)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun settingsOpenedFromAlbumReturnsWithTheNewThemeAndSameItems() {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val oldTheme = if (prefs.contains("theme_color")) prefs.getInt("theme_color", Color.BLACK) else null
        val folder = "Pictures/BrowseTheme-${System.nanoTime()}/"
        val uri = insertPhoto(folder)
        try {
            ActivityScenario.launch<AlbumMediaActivity>(Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", folder); putExtra("album_name", "Tema")
            }).use { scenario ->
                waitUntil { var count = 0; scenario.onActivity { count = adapter(it).getCount() }; count == 1 }
                onView(withTagValue(`is`("browse_settings"))).perform(click())
                onView(withText(R.string.action_settings)).check(matches(isDisplayed()))
                prefs.edit().putInt("theme_color", if (Ui.darkMode(context)) Color.WHITE else Color.BLACK).commit()
                pressBack()
                onView(withTagValue(`is`("gallery_browse_bar"))).check(matches(isDisplayed()))
                scenario.onActivity {
                    assertEquals(Ui.surface(it), (it.window.decorView.findViewWithTag<View>("gallery_browse_bar").background as ColorDrawable).color)
                    assertEquals(Ui.bg(it), (it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).background as ColorDrawable).color)
                    assertEquals(1, adapter(it).getCount())
                }
            }
        } finally {
            prefs.edit().apply { if (oldTheme == null) remove("theme_color") else putInt("theme_color", oldTheme) }.commit()
            context.contentResolver.delete(uri, null, null)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun deleteStaysAtTheSameRightEdgeForPhotoVideoAndAdditionalPhotoActions() {
        val photo = File(context.cacheDir, "browse-delete-${System.nanoTime()}.png")
        val video = File(context.cacheDir, "browse-delete-${System.nanoTime()}.mp4")
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("DOCUMENTO GALERIA TESTE", 50f, 300f, Paint().apply { color = Color.BLACK; textSize = 60f }) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        instrumentation.context.assets.open("playback-sample.mp4").use { input -> video.outputStream().use(input::copyTo) }
        try {
            var photoInset = 0f
            for ((file, mime) in listOf(photo to "image/png", video to "video/mp4")) {
                ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                    putExtra("uri", Uri.fromFile(file).toString()); putExtra("name", file.name); putExtra("mime", mime)
                }).use { scenario ->
                    if (mime == "video/mp4") waitUntil {
                        var ready = false
                        scenario.onActivity { ready = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player?.playbackState == Player.STATE_READY }
                        ready
                    }
                    onView(withTagValue(`is`("viewer_delete"))).check(matches(isDisplayed()))
                    scenario.onActivity {
                        val button = it.window.decorView.findViewWithTag<View>("viewer_delete")
                        val parent = button.parent as ViewGroup
                        assertSame(button, parent.getChildAt(parent.childCount - 1))
                        val inset = parent.width - button.x - button.width / 2f
                        assertEquals(Ui.dp(it, 28).toFloat(), inset, 1f)
                        if (mime == "image/png") photoInset = inset else assertEquals(photoInset, inset, 1f)
                        assertNull("A barra de álbuns não deve ocupar o visualizador", it.window.decorView.findViewWithTag<View>("gallery_browse_bar"))
                    }
                    if (mime == "image/png") {
                        waitUntil { var visible = false; scenario.onActivity { visible = it.window.decorView.findViewWithTag<View>("ocr_text_action").isShown }; visible }
                        scenario.onActivity {
                            val button = it.window.decorView.findViewWithTag<View>("viewer_delete")
                            assertEquals(photoInset, (button.parent as View).width - button.x - button.width / 2f, 1f)
                        }
                        screenshot("browse-photo-delete-qa.png")
                    }
                }
            }
        } finally { photo.delete(); video.delete() }
    }

    private fun assertBar(root: View) {
        val bar = root.findViewWithTag<ViewGroup>("gallery_browse_bar")
        assertTrue(bar.isShown)
        val dock = bar.getChildAt(0) as ViewGroup
        assertEquals(4, dock.childCount)
        val tags = listOf("browse_sort", "browse_filter", "browse_hidden", "browse_settings")
        tags.forEachIndexed { index, tag ->
            val action = dock.getChildAt(index)
            assertEquals(tag, action.tag)
            assertTrue(action.isClickable && action.isFocusable)
            assertFalse(action.isSelected)
            assertEquals(dock.getChildAt(0).width.toFloat(), action.width.toFloat(), 1f)
        }
    }

    private fun adapter(activity: AlbumMediaActivity) = AlbumMediaActivity::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(activity) as MediaRecyclerAdapter
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(100) }
        assertTrue("Estado esperado não apareceu", predicate())
    }
    private fun insertPhoto(folder: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "browse-photo.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder); put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val image = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(170, 190, 180)) }
        context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // The window compositor may still be animating a dismissed popup after Espresso is idle.
        SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
