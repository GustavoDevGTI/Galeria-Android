package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class AlbumReorderInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun holdingAndDraggingAnAlbumSavesACustomOrder() {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val saved = listOf(GalleryPreferences.SORT_MODE, GalleryPreferences.SORT_DESC, GalleryPreferences.ALBUM_CUSTOM_ORDER)
            .associateWith { prefs.all[it] }
        val stamp = System.nanoTime()
        val first = "Pictures/ReorderA-$stamp/"
        val second = "Pictures/ReorderB-$stamp/"
        val now = System.currentTimeMillis() / 1000L
        val created = listOf(insert(first, now + 200), insert(second, now + 100))
        prefs.edit().putString(GalleryPreferences.SORT_MODE, AlbumRules.SORT_MODIFIED)
            .putBoolean(GalleryPreferences.SORT_DESC, true).remove(GalleryPreferences.ALBUM_CUSTOM_ORDER)
            .putBoolean("initial_all_files_requested", true).putBoolean("all_files_prompted", true).commit()
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var main: MainActivity
                waitFor {
                    var both = false
                    scenario.onActivity { activity ->
                        main = activity
                        val keys = activity.adapter.allAlbumsSnapshot().map { it.key }
                        both = first in keys && second in keys
                        if (both) activity.albumGrid.scrollToPosition(keys.indexOf(first))
                    }
                    assertTrue("Os álbuns de teste ainda não apareceram", both)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onView(isRoot()).perform(dragAlbum(main, first, second))
                waitFor { assertEquals(AlbumRules.SORT_CUSTOM, prefs.getString(GalleryPreferences.SORT_MODE, null)) }
                val order = AlbumReorderRules.savedOrder(prefs.getString(GalleryPreferences.ALBUM_CUSTOM_ORDER, null))
                assertTrue("B deve vir antes de A: $order", order.indexOf(second) in 0 until order.indexOf(first))
                scenario.onActivity { activity ->
                    val keys = activity.adapter.allAlbumsSnapshot().map { it.key }
                    assertTrue(keys.indexOf(second) < keys.indexOf(first))
                }
            }
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            prefs.edit().apply {
                saved.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    /** Holds the [fromKey] album past the long-press timeout and drags it onto [toKey]. */
    private fun dragAlbum(activity: MainActivity, fromKey: String, toKey: String): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription(): String = "arrastar $fromKey sobre $toKey"
        override fun perform(uiController: UiController, root: View) {
            fun center(key: String): Pair<Float, Float> {
                val position = activity.adapter.allAlbumsSnapshot().indexOfFirst { it.key == key }
                val view = requireNotNull(activity.albumGrid.findViewHolderForAdapterPosition(position)?.itemView) { "Sem $key na tela" }
                val location = IntArray(2).also(view::getLocationOnScreen)
                return location[0] + view.width / 2f to location[1] + view.height / 3f
            }
            val (fromX, fromY) = center(fromKey)
            val (targetX, targetY) = center(toKey)
            // A finger passes a little beyond the target; RecyclerView swaps only once the edge crosses it.
            val toX = targetX + (targetX - fromX) * 0.3f
            val toY = targetY + (targetY - fromY) * 0.3f
            val down = SystemClock.uptimeMillis()
            var event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, fromY, 0)
            uiController.injectMotionEvent(event); event.recycle()
            uiController.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 300L)
            for (step in 1..20) {
                event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * step / 20f, fromY + (toY - fromY) * step / 20f, 0)
                uiController.injectMotionEvent(event); event.recycle()
                uiController.loopMainThreadForAtLeast(25L)
            }
            uiController.loopMainThreadForAtLeast(300L)
            event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, toX, toY, 0)
            uiController.injectMotionEvent(event); event.recycle()
            uiController.loopMainThreadForAtLeast(500L)
        }
    }

    private fun insert(path: String, added: Long): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.DISPLAY_NAME, "reorder-$added.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_ADDED, added)
            put(MediaStore.MediaColumns.DATE_MODIFIED, added)
        }, null, null)
        return uri
    }

    private fun waitFor(check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (true) {
            try { check(); return } catch (error: Throwable) {
                if (SystemClock.elapsedRealtime() > deadline) throw error
                Thread.sleep(150L)
            }
        }
    }
}
