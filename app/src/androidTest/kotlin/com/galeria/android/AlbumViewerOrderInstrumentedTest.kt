package com.galeria.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class AlbumViewerOrderInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private val path = "Pictures/ViewerOrder-${System.nanoTime()}/"
    private val uris = ArrayList<Uri>()
    private fun key(suffix: String) = AlbumCoverRules.optionKey(path, suffix)

    @Before fun prepare() {
        insert("Zeta.png", 100, 30)
        insert("Alpha.png", 300, 60)
        insert("Beta.png", 200, 90)
        MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
        val items = MediaStoreRepository.queryAlbumMedia(context, path, false)
        GalleryCatalogStore.saveCustomOrder(context, path, listOf("Beta.png", "Zeta.png", "Alpha.png").map { name -> items.first { it.name == name } })
        prefs.edit().putString(key("sort_mode"), MediaSortRules.SORT_CUSTOM).putBoolean(key("sort_desc"), true).commit()
    }

    @After fun cleanup() {
        uris.forEach { context.contentResolver.delete(it, null, null) }
        GalleryDatabase.get(context).galleryDao().deleteCustomOrder(path)
        prefs.edit().remove(key("sort_mode")).remove(key("sort_desc")).remove(key("group_mode")).commit()
        MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
    }

    @Test fun sortMenuChangesSwipeOrderInBothDirectionsAndSurvivesRecreation() {
        ActivityScenario.launch<AlbumMediaActivity>(Intent(context, AlbumMediaActivity::class.java).apply {
            putExtra("album_key", path); putExtra("album_name", "Ordem do visualizador")
        }).use { scenario ->
            awaitGrid(scenario, listOf("Beta.png", "Zeta.png", "Alpha.png"))
            chooseNameSort()
            awaitGrid(scenario, listOf("Alpha.png", "Beta.png", "Zeta.png"))
            onView(withContentDescription("Alpha.png")).perform(clickClickableAncestor())
            awaitViewer("Alpha.png", listOf("Alpha.png", "Beta.png", "Zeta.png"))
            swipe(true); awaitViewer("Beta.png")
            instrumentation.runOnMainSync { resumed(DetailActivity::class.java)?.recreate() }
            awaitViewer("Beta.png", listOf("Alpha.png", "Beta.png", "Zeta.png"))
            swipe(true); awaitViewer("Zeta.png")
            swipe(false); awaitViewer("Beta.png")
            pressBack()
            awaitGrid(scenario, listOf("Alpha.png", "Beta.png", "Zeta.png"))
            chooseNameSort()
            awaitGrid(scenario, listOf("Zeta.png", "Beta.png", "Alpha.png"))
            onView(withContentDescription("Zeta.png")).perform(clickClickableAncestor())
            awaitViewer("Zeta.png", listOf("Zeta.png", "Beta.png", "Alpha.png"))
            swipe(true); awaitViewer("Beta.png")
            swipe(true); awaitViewer("Alpha.png")
            pressBack()
        }
    }

    @Test fun queueUsesTheAlbumSortAndGroupingInsteadOfAlwaysApplyingSavedManualOrder() {
        val source = MediaStoreRepository.queryAlbumMedia(context, path, false)
        val saved = GalleryCatalogStore.migrateLegacyOrder(context, path)
        val current = source.first { it.name == "Beta.png" }
        val modes = listOf(MediaSortRules.SORT_CUSTOM, MediaSortRules.SORT_DATE, MediaSortRules.SORT_NAME,
            MediaSortRules.SORT_SIZE, MediaSortRules.SORT_DURATION, MediaSortRules.SORT_TYPE)
        for (mode in modes) for (descending in listOf(false, true)) for (group in listOf(AlbumMediaRules.GROUP_NONE, AlbumMediaRules.GROUP_DAY)) {
            prefs.edit().putString(key("sort_mode"), mode).putBoolean(key("sort_desc"), descending).putString(key("group_mode"), group).commit()
            val expected = AlbumMediaRules.prepare(source, AlbumMediaPreparationOptions(MediaFilterOptions(), group, mode, descending), saved)
            lateinit var controller: DetailMediaQueueController
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller = DetailMediaQueueController(context)
                controller.setInitial(current)
                controller.loadAlbum(path, false, current.uri) { ready.countDown() }
            }
            try {
                assertTrue("Fila carregou: $mode/$descending/$group", ready.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertEquals("Ordem: $mode/$descending/$group", expected.map { it.name }, controller.items.map { it.name })
                    assertEquals(current.name, controller.current().name)
                    val index = expected.indexOfFirst { it.name == current.name }
                    controller.advance(1)
                    assertEquals(expected[(index + 1) % expected.size].name, controller.current().name)
                    controller.advance(-1); assertEquals(current.name, controller.current().name)
                }
            } finally { instrumentation.runOnMainSync { controller.close() } }
        }
    }

    private fun chooseNameSort() {
        awaitAndroidInputReady()
        onView(withContentDescription(R.string.action_sort_by)).perform(clickClickableAncestor())
        onView(withText("Nome alfabético")).inRoot(isDialog()).perform(clickClickableAncestor())
    }
    private fun swipe(forward: Boolean) {
        awaitAndroidInputReady()
        onView(withContentDescription("Visualizador de mídia")).perform(if (forward) swipeLeft() else swipeRight())
    }
    private fun awaitGrid(scenario: ActivityScenario<AlbumMediaActivity>, expected: List<String>) = await {
        var matches = false
        scenario.onActivity {
            val adapter = AlbumMediaActivity::class.java.getDeclaredField("adapter").apply { isAccessible = true }.get(it) as MediaRecyclerAdapter
            matches = it.hasWindowFocus() && adapter.currentOrder().map { item -> item.name } == expected
        }
        matches
    }
    private fun awaitViewer(name: String, order: List<String>? = null) = await {
        var matches = false
        instrumentation.runOnMainSync {
            val activity = resumed(DetailActivity::class.java)
            if (activity != null && activity.hasWindowFocus()) {
                val queue = DetailActivity::class.java.getDeclaredField("queueController").apply { isAccessible = true }.get(activity) as DetailMediaQueueController
                val transition = DetailActivity::class.java.getDeclaredField("mediaTransitionController").apply { isAccessible = true }.get(activity) as MediaTransitionController
                val title = DetailActivity::class.java.getDeclaredField("title").apply { isAccessible = true }.get(activity) as android.widget.TextView
                matches = !transition.isBusy && title.text.toString() == name && queue.items.size == 3 && queue.current().name == name &&
                    (order == null || queue.items.map { it.name } == order)
            }
        }
        matches
    }
    private fun <T : Activity> resumed(type: Class<T>): T? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }?.let(type::cast)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(70) }
        assertTrue("A ordem esperada não apareceu", condition())
    }
    private fun insert(name: String, date: Long, size: Int) {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        uris.add(uri)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0); put(MediaStore.MediaColumns.DATE_ADDED, date) }, null, null)
    }
}
