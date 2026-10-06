package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class VideoThumbnailFrameInstrumentedTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)

    @Test
    fun realVideoFrameSelectionCompletesAndIsStable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "thumbnail-${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GaleriaThumbnailTest/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        ))
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input ->
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            val item = MediaItem(1L, uri, "thumbnail.mp4", "video/mp4", System.currentTimeMillis() / 1000L,
                101_674L, "Movies/GaleriaThumbnailTest/", "Movies/GaleriaThumbnailTest/", "GaleriaThumbnailTest")
            val ready = CountDownLatch(1)
            var selected: Uri? = null
            val immediate = VideoThumbnailFrames.thumbnail(context, item) {
                selected = it
                ready.countDown()
            }
            if (immediate != null) selected = immediate else assertTrue(ready.await(15, TimeUnit.SECONDS))
            val file = VideoThumbnailFrames.thumbnailFile(context, item)
            assertTrue(file.exists() && file.length() > 0L)
            assertTrue(file.path.startsWith(context.noBackupFilesDir.path))
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
            val retriever = MediaMetadataRetriever()
            val sourceLongSide = try {
                retriever.setDataSource(context, uri)
                maxOf(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                )
            } finally {
                retriever.release()
            }
            assertTrue(sourceLongSide > 0)
            assertTrue(maxOf(bitmap.width, bitmap.height) >= minOf(sourceLongSide, 960) * 8 / 10)
            bitmap.recycle()
            val modified = file.lastModified()
            assertEquals(selected, VideoThumbnailFrames.thumbnail(context, item) {})
            assertEquals(selected, VideoThumbnailFrames.cachedThumbnail(context, item))
            val itemWithResolvedDuration = MediaItem(1L, uri, "thumbnail.mp4", "video/mp4",
                item.dateAdded, 101_674L, "Movies/GaleriaThumbnailTest/", "Movies/GaleriaThumbnailTest/",
                "GaleriaThumbnailTest", 5_000L)
            assertEquals(file, VideoThumbnailFrames.thumbnailFile(context, itemWithResolvedDuration))
            assertEquals(selected, VideoThumbnailFrames.thumbnail(context, itemWithResolvedDuration) {})
            assertEquals(modified, file.lastModified())

            val reused = CountDownLatch(2)
            val cancelledCalled = java.util.concurrent.atomic.AtomicBoolean()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                repeat(2) {
                    VideoThumbnailFrames.request(context, item) { value ->
                        assertEquals(selected, value); reused.countDown()
                    }
                }
                VideoThumbnailFrames.request(context, item) { cancelledCalled.set(true) }.cancel()
            }
            assertTrue(reused.await(5, TimeUnit.SECONDS))
            assertTrue(!cancelledCalled.get())
            assertEquals(modified, file.lastModified())
            // Trashed files still exist: cleanup must not discard their chosen frame.
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            VideoThumbnailFrames.cleanupOrphans(context)
            assertTrue(file.exists())
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
            context.contentResolver.delete(uri, null, null)
            VideoThumbnailFrames.cleanupOrphans(context)
            assertTrue("Miniatura órfã deve sair somente depois da exclusão definitiva", !file.exists())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
