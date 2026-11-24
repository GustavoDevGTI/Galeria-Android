package com.galeria.android

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MediaSplitInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val created = ArrayList<Uri>()

    @Test fun separatingRestoresTrashedOriginalsAndRewritesOnlyWhatWasDeleted() = cleaning {
        val folder = "Pictures/GaleriaSplit-${System.nanoTime()}/"
        val photo = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "foto.jpg", "image/jpeg", folder, jpeg(Color.RED))
        val video = insertVideo(folder, "inteiro.mp4")
        val cutVideo = insertVideo(folder, "cortado.mp4")
        val photoMedia = probe(photo, "image/jpeg", "foto.jpg")
        val videoMedia = probe(video, "video/mp4", "inteiro.mp4")
        val cutMedia = probe(cutVideo, "video/mp4", "cortado.mp4")
        val half = cutMedia.durationMs / 2
        val clips = listOf(JoinClip(photoMedia), JoinClip(videoMedia), JoinClip(cutMedia, listOf(0L..half)))
        val joined = MediaJoinExporter.export(context, JoinRequest(clips, 4_000L, JoinQuality.NATIVE), {}, { false }).also { created += it }

        // The clock already took the whole video to the Trash; the photo and the cut video were deleted.
        setTrashed(video, true)
        context.contentResolver.delete(photo, null, null)
        context.contentResolver.delete(cutVideo, null, null)

        val record = requireNotNull(MediaJoinExporter.readRecord(context, joined))
        val result = MediaSplitter.split(context, joined, record).also { created += it.written }

        assertEquals("O vídeo inteiro volta da Lixeira, sem cópia", 0, trashedFlag(video))
        assertTrue(result.restoreNeedsConsent.isEmpty())
        assertEquals("Só a foto e o vídeo cortado são escritos de novo", 2, result.written.size)
        val newPhoto = result.written.single { name(it).endsWith(".jpg") }
        assertEquals("foto.jpg", name(newPhoto))
        assertEquals(folder, column(newPhoto, MediaStore.MediaColumns.RELATIVE_PATH))
        val picture = context.contentResolver.openInputStream(newPhoto)!!.use { BitmapFactory.decodeStream(it) }
        assertEquals("A foto sai sem as bordas pretas", 480f / 640f, picture.width.toFloat() / picture.height, 0.02f)
        val center = picture.getPixel(picture.width / 2, picture.height / 2)
        assertTrue("A foto refeita é a do vídeo: ${Integer.toHexString(center)}", Color.red(center) > 180 && Color.blue(center) < 80)
        val newVideo = result.written.single { name(it).endsWith(".mp4") }
        assertTrue("O vídeo cortado volta como vídeo novo: ${name(newVideo)}", name(newVideo).contains("-cortado-"))
        assertTrue(abs(duration(newVideo) - half) < 400L)
        assertTrue("O vídeo juntado vai para a Lixeira", result.joinedTrashed)
        assertEquals(1, trashedFlag(joined))
        created += listOf(video)
    }

    @Test fun withThePhotoClockTheOriginalPhotoComesBackIntact() = cleaning {
        val folder = "Pictures/GaleriaSplitKeep-${System.nanoTime()}/"
        val bytes = jpeg(Color.rgb(20, 140, 220))
        val photo = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "guardada.jpg", "image/jpeg", folder, bytes)
        val video = insertVideo(folder, "original.mp4").also { created += it }
        val videoMedia = probe(video, "video/mp4", "original.mp4")
        val clips = listOf(JoinClip(probe(photo, "image/jpeg", "guardada.jpg")), JoinClip(videoMedia, listOf(0L..videoMedia.durationMs / 2)))
        val joined = MediaJoinExporter.export(context, JoinRequest(clips, 4_000L, JoinQuality.NATIVE, keepPhotos = true), {}, { false })
            .also { created += it }
        assertEquals(setOf(0), MediaJoinExporter.keptPhotos(context, joined))
        context.contentResolver.delete(photo, null, null)

        val result = MediaSplitter.split(context, joined, requireNotNull(MediaJoinExporter.readRecord(context, joined)))
            .also { created += it.written }

        val restored = result.written.single { name(it).endsWith(".jpg") }
        assertEquals("guardada.jpg", name(restored))
        assertArrayEquals("A foto guardada volta byte a byte", bytes, context.contentResolver.openInputStream(restored)!!.use { it.readBytes() })
        // The original of the cut video still exists: the new video is cut from it, beside it.
        val cut = result.written.single { name(it).endsWith(".mp4") }
        assertTrue(name(cut).startsWith("original-cortado-"))
        assertEquals(0, trashedFlag(video))
    }

    @Test fun theViewerOffersSeparateOnlyForJoinedVideos() = cleaning {
        val folder = "Movies/GaleriaSplitViewer-${System.nanoTime()}/"
        val first = insertVideo(folder, "a.mp4").also { created += it }
        val second = insertVideo(folder, "b.mp4").also { created += it }
        val clips = listOf(JoinClip(probe(first, "video/mp4", "a.mp4")), JoinClip(probe(second, "video/mp4", "b.mp4")))
        val joined = MediaJoinExporter.export(context, JoinRequest(clips, 4_000L, JoinQuality.NATIVE), {}, { false }).also { created += it }

        val intent = ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java)).apply {
            putExtra("uri", joined.toString())
            putExtra("mime", "video/mp4")
            putExtra("name", name(joined))
        }
        ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
            onView(withContentDescription(R.string.action_more_options)).perform(clickClickableAncestor())
            awaitView { onView(withText(R.string.action_separate)).check(matches(isDisplayed())) }
            onView(withText(R.string.action_separate)).perform(clickClickableAncestor())
            awaitView { onView(withText(R.string.separate_title)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.action_separate)).inRoot(isDialog()).perform(clickClickableAncestor())
            val deadline = SystemClock.uptimeMillis() + 30_000L
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200L)
            assertEquals("O visualizador sai do vídeo juntado", Lifecycle.State.DESTROYED, scenario.state)
        }
        assertEquals(1, trashedFlag(joined))
        assertEquals("Os originais continuam onde estavam", 0, trashedFlag(first))
    }

    private fun cleaning(block: () -> Unit) {
        try {
            block()
        } finally {
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            created.forEach { uri ->
                MediaClocks.cancel(context, uri.toString())
                runCatching { context.contentResolver.delete(uri, args) }
            }
        }
    }

    private fun awaitView(check: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (true) {
            try {
                check()
                return
            } catch (error: Throwable) {
                if (error !is NoMatchingViewException && error !is AssertionError || SystemClock.uptimeMillis() > deadline) throw error
                SystemClock.sleep(150L)
            }
        }
    }

    private fun probe(uri: Uri, mime: String, name: String) = requireNotNull(MediaJoinExporter.probe(context, uri, mime, name, 1L))

    private fun setTrashed(uri: Uri, trashed: Boolean) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }, null, null)
    }

    private fun trashedFlag(uri: Uri): Int? {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    private fun name(uri: Uri): String = column(uri, MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()

    private fun column(uri: Uri, column: String): String? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun duration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            retriever.release()
        }
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it); bitmap.recycle() }.toByteArray()
    }

    private fun insertVideo(folder: String, name: String): Uri = insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name, "video/mp4", folder,
        instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() })

    private fun insert(collection: Uri, name: String, mime: String, folder: String, bytes: ByteArray): Uri {
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
