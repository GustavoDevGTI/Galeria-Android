package com.galeria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AlbumFastScrollInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun draggingFastScrollReachesTheEndOfALargeAlbum() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        val dao = GalleryDatabase.get(context).galleryDao()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val catalogPrefs = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
        val suffix = System.currentTimeMillis()
        val albumKey = "Pictures/GaleriaFastScrollTest-$suffix/"
        val optionSuffix = albumKey.hashCode()
        val originalMedia = io { dao.media(VISIBLE_SCOPE) }
        val originalState = io { dao.state(VISIBLE_SCOPE) }
        val originalColumns = prefs.getInt(PREF_GRID_COLUMNS, 0)
        val hadColumns = prefs.contains(PREF_GRID_COLUMNS)
        val originalMediaStoreVersion = catalogPrefs.getString(PREF_MEDIA_STORE_VERSION_VISIBLE, null)
        val originalGeneration = catalogPrefs.getString("media_store_generation_visible", null)
        val originalModel = catalogPrefs.getInt("catalog_model_version_visible", 0)
        val visibleWasDirty = GalleryCatalogStore.isCatalogDirty(context, false)
        val completeWasDirty = GalleryCatalogStore.isCatalogDirty(context, true)
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)

        try {
            // Drain any already-running scan before installing the large cached
            // fixture. This case exercises grid/scrolling, not real-device indexing.
            MediaStoreRepository.refreshMedia(context, force = true)
            awaitMediaStoreSettled(context)
            io {
                dao.replaceMedia(
                    VISIBLE_SCOPE,
                    List(TOTAL_MEDIA) { index -> media(albumKey, index) },
                    CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), allFilesAccess)
                )
            }
            GalleryCatalogStore.clearCatalogDirty(context, false)
            catalogPrefs.edit()
                .putInt("catalog_model_version_visible", 3)
                .putString("media_store_generation_visible", GalleryCatalogStore.mediaStoreChangeToken(context))
                .putString(
                    PREF_MEDIA_STORE_VERSION_VISIBLE,
                    MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
                )
                .commit()
            prefs.edit()
                .putInt(PREF_GRID_COLUMNS, GRID_COLUMNS)
                .putBoolean("album_list_mode_$optionSuffix", false)
                .putString("album_group_mode_$optionSuffix", "none")
                .commit()
            MediaStoreRepository.invalidateCache()
            assertTrue("O catálogo sintético deve estar válido antes de abrir a grade",
                io { GalleryCatalogStore.hasFreshCatalog(context, false, allFilesAccess, 180_000L) })

            val intent = Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", albumKey)
                putExtra("album_name", "Álbum com fast scroll")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<AlbumMediaActivity>(intent).use { scenario ->
                waitUntilDisplayed(FAST_SCROLL_DESCRIPTION, scenario)
                onView(allOf(withTagValue(equalTo("media_overlay_name")), withText(mediaName(0))))
                    .check(matches(isDisplayed()))
                onView(allOf(withTagValue(equalTo("media_overlay_duration")), withText("2:05")))
                    .check(matches(isDisplayed()))
                onView(allOf(withTagValue(equalTo("media_overlay_duration")), withText("–:–")))
                    .check(doesNotExist())
                val movedBeforeRelease = AtomicBoolean(false)
                onView(withContentDescription(FAST_SCROLL_DESCRIPTION))
                    .check(matches(isDisplayed()))
                    .perform(slowDragToMiddle(movedBeforeRelease))
                assertTrue("A grade ficou congelada durante o arrasto lento.", movedBeforeRelease.get())
                onView(withContentDescription(FAST_SCROLL_DESCRIPTION))
                    .perform(
                        GeneralSwipeAction(
                            Swipe.FAST,
                            GeneralLocation.TOP_CENTER,
                            GeneralLocation.BOTTOM_CENTER,
                            Press.FINGER
                        )
                    )
                waitUntilAlbumEnd(scenario)

                scenario.onActivity { activity ->
                    val recycler = findRecyclerView(activity.findViewById(android.R.id.content))
                    assertEquals(TOTAL_MEDIA, recycler?.adapter?.itemCount)
                    assertFalse((recycler?.adapter as MediaRecyclerAdapter).isPagingMode())
                    val emptyCells = (0 until recycler.childCount).count { index ->
                        recycler.getChildAt(index).contentDescription.isNullOrEmpty()
                    }
                    assertEquals("O fast scroll deixou células vazias na região final.", 0, emptyCells)
                    val thumbnail = findViewOfType(recycler.getChildAt(0), SquareFrameLayout::class.java)
                    assertTrue(requireNotNull(thumbnail).clipToOutline)
                    val image = findViewOfType(recycler.getChildAt(0), ImageView::class.java)
                    assertNotNull(image?.background)
                    val metadataRow = recycler.getChildAt(0).findViewWithTag<View>("media_metadata_row")
                    assertNotNull(metadataRow)
                    assertNull("A faixa preta ainda está atrás do nome da mídia.", metadataRow.background)
                }
            }
        } finally {
            catalogPrefs.edit().apply {
                putInt("catalog_model_version_visible", originalModel)
                if (originalGeneration == null) remove("media_store_generation_visible")
                else putString("media_store_generation_visible", originalGeneration)
            }.commit()
            io {
                dao.replaceMedia(
                    VISIBLE_SCOPE,
                    originalMedia,
                    originalState ?: CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), allFilesAccess)
                )
            }
            val editor = prefs.edit()
                .remove("album_list_mode_$optionSuffix")
                .remove("album_group_mode_$optionSuffix")
            if (hadColumns) editor.putInt(PREF_GRID_COLUMNS, originalColumns) else editor.remove(PREF_GRID_COLUMNS)
            editor.commit()
            if (originalMediaStoreVersion == null) {
                catalogPrefs.edit().remove(PREF_MEDIA_STORE_VERSION_VISIBLE).commit()
            } else {
                catalogPrefs.edit().putString(PREF_MEDIA_STORE_VERSION_VISIBLE, originalMediaStoreVersion).commit()
            }
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.clearCatalogDirty(context)
            if (visibleWasDirty || completeWasDirty) {
                GalleryCatalogStore.markCatalogDirty(context)
                if (!visibleWasDirty) GalleryCatalogStore.clearCatalogDirty(context, false)
                if (!completeWasDirty) GalleryCatalogStore.clearCatalogDirty(context, true)
            }
        }
    }

    private fun media(albumKey: String, index: Int) = CachedMediaEntity(
        scope = VISIBLE_SCOPE,
        uri = "content://album-fast-scroll/$index",
        mediaId = index.toLong(),
        name = mediaName(index),
        mimeType = if (index <= 1) "video/mp4" else "image/jpeg",
        dateAdded = (TOTAL_MEDIA - index).toLong(),
        size = 100L,
        relativePath = albumKey,
        albumKey = albumKey,
        albumName = "Álbum com fast scroll",
        duration = if (index == 0) 125_000L else 0L
    )

    private fun mediaName(index: Int): String = "midia-${index.toString().padStart(3, '0')}.jpg"

    private fun awaitMediaStoreSettled(context: Context) {
        // Previous real-media fixtures may still deliver asynchronous MediaStore
        // notifications. Do not stamp the synthetic catalog fresh until those
        // changes settle; otherwise the app correctly replaces it with real rows.
        val lastChange = AtomicLong(SystemClock.elapsedRealtime())
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { lastChange.set(SystemClock.elapsedRealtime()) }
        }
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        try {
            val deadline = SystemClock.elapsedRealtime() + 5000
            var token = GalleryCatalogStore.mediaStoreChangeToken(context)
            while (SystemClock.elapsedRealtime() < deadline) {
                val current = GalleryCatalogStore.mediaStoreChangeToken(context)
                if (current != token) { token = current; lastChange.set(SystemClock.elapsedRealtime()) }
                if (SystemClock.elapsedRealtime() - lastChange.get() >= 750) return
                Thread.sleep(50)
            }
            throw AssertionError("MediaStore não estabilizou antes da instalação do catálogo sintético")
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    private fun viewIsDisplayed(description: String): Boolean = try {
        onView(withContentDescription(description)).check(matches(isDisplayed()))
        true
    } catch (_: Throwable) {
        false
    }

    private fun waitUntilDisplayed(description: String, scenario: ActivityScenario<AlbumMediaActivity>) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (viewIsDisplayed(description)) return
            Thread.sleep(100L)
        }
        var diagnostic = ""
        scenario.onActivity { activity ->
            val recycler = findRecyclerView(activity.findViewById(android.R.id.content))
            val layout = recycler?.layoutManager as? GridLayoutManager
            val scroller = findViewOfType(activity.findViewById(android.R.id.content), AlbumFastScroller::class.java)
            diagnostic = "items=${recycler?.adapter?.itemCount}, first=${layout?.findFirstVisibleItemPosition()}, " +
                "last=${layout?.findLastVisibleItemPosition()}, height=${scroller?.height}, visibility=${scroller?.visibility}"
        }
        throw AssertionError("Elemento não exibido: $description ($diagnostic)")
    }

    private fun waitUntilAlbumEnd(scenario: ActivityScenario<AlbumMediaActivity>) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        var lastVisible = RecyclerView.NO_POSITION
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val recycler = findRecyclerView(activity.findViewById(android.R.id.content))
                lastVisible = (recycler?.layoutManager as? GridLayoutManager)
                    ?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
            }
            if (lastVisible == TOTAL_MEDIA - 1) return
            Thread.sleep(100L)
        }
        throw AssertionError("O fast scroll parou na posição $lastVisible de ${TOTAL_MEDIA - 1}.")
    }

    private fun slowDragToMiddle(movedBeforeRelease: AtomicBoolean): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(AlbumFastScroller::class.java)

        override fun getDescription(): String = "arrastar lentamente o fast scroll sem soltar"

        override fun perform(uiController: UiController, view: View) {
            val x = view.width / 2f
            val startY = view.height * 0.08f
            val endY = view.height * 0.55f
            val downTime = SystemClock.uptimeMillis()
            var event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            for (step in 1..8) {
                uiController.loopMainThreadForAtLeast(85L)
                val y = startY + (endY - startY) * step / 8f
                val eventTime = SystemClock.uptimeMillis()
                event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            uiController.loopMainThreadForAtLeast(85L)
            val recycler = findRecyclerView(view.rootView)
            val firstVisible = (recycler?.layoutManager as? GridLayoutManager)
                ?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            movedBeforeRelease.set(firstVisible > 0)
            val upTime = SystemClock.uptimeMillis()
            event = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, endY, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun <T : View> findViewOfType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view !is android.view.ViewGroup) return null
        for (index in 0 until view.childCount) {
            findViewOfType(view.getChildAt(index), type)?.let { return it }
        }
        return null
    }

    private fun findRecyclerView(view: android.view.View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view !is android.view.ViewGroup) return null
        for (index in 0 until view.childCount) {
            findRecyclerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val FAST_SCROLL_DESCRIPTION = "Rolagem rápida do álbum"
        const val VISIBLE_SCOPE = "visible"
        const val TOTAL_MEDIA = 260
        const val GRID_COLUMNS = 4
        const val LOAD_TIMEOUT_MS = 15_000L
        const val PREF_GRID_COLUMNS = "media_grid_columns"
        const val CATALOG_META_PREFS = "gallery_catalog_meta"
        const val PREF_MEDIA_STORE_VERSION_VISIBLE = "media_store_version_visible"
    }
}
