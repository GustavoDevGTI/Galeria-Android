package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AlbumCoverInstrumentedTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)

    @Test
    fun automaticCoverFollowsAlbumOrderAndManualCoverWinsOnlyWhilePresent() {
        val folder = "Pictures/Album/"
        fun media(name: String, date: Long) = MediaItem(
            date, Uri.parse("content://media/external/images/media/$date"), name,
            "image/jpeg", date, 100L, folder, folder, "Album"
        )
        val zeta = media("zeta.jpg", 3L)
        val alpha = media("alpha.jpg", 1L)
        val options = AlbumMediaPreparationOptions(
            MediaFilterOptions(), AlbumMediaRules.GROUP_NONE, MediaSortRules.SORT_NAME, false
        )
        assertEquals(alpha.uri, AlbumCoverRules.choose(listOf(zeta, alpha), null, options, emptyList())?.uri)
        assertEquals(zeta.uri, AlbumCoverRules.choose(listOf(zeta, alpha), zeta.uri.toString(), options, emptyList())?.uri)
        assertEquals(alpha.uri, AlbumCoverRules.choose(listOf(zeta, alpha), "removed", options, emptyList())?.uri)
        assertEquals(zeta.uri, AlbumCoverRules.choose(listOf(zeta, alpha), null,
            options.copy(sortMode = MediaSortRules.SORT_CUSTOM), listOf(zeta.uri.toString()))?.uri)
    }

    @Test
    fun mainCatalogUsesTheAlbumsSavedMediaSortForItsCover() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = "Pictures/GaleriaCoverCatalogTest/"
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val sortKey = AlbumCoverRules.optionKey(folder, "sort_mode")
        val directionKey = AlbumCoverRules.optionKey(folder, "sort_desc")
        val manualKey = AlbumCoverRules.preferenceKey(folder)
        val previousSort = prefs.getString(sortKey, null)
        val previousDirection = prefs.getBoolean(directionKey, true)
        val hadDirection = prefs.contains(directionKey)
        val previousManual = prefs.getString(manualKey, null)
        val visibleWasDirty = GalleryCatalogStore.isCatalogDirty(context, false)
        val completeWasDirty = GalleryCatalogStore.isCatalogDirty(context, true)
        val dao = GalleryDatabase.get(context).galleryDao()
        val originalMedia = dao.media("visible")
        val originalState = dao.state("visible")
        val alpha = "content://media/external/file/900001"
        val zeta = "content://media/external/file/900002"
        fun entity(uri: String, name: String, date: Long) = CachedMediaEntity(
            "visible", uri, date, name, "image/jpeg", date, 100L, folder, folder, "Capa de teste"
        )
        try {
            dao.replaceMedia("visible", listOf(entity(zeta, "zeta.jpg", 3L), entity(alpha, "alpha.jpg", 1L)),
                CatalogStateEntity("visible", System.currentTimeMillis(), false))
            GalleryCatalogStore.clearCatalogDirty(context, false)
            prefs.edit().putString(sortKey, MediaSortRules.SORT_NAME).putBoolean(directionKey, false)
                .remove(manualKey).commit()
            fun cover(): String? {
                val ready = CountDownLatch(1)
                val result = AtomicReference<String?>()
                val controller = AlbumCatalogController(context)
                try {
                    controller.load(
                        AlbumCatalogOptions(false, false, emptySet(), "", MediaFilterOptions(),
                            AlbumRules.SORT_NAME, false),
                        { albums, _ ->
                            result.set(albums.firstOrNull { it.key == folder }?.cover?.uri?.toString())
                            ready.countDown()
                        },
                        {}
                    )
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    return result.get()
                } finally {
                    controller.close()
                }
            }
            assertEquals(alpha, cover())
            prefs.edit().putString(manualKey, zeta).commit()
            assertEquals(zeta, cover())
        } finally {
            dao.replaceMedia("visible", originalMedia,
                originalState ?: CatalogStateEntity("visible", System.currentTimeMillis(), false))
            prefs.edit().apply {
                if (previousSort == null) remove(sortKey) else putString(sortKey, previousSort)
                if (hadDirection) putBoolean(directionKey, previousDirection) else remove(directionKey)
                if (previousManual == null) remove(manualKey) else putString(manualKey, previousManual)
            }.commit()
            GalleryCatalogStore.clearCatalogDirty(context)
            if (visibleWasDirty || completeWasDirty) {
                GalleryCatalogStore.markCatalogDirty(context)
                if (!visibleWasDirty) GalleryCatalogStore.clearCatalogDirty(context, false)
                if (!completeWasDirty) GalleryCatalogStore.clearCatalogDirty(context, true)
            }
            MediaStoreRepository.invalidateCache()
        }
    }

    @Test
    fun manualCoverCanBeChosenAndRestoredToAutomatic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime()
        val path = "Pictures/GaleriaCoverTest-$suffix/"
        val name = "capa-$suffix.png"
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ))
        context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val prefKey = AlbumCoverRules.preferenceKey(path)
        val previous = prefs.getString(prefKey, null)
        try {
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
            ActivityScenario.launch<AlbumMediaActivity>(Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", path)
                putExtra("album_name", "Capa de teste")
            }).use {
                waitForView { onView(withContentDescription(name)).check(matches(isDisplayed())) }
                onView(withContentDescription("Mais opções")).perform(click())
                onView(withText("Escolher capa")).perform(click())
                onView(withContentDescription("Pesquisar nesta pasta"))
                    .check(matches(withHint("Toque na mídia que será a capa")))
                onView(withContentDescription(name)).perform(click())
                assertTrue(MediaIdentityRules.sameUri(uri.toString(), prefs.getString(prefKey, null).orEmpty()))
                onView(withContentDescription(name)).check(matches(isDisplayed()))
                onView(withContentDescription("Mais opções")).perform(click())
                onView(withText("Usar capa automática")).perform(click())
                assertNull(prefs.getString(prefKey, null))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
            prefs.edit().apply {
                if (previous == null) remove(prefKey) else putString(prefKey, previous)
            }.commit()
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
            MediaStoreRepository.refreshMedia(context, force = true)
        }
    }

    private fun waitForView(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000L
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try { assertion(); return } catch (failure: Throwable) { last = failure; Thread.sleep(100L) }
        }
        throw AssertionError("A mídia do álbum não apareceu.", last)
    }
}
