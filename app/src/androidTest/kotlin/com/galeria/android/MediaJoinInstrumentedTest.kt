package com.galeria.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MediaJoinInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun joinedVideoKeepsTheOrderLandsBesideTheFirstMediaAndRemembersTheMontage() {
        val suffix = System.nanoTime()
        val folder = "Pictures/GaleriaJoinTest-$suffix/"
        val video = insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "clip-$suffix.mp4", "video/mp4", folder,
            instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() })
        val still = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 60, 40)) }
        val photo = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo-$suffix.jpg", "image/jpeg", folder,
            ByteArrayOutputStream().also { still.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())
        still.recycle()
        var joined: Uri? = null
        try {
            val videoMedia = requireNotNull(MediaJoinExporter.probe(context, video, "video/mp4", "clip.mp4", 1L))
            val photoMedia = requireNotNull(MediaJoinExporter.probe(context, photo, "image/jpeg", "photo.jpg", 2L))
            assertEquals(480, photoMedia.width)
            // The photo first, then the video with its middle cut out.
            val cut = MediaJoinRules.trimmed(MediaJoinRules.split(JoinClip(videoMedia), videoMedia.durationMs / 2), 1, true, videoMedia.durationMs / 2 + 500L)
            val request = JoinRequest(listOf(JoinClip(photoMedia), cut), 4_000L, JoinQuality.NATIVE)
            val steps = ArrayList<Int>()
            joined = MediaJoinExporter.export(context, request, { steps += it }, { false })
            assertEquals(100, steps.last())

            assertEquals("O vídeo juntado fica na pasta da primeira mídia", folder, column(joined, MediaStore.MediaColumns.RELATIVE_PATH))
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, joined)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                val expected = MediaJoinRules.totalMs(request.clips, request.photoMs)
                assertTrue("Duração $duration, esperada $expected", abs(duration - expected) < 400L)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val upright = if (rotation % 180 != 0) height to width else width to height
                val canvas = MediaJoinRules.canvas(request.clips.map { it.media }, request.quality)
                assertEquals(canvas.width to canvas.height, upright)
                assertNotNull("O vídeo deve ter som", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            } finally {
                retriever.release()
            }
            assertEquals("A montagem fica gravada no vídeo", JoinRecord(request.photoMs, request.clips), MediaJoinExporter.readRecord(context, joined))
            assertEquals(null, MediaJoinExporter.readRecord(context, video))
        } finally {
            listOfNotNull(video, photo, joined).forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    @Test fun cancellingSavesNothing() {
        val suffix = System.nanoTime()
        val folder = "Pictures/GaleriaJoinCancel-$suffix/"
        val video = insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "clip-$suffix.mp4", "video/mp4", folder,
            instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() })
        try {
            val media = requireNotNull(MediaJoinExporter.probe(context, video, "video/mp4", "clip.mp4", 1L))
            var stop = false
            val result = runCatching {
                MediaJoinExporter.export(context, JoinRequest(listOf(JoinClip(media), JoinClip(media)), 4_000L, JoinQuality.NATIVE),
                    { stop = true }, { stop })
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(folder), null)!!.use { assertEquals(1, it.count) }
        } finally {
            context.contentResolver.delete(video, null, null)
        }
    }

    @Test fun transitionsBlendFadeAndSlideBetweenTwoPhotos() {
        val suffix = System.nanoTime()
        val folder = "Pictures/GaleriaJoinTransition-$suffix/"
        fun solid(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); bitmap.recycle() }.toByteArray()
        }
        val red = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "red-$suffix.jpg", "image/jpeg", folder, solid(Color.RED))
        val blue = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "blue-$suffix.jpg", "image/jpeg", folder, solid(Color.BLUE))
        val outputs = ArrayList<Uri>()
        try {
            val clips = listOf(
                JoinClip(requireNotNull(MediaJoinExporter.probe(context, red, "image/jpeg", "red.jpg", 1L))),
                JoinClip(requireNotNull(MediaJoinExporter.probe(context, blue, "image/jpeg", "blue.jpg", 2L)))
            )
            fun middleColor(type: JoinTransition, x: Float): Int {
                val request = JoinRequest(clips, 4_000L, JoinQuality.NATIVE, listOf(type))
                val uri = MediaJoinExporter.export(context, request, {}, { false }).also { outputs += it }
                val window = MediaJoinRules.windows(clips, 4_000L, request.transitions).single()
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("A transição encurta o vídeo: $duration", abs(duration - MediaJoinRules.totalMs(clips, 4_000L, request.transitions)) < 400L)
                    val frame = retriever.getFrameAtTime((window.startMs + window.endMs) / 2 * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    return frame.getPixel((frame.width * x).toInt(), frame.height / 2).also { frame.recycle() }
                } finally { retriever.release() }
            }
            val dissolve = middleColor(JoinTransition.DISSOLVE, 0.5f)
            assertTrue("Dissolver mistura as duas fotos: ${Integer.toHexString(dissolve)}", Color.red(dissolve) > 60 && Color.blue(dissolve) > 60)
            val fade = middleColor(JoinTransition.FADE, 0.5f)
            assertTrue("Escurecer passa pelo preto: ${Integer.toHexString(fade)}", Color.red(fade) < 70 && Color.blue(fade) < 70)
            val left = middleColor(JoinTransition.SLIDE, 0.25f)
            assertTrue("Deslizar: a foto que sai fica à esquerda: ${Integer.toHexString(left)}", Color.red(left) > 150 && Color.blue(left) < 100)
            val right = middleColor(JoinTransition.SLIDE, 0.75f)
            assertTrue("Deslizar: a foto que entra vem da direita: ${Integer.toHexString(right)}", Color.blue(right) > 150 && Color.red(right) < 100)
            val record = requireNotNull(MediaJoinExporter.readRecord(context, outputs.last()))
            assertEquals(listOf(JoinTransition.SLIDE), record.transitions)
        } finally {
            (outputs + listOf(red, blue)).forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

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

    private fun column(uri: Uri, name: String): String? =
        context.contentResolver.query(uri, arrayOf(name), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}
