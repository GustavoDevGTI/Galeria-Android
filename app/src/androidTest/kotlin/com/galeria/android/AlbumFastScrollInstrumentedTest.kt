package com.galeria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)

        try {
            io {
                dao.replaceMedia(
                    VISIBLE_SCOPE,
                    List(TOTAL_MEDIA) { index -> media(albumKey, index) },
                    CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), allFilesAccess)
                )
            }
            catalogPrefs.edit()
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

            val intent = Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", albumKey)
                putExtra("album_name", "Álbum com fast scroll")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<AlbumMediaActivity>(intent).use { scenario ->
                waitUntilDisplayed(FAST_SCROLL_DESCRIPTION)
                onView(withContentDescription(FAST_SCROLL_DESCRIPTION))
                    .check(matches(isDisplayed()))
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
                }
            }
        } finally {
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
        }
    }

    private fun media(albumKey: String, index: Int) = CachedMediaEntity(
        scope = VISIBLE_SCOPE,
        uri = "content://album-fast-scroll/$index",
        mediaId = index.toLong(),
        name = mediaName(index),
        mimeType = "image/jpeg",
        dateAdded = (TOTAL_MEDIA - index).toLong(),
        size = 100L,
        relativePath = albumKey,
        albumKey = albumKey,
        albumName = "Álbum com fast scroll"
    )

    private fun mediaName(index: Int): String = "midia-${index.toString().padStart(3, '0')}.jpg"

    private fun viewIsDisplayed(description: String): Boolean = try {
        onView(withContentDescription(description)).check(matches(isDisplayed()))
        true
    } catch (_: Throwable) {
        false
    }

    private fun waitUntilDisplayed(description: String) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (viewIsDisplayed(description)) return
            Thread.sleep(100L)
        }
        throw AssertionError("Elemento não exibido: $description")
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
