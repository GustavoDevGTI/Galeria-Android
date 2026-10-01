package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class AutomaticHiddenAlbumsInstrumentedTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)

    @Test
    fun realMediaStoreHashBucketsStayOutOfAlbumsAndRecent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = "Pictures/GaleriaShardsTest${System.nanoTime()}/"
        val created = ArrayList<android.net.Uri>()
        val automaticPrefs = context.getSharedPreferences("gallery_automatic_hidden_albums", Context.MODE_PRIVATE)
        val originalParents = automaticPrefs.getStringSet("hash_bucket_parents", emptySet()).orEmpty().toSet()
        try {
            for (index in 0 until 8) {
                val bucket = "%02x".format(index)
                val uri = requireNotNull(context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "cover-$bucket.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "$parent$bucket/")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                ))
                created.add(uri)
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { stream ->
                    val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(0xff112233.toInt())
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    } finally {
                        image.recycle()
                    }
                }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }

            val media = MediaStoreRepository.refreshMedia(context, force = true)
            assertTrue("A varredura deve lembrar o cache antes de qualquer filtro de tela.",
                parent.trimEnd('/') in automaticPrefs.getStringSet("hash_bucket_parents", emptySet()).orEmpty())
            val samples = media.filter { it.relativePath.startsWith(parent) }
            assertEquals(8, samples.size)
            val albums = MediaStoreRepository.buildAlbums(samples)
            val markers = HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())
            val hidden = AutomaticHiddenAlbums.keys(context, albums,
                markers)
            assertEquals(albums.mapTo(HashSet()) { it.key }, hidden)
            assertTrue(AlbumCatalogRules.prepare(albums, emptySet(), false, false,
                naturallyHiddenKeys = hidden).isEmpty())
            assertEquals(8, AlbumCatalogRules.prepare(albums, emptySet(), true, false,
                naturallyHiddenKeys = hidden).size)
            assertFalse(VirtualAlbumRules.availableMedia(emptyList(), samples, hidden).isNotEmpty())
            assertEquals(setOf(albums.first().key),
                AutomaticHiddenAlbums.keys(context, listOf(albums.first()), markers))

            // Regression: the old visibility test required <= 3 items even for
            // remembered cache parents. Growing one bucket made it public again.
            repeat(3) { index ->
                val uri = requireNotNull(context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "extra-$index.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${parent}00/")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                ))
                created.add(uri)
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { stream ->
                    val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(0xff112233.toInt())
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    } finally { image.recycle() }
                }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            val refreshed = MediaStoreRepository.refreshMedia(context, force = true)
                .filter { it.relativePath.startsWith(parent) }
            assertEquals(11, refreshed.size)
            val grown = MediaStoreRepository.buildAlbums(refreshed).single { it.name == "00" }
            assertEquals(4, grown.count)
            val grownHidden = AutomaticHiddenAlbums.keys(context, listOf(grown), markers)
            assertEquals(setOf(grown.key), grownHidden)
            val partial = refreshed.filter { it.albumKey == grown.key }
            assertTrue(VirtualAlbumRules.mediaForAlbum(partial, VirtualAlbumRules.RECENT_KEY,
                emptySet(), grownHidden).isEmpty())
            assertTrue(VirtualAlbumRules.mediaForAlbum(partial, VirtualAlbumRules.FAVORITES_KEY,
                partial.mapTo(HashSet()) { it.uri.toString() }, grownHidden).isEmpty())
            assertTrue(VirtualAlbumRules.visibleTrash(partial, grownHidden, false).isEmpty())
            assertEquals(4, VirtualAlbumRules.visibleTrash(partial, grownHidden, true).size)
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            automaticPrefs.edit().putStringSet("hash_bucket_parents", originalParents).commit()
            MediaStoreRepository.invalidateCache()
            MediaStoreRepository.refreshMedia(context, force = true)
        }
    }
}
