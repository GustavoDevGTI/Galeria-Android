package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class VirtualAlbumsInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun recentFavoritesAndTrashReferenceTheOriginalMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "virtual-${System.nanoTime()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GaleriaVirtualTest/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ))
        resolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)

        try {
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
            val media = MediaStoreRepository.refreshMedia(context, force = true)
            val item = media.first { MediaIdentityRules.sameUri(it.uri.toString(), uri.toString()) }
            val physical = MediaStoreRepository.buildAlbums(media)
            val collections = VirtualAlbumRules.addCollections(physical, media, setOf(uri.toString()), emptyList())

            val recent = collections.first { it.key == VirtualAlbumRules.RECENT_KEY }
            val favorites = collections.first { it.key == VirtualAlbumRules.FAVORITES_KEY }
            assertTrue(recent.count >= 1)
            assertEquals(1, favorites.count)
            assertTrue(MediaIdentityRules.sameUri(favorites.cover!!.uri.toString(), item.uri.toString()))

            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(MediaActions.RESULT_DONE, MediaActions.requestDelete(activity, uri, 701))
                }
            }
            val trashed = MediaStoreRepository.loadTrashedMedia(context)
            assertTrue(trashed.any { MediaIdentityRules.sameUri(it.uri.toString(), uri.toString()) })

            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(MediaActions.RESULT_DONE, MediaActions.requestRestore(activity, uri, 702))
                }
            }
            assertFalse(MediaStoreRepository.loadTrashedMedia(context).any {
                MediaIdentityRules.sameUri(it.uri.toString(), uri.toString())
            })
        } finally {
            runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
                resolver.delete(uri, null, null)
            }
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test
    fun trashAlbumOffersRestoreAndPermanentDeleteActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val name = "trash-ui-${System.nanoTime()}.png"
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GaleriaTrashUiTest/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ))
        resolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
        }, null, null)

        try {
            val intent = Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", VirtualAlbumRules.TRASH_KEY)
                putExtra("album_name", "Lixeira")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<AlbumMediaActivity>(intent).use {
                waitForView { onView(withContentDescription(name)).check(matches(isDisplayed())) }
                onView(withContentDescription(name)).perform(longClick())
                onView(withText(R.string.action_restore)).check(matches(isDisplayed()))
                onView(withText(R.string.action_delete_permanently)).check(matches(isDisplayed()))
            }
        } finally {
            runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
                resolver.delete(uri, null, null)
            }
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test
    fun hiddenFoldersStayOutOfRecentAndTrashUntilExplicitlyShown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val suffix = System.nanoTime()
        val visiblePath = "Pictures/GaleriaVisible-$suffix/"
        val hiddenPath = "Pictures/GaleriaHidden-$suffix/"
        fun insert(path: String, name: String) = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        )).also { uri ->
            resolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        val visibleUri = insert(visiblePath, "visible-$suffix.png")
        val hiddenUri = insert(hiddenPath, "hidden-$suffix.png")
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val previousHidden = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty().toSet()
        val previousTrashVisibility = prefs.getBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false)
        prefs.edit().putStringSet("hidden_folder_keys", previousHidden + hiddenPath)
            .putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false).commit()
        try {
            val source = MediaStoreRepository.refreshMedia(context, force = true)
            val visible = source.first { MediaIdentityRules.sameUri(it.uri.toString(), visibleUri.toString()) }
            val hidden = source.first { MediaIdentityRules.sameUri(it.uri.toString(), hiddenUri.toString()) }
            val hiddenKeys = previousHidden + hiddenPath
            val physical = AlbumCatalogRules.prepare(MediaStoreRepository.buildAlbums(listOf(visible, hidden)), hiddenKeys, false, false)
            val collections = VirtualAlbumRules.addCollections(physical, listOf(visible, hidden), emptySet(), emptyList(), hiddenKeys = hiddenKeys)
            assertEquals(1, collections.first { it.key == VirtualAlbumRules.RECENT_KEY }.count)
            assertFalse(collections.any { it.key == VirtualAlbumRules.FAVORITES_KEY })
            assertFalse(VirtualAlbumRules.mediaForAlbum(source, VirtualAlbumRules.RECENT_KEY, emptySet(), hiddenKeys)
                .any { MediaIdentityRules.sameUri(it.uri.toString(), hiddenUri.toString()) })

            resolver.update(hiddenUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            assertFalse(MediaStoreRepository.loadMediaForAlbum(context, VirtualAlbumRules.TRASH_KEY)
                .any { MediaIdentityRules.sameUri(it.uri.toString(), hiddenUri.toString()) })
            prefs.edit().putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, true).commit()
            assertTrue(MediaStoreRepository.loadMediaForAlbum(context, VirtualAlbumRules.TRASH_KEY)
                .any { MediaIdentityRules.sameUri(it.uri.toString(), hiddenUri.toString()) })
        } finally {
            runCatching {
                resolver.update(hiddenUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
                resolver.delete(hiddenUri, null, null)
                resolver.delete(visibleUri, null, null)
            }
            prefs.edit().putStringSet("hidden_folder_keys", previousHidden)
                .putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, previousTrashVisibility).commit()
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    private fun waitForView(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000L
        var failure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (current: Throwable) {
                failure = current
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Mídia da Lixeira não foi exibida.", failure)
    }
}
