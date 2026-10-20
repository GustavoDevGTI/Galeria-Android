package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class EssentialAlbumOrderingInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun recentIsFirstAndOnlyTheCurrentScreenshotFolderCompetesByActivityAfterReopening() {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val pinnedBefore = prefs.getStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, emptySet()).orEmpty().toSet()
        val favoriteBefore = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
        val root = "Order-${System.nanoTime()}"
        val camera = "DCIM/Camera/$root/"
        val screen = "Pictures/$root/Capturas de tela/"
        val legacy = "Pictures/$root/Screenshot/"
        val download = "Download/$root/"
        val pinned = "Pictures/$root/Pin/"
        val favoritePath = "Pictures/$root/Favorite/"
        val keys = setOf(camera, screen, legacy, download, pinned, VirtualAlbumRules.FAVORITES_KEY, VirtualAlbumRules.RECENT_KEY)
        val now = System.currentTimeMillis() / 1000L
        val created = mutableListOf<Uri>()
        fun insert(path: String, added: Long, taken: Long = added): Uri {
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(taken * 1000L))
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Screenshot_$stamp-${created.size}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_ADDED, added)
                put(MediaStore.Images.ImageColumns.DATE_TAKEN, taken * 1000L)
            })).also(created::add)
            val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_ADDED, added)
                put(MediaStore.Images.ImageColumns.DATE_TAKEN, taken * 1000L)
            }, null, null)
            return uri
        }
        fun load(): List<AlbumItem> {
            GalleryCatalogStore.markCatalogDirty(context)
            val controller = AlbumCatalogController(context)
            val done = CountDownLatch(1)
            var result: List<AlbumItem>? = null
            instrumentation.runOnMainSync {
                controller.load(AlbumCatalogOptions(false, false, emptySet(), "", MediaFilterOptions(),
                    AlbumRules.SORT_NAME, false), onAlbums = { albums, _ -> result = albums; done.countDown() },
                    onDeferredRefreshRequired = {}, onFailure = { done.countDown() })
            }
            try {
                assertTrue("O catálogo deve responder", done.await(15, TimeUnit.SECONDS))
                assertNotNull(result)
                assertEquals("Recentes deve ser o primeiro inclusive antes dos pinados", VirtualAlbumRules.RECENT_KEY, result!!.first().key)
                return result.filter { it.key in keys }
            } finally { instrumentation.runOnMainSync { controller.close() } }
        }
        try {
            insert(camera, now - 30)
            insert(screen, now - 20)
            insert(download, now - 10)
            insert(legacy, now + 1000, now - 365 * 86400L) // Imported now, captured on the old phone.
            insert(pinned, now - 100)
            val favoriteUri = insert(favoritePath, now - 200)
            val captureMetadata = StringBuilder()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.ImageColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)", arrayOf(screen, legacy), null)!!.use { cursor ->
                while (cursor.moveToNext()) {
                    captureMetadata.append((0..3).map { cursor.getString(it) }).append(';')
                }
            }
            assertEquals("A pasta ativa deve seguir a captura e não a importação: $captureMetadata", screen,
                ScreenshotAlbumResolver.activeKey(context, listOf(
                    AlbumItem(screen, "Capturas de tela", 1, null, now - 20, 0, 1, screen),
                    AlbumItem(legacy, "Screenshot", 1, null, now + 1000, 0, 1, legacy))))
            prefs.edit().putStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, pinnedBefore + pinned)
                .putStringSet("favorites", emptySet()).commit()
            assertEquals(listOf(VirtualAlbumRules.RECENT_KEY, pinned, download, screen, camera, legacy), load().map { it.key })
            MediaFavorites.toggle(prefs, favoriteUri.toString(), now)
            assertEquals(listOf(VirtualAlbumRules.RECENT_KEY, pinned, VirtualAlbumRules.FAVORITES_KEY, download, screen, camera, legacy), load().map { it.key })
            insert(camera, now + 10)
            assertEquals(camera, load()[2].key)
            insert(screen, now + 20)
            assertEquals(screen, load()[2].key)
            insert(download, now + 30)
            assertEquals(download, load()[2].key)
            val ordered = load()
            val targets = AlbumTargetRules.orderedTargets(ordered, ordered.map { it.key }, emptySet(), emptySet(), AlbumRules.SORT_NAME, false)
            assertEquals(ordered.filterNot { VirtualAlbumRules.isVirtual(it.key) }.map { it.key }, targets.map { it.key })
        } finally {
            for (uri in created) {
                if (prefs.getStringSet("favorites", emptySet()).orEmpty().any { MediaIdentityRules.sameUri(it, uri.toString()) }) {
                    MediaFavorites.toggle(prefs, uri.toString())
                }
                context.contentResolver.delete(uri, null, null)
            }
            prefs.edit().putStringSet("favorites", favoriteBefore)
                .putStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, pinnedBefore).commit()
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun favoriteActionsRecordNewAdditionsButNotRemovalOrAddingTheSameMediaTwice() {
        val prefs = context.getSharedPreferences("favorite-order-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val uri = Uri.parse("content://media/external/images/media/43210")
        val alias = "content://media/external/file/43210"
        val item = MediaItem(43210, uri, "old.png", "image/png", 1, 20, "Pictures/Test/", "Pictures/Test/", "Test")
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val detail = DetailMediaActions(activity, prefs)
                    val bulk = AlbumSelectionActions(activity, prefs)
                    try {
                        assertTrue(detail.toggleFavorite(item))
                        val added = MediaFavorites.latestAddition(prefs, listOf(alias))
                        assertTrue(added > 1)
                        bulk.addToFavorites(listOf(item))
                        assertEquals(added, MediaFavorites.latestAddition(prefs, listOf(alias)))
                        assertFalse(detail.toggleFavorite(item))
                        assertEquals(0L, MediaFavorites.latestAddition(prefs, listOf(alias)))
                        bulk.addToFavorites(listOf(item))
                        assertTrue(MediaFavorites.latestAddition(prefs, listOf(alias)) > 1)
                        assertEquals(0L, MediaFavorites.latestAddition(prefs, emptyList()))
                    } finally { detail.close(); bulk.close() }
                }
            }
        } finally { prefs.edit().clear().commit() }
    }
}
