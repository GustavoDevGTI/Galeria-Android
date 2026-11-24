package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class VideoEditInstrumentedTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test fun trimmingCreatesPlayableCopyAndPreservesSourceTracks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = insertSample(context)
        var result: Uri? = null
        try {
            val sourceFormats = trackFormats(context, source)
            assertTrue(sourceFormats.any { it.startsWith("video/") })
            result = VideoTrimExporter.export(context, source, "sample.mp4", 0L, 600L)
            assertEquals(sourceFormats.sorted(), trackFormats(context, result).sorted())
            assertTrue(context.contentResolver.openInputStream(result)?.use { it.read() >= 0 } == true)
        } finally {
            result?.let { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test fun editorDisplaysVisibleTrimActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = insertSample(context)
        try {
            ActivityScenario.launch<DetailActivity>(ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java)).apply {
                putExtra("uri", source.toString())
                putExtra("name", "sample.mp4")
                putExtra("mime", "video/mp4")
                putExtra("path", "Movies/GaleriaEditorTest/")
                putExtra("album_key", "Movies/GaleriaEditorTest/")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use {
                // Editing a video opens the same editor used to join media, with this one video.
                onView(withContentDescription("Editar")).perform(clickClickableAncestor())
                waitFor { onView(withContentDescription(R.string.join_split)).check(matches(isDisplayed())) }
                onView(withContentDescription(R.string.join_timeline)).check(matches(isDisplayed()))
                onView(withContentDescription(R.string.join_strip)).check(matches(isDisplayed()))
                onView(withContentDescription(R.string.video_play)).check(matches(isDisplayed())).perform(click())
                onView(withContentDescription("Salvar cópia")).check(matches(isDisplayed()))
            }
        } finally {
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test fun trimmingKeepsBothAudioTracks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = insertSample(context)
        val dualAudio = File(context.cacheDir, "dual-audio-${System.currentTimeMillis()}.mp4")
        var output: Uri? = null
        try {
            duplicateAudioTrack(context, source, dualAudio)
            val originalTracks = trackFormats(context, Uri.fromFile(dualAudio))
            assertEquals(2, originalTracks.count { it.startsWith("audio/") })
            output = VideoTrimExporter.export(context, Uri.fromFile(dualAudio), "dual-audio.mp4", 0L, 600L)
            assertEquals(originalTracks.sorted(), trackFormats(context, output).sorted())
            val counts = sampleCounts(context, output)
            assertTrue(counts.filterIndexed { index, _ -> originalTracks[index].startsWith("audio/") }.all { it > 0 })
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            dualAudio.delete()
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test fun savingACutVideoCopiesItBesideTheOriginalWithEveryTrack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = insertSample(context)
        var result: Uri? = null
        try {
            val media = requireNotNull(MediaJoinExporter.probe(context, source, "video/mp4", "sample.mp4", 1L))
            // Cut the middle out: two kept parts of the same video.
            val half = media.durationMs / 2
            val clip = JoinClip(media, listOf(0L..half, minOf(media.durationMs - 1L, half + 300L)..media.durationMs))
            val request = JoinRequest(listOf(clip), 5_000L, JoinQuality.NATIVE)
            assertTrue("Um vídeo só, apenas com cortes, é copiado sem regravar", MediaJoinRules.copiesWithoutEncoding(request))
            result = MediaJoinExporter.export(context, request, {}, { false })
            assertEquals("A cópia fica na pasta do original", "Movies/GaleriaEditorTest/",
                context.contentResolver.query(result, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { it.moveToFirst(); it.getString(0) })
            assertEquals(trackFormats(context, source).sorted(), trackFormats(context, result).sorted())
            assertTrue(sampleCounts(context, result).all { it > 0 })
            assertEquals("Edição simples não é uma montagem", null, MediaJoinExporter.readRecord(context, result))
        } finally {
            result?.let { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(source, null, null)
        }
    }

    private fun waitFor(check: () -> Unit) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
        while (true) {
            try { check(); return } catch (error: Throwable) {
                if (android.os.SystemClock.uptimeMillis() > deadline) throw error
                android.os.SystemClock.sleep(100L)
            }
        }
    }

    private fun insertSample(context: Context): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "editor-test-${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/GaleriaEditorTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input ->
            resolver.openOutputStream(uri)?.use(input::copyTo)
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun trackFormats(context: Context, uri: Uri): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            extractor.release()
        }
    }

    private fun sampleCounts(context: Context, uri: Uri): List<Int> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val counts = MutableList(extractor.trackCount) { 0 }
            for (index in counts.indices) extractor.selectTrack(index)
            while (extractor.sampleTrackIndex >= 0) {
                counts[extractor.sampleTrackIndex]++
                if (!extractor.advance()) break
            }
            counts
        } finally {
            extractor.release()
        }
    }

    private fun duplicateAudioTrack(context: Context, source: Uri, target: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(context, source, null)
            val audioTrack = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val outputs = (0 until extractor.trackCount).map { muxer.addTrack(extractor.getTrackFormat(it)) }
            val duplicate = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            for (index in 0 until extractor.trackCount) extractor.selectTrack(index)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(outputs[track], buffer, info)
                if (track == audioTrack) muxer.writeSampleData(duplicate, buffer, info)
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
