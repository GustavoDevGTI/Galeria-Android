package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.media.MediaMetadataRetriever
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
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_VIDEO)

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
            var selected = -1L
            val immediate = VideoThumbnailFrames.selectedTime(context, item) {
                selected = it
                ready.countDown()
            }
            if (immediate != null) selected = immediate else assertTrue(ready.await(15, TimeUnit.SECONDS))
            assertTrue(selected >= 0L)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val opening = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
                if (opening != null) {
                    try {
                        if (!VideoThumbnailRules.isBlank(opening)) assertEquals(0L, selected)
                    } finally {
                        opening.recycle()
                    }
                }
            } finally {
                retriever.release()
            }
            assertEquals(selected, VideoThumbnailFrames.selectedTime(context, item) {})
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
