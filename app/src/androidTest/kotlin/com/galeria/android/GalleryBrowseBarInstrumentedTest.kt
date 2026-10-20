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
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
            onView(withTagValue(`is`("browse_hidden"))).perform(click())
            onView(withText(R.string.main_folder_visibility)).inRoot(isDialog()).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription(R.string.action_more_options)).perform(click())
            for (text in listOf(R.string.main_folder_organization, R.string.main_album_spacing, R.string.action_create_folder, R.string.action_refresh)) {
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

    @Test fun albumSpacingChangesThroughOverflowAndSurvivesReopeningWithoutChangingMediaSpacing() {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val oldSpacing = if (prefs.contains("album_spacing_dp")) prefs.getInt("album_spacing_dp", 8) else null
        val mediaSpacing = prefs.getInt("grid_spacing_global", 3)
        val folder = "Pictures/AlbumSpacing-${System.nanoTime()}/"
        val uri = insertPhoto(folder)
        prefs.edit().remove("album_spacing_dp").commit()
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context)
        fun grid(activity: MainActivity): RecyclerView = MainActivity::class.java.getDeclaredField("grid").apply {
            isAccessible = true
        }.get(activity) as RecyclerView
        fun assertSpacing(scenario: ActivityScenario<MainActivity>, expected: Int) {
            waitUntil {
                var ready = false
                scenario.onActivity { activity ->
                    val list = grid(activity)
                    ready = list.childCount > 0 && (0 until list.childCount).all {
                        list.getChildAt(it).paddingLeft == Ui.dp(activity, expected) &&
                            list.getChildAt(it).paddingTop == Ui.dp(activity, expected)
                    }
                }
                ready
            }
        }
        try {
            var reduced = 8
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertSpacing(scenario, 8)
                onView(withContentDescription(R.string.action_more_options)).perform(click())
                onView(withText(R.string.main_album_spacing)).perform(click())
                onView(withTagValue(`is`("album_spacing_slider"))).inRoot(isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.swipeRight())
                reduced = prefs.getInt("album_spacing_dp", 8)
                assertTrue("Arrastar para a direita deve diminuir o espaço", reduced < 8)
                assertSpacing(scenario, reduced)
                assertEquals(mediaSpacing, prefs.getInt("grid_spacing_global", 3))
                screenshot("album-spacing-compact-qa.png")
                pressBack()
                scenario.recreate()
                assertSpacing(scenario, reduced)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertSpacing(scenario, reduced)
                onView(withContentDescription(R.string.action_more_options)).perform(click())
                onView(withText(R.string.main_album_spacing)).perform(click())
                onView(withTagValue(`is`("album_spacing_slider"))).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    assertEquals(16 - reduced, (view as SeekBar).progress)
                }
                onView(withTagValue(`is`("album_spacing_slider"))).inRoot(isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.swipeLeft())
                val expanded = prefs.getInt("album_spacing_dp", 0)
                assertTrue("Também deve ser possível aumentar novamente", expanded > reduced)
                assertSpacing(scenario, expanded)
                pressBack()
                assertEquals(mediaSpacing, prefs.getInt("grid_spacing_global", 3))
            }
        } finally {
            prefs.edit().apply {
                if (oldSpacing == null) remove("album_spacing_dp") else putInt("album_spacing_dp", oldSpacing)
            }.commit()
            context.contentResolver.delete(uri, null, null)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun albumBarReturnsAfterSelectionAndShuffleKeepsTheOriginalAlbumAndRevealSession() {
        val folder = "Pictures/BrowseBar-${System.nanoTime()}/"
        val uri = insertPhoto(folder)
        val secondUri = insertPhoto(folder, "browse-second.png")
        try {
            ActivityScenario.launch<AlbumMediaActivity>(Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", folder); putExtra("album_name", "Álbum de teste")
            }).use { scenario ->
                waitUntil { var count = 0; scenario.onActivity { count = adapter(it).getCount() }; count == 2 }
                scenario.onActivity { assertBar(it.window.decorView, "browse_shuffle") }
                onView(withTagValue(`is`("browse_hidden"))).check(doesNotExist())
                onView(withContentDescription(R.string.action_random_playback)).check(matches(isDisplayed()))
                onView(withContentDescription("browse-photo.png")).perform(longClick())
                onView(withTagValue(`is`("gallery_browse_bar"))).check(matches(not(isDisplayed())))
                onView(withContentDescription(R.string.action_move)).check(matches(isDisplayed()))
                pressBack()
                onView(withTagValue(`is`("gallery_browse_bar"))).check(matches(isDisplayed()))
                TemporaryAlbumVisibility.toggle(folder)
                onView(withTagValue(`is`("browse_shuffle"))).perform(click())
                waitUntil {
                    var playing = false
                    instrumentation.runOnMainSync {
                        val viewer = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<DetailActivity>().singleOrNull()
                        playing = viewer != null
                        if (viewer != null) {
                            assertTrue(viewer.intent.getBooleanExtra("shuffle_mode", false))
                            assertEquals(folder, viewer.intent.getStringExtra("album_key"))
                            assertNull(viewer.window.decorView.findViewWithTag<View>("gallery_browse_bar"))
                        }
                    }
                    playing
                }
                assertTrue("Reproduzir não pode encerrar a revelação existente", folder in TemporaryAlbumVisibility.activeKeys())
                pressBack()
                waitUntil {
                    var resumed = false
                    scenario.onActivity { resumed = it.hasWindowFocus() && !it.isFinishing }
                    resumed
                }
                scenario.onActivity {
                    assertEquals(folder, it.intent.getStringExtra("album_key"))
                    assertEquals(2, adapter(it).getCount())
                    assertBar(it.window.decorView, "browse_shuffle")
                }
                assertTrue("Voltar do visualizador não equivale a fechar o app", folder in TemporaryAlbumVisibility.activeKeys())
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
            context.contentResolver.delete(secondUri, null, null)
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
                        assertEquals(parent.width / 10f, inset, 1f)
                        if (mime == "image/png") photoInset = inset else assertEquals(photoInset, inset, 1f)
                        val actions = (0 until parent.childCount).map(parent::getChildAt).filter { view -> view.visibility == View.VISIBLE }
                        val middle = if (mime == "image/png") listOf("ocr_text_action", "viewer_information")
                            else listOf("viewer_sound", "viewer_cinema_mode_button")
                        assertEquals(listOf("viewer_edit") + middle + listOf("viewer_share", "viewer_delete"), actions.map { view -> view.tag })
                        actions.forEachIndexed { index, view ->
                            assertEquals(parent.width / 5f, view.width.toFloat(), 1f)
                            assertEquals((index + 0.5f) * parent.width / 5f, view.x + view.width / 2f, 1f)
                            assertTrue(view is android.widget.ImageButton)
                            assertTrue(view.isClickable && view.isFocusable)
                        }
                        assertNull("A barra de álbuns não deve ocupar o visualizador", it.window.decorView.findViewWithTag<View>("gallery_browse_bar"))
                    }
                    if (mime == "image/png") {
                        waitUntil { var visible = false; scenario.onActivity { visible = it.window.decorView.findViewWithTag<View>("ocr_text_action").isShown }; visible }
                        scenario.onActivity {
                            val button = it.window.decorView.findViewWithTag<View>("viewer_delete")
                            assertEquals(photoInset, (button.parent as View).width - button.x - button.width / 2f, 1f)
                        }
                        screenshot("browse-photo-delete-qa.png")
                        onView(withTagValue(`is`("viewer_information"))).perform(click())
                        waitUntil {
                            runCatching {
                                onView(withText("Informações da imagem")).inRoot(isDialog()).check(matches(isDisplayed()))
                            }.isSuccess
                        }
                        onView(withText(photo.name)).inRoot(isDialog()).check(matches(isDisplayed()))
                        pressBack()
                    } else {
                        screenshot("browse-video-actions-qa.png")
                    }
                }
            }
        } finally { photo.delete(); video.delete() }
    }

    private fun assertBar(root: View, thirdAction: String = "browse_hidden") {
        val bar = root.findViewWithTag<ViewGroup>("gallery_browse_bar")
        assertTrue(bar.isShown)
        val dock = bar.getChildAt(0) as ViewGroup
        assertEquals(4, dock.childCount)
        val tags = listOf("browse_sort", "browse_filter", thirdAction, "browse_settings")
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
    private fun insertPhoto(folder: String, name: String = "browse-photo.png"): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
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
