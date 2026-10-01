package com.galeria.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageRotationInstrumentedTest {
    @Test fun firstRotationWorksWhenTheImageHasNoOrientationTag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((extension, mime, format) in listOf(
            Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
            Triple("png", "image/png", Bitmap.CompressFormat.PNG),
            Triple("webp", "image/webp", Bitmap.CompressFormat.WEBP)
        )) {
            val file = File.createTempFile("rotate-undefined-", ".$extension", context.cacheDir)
            try {
                val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                try { file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) } }
                finally { bitmap.recycle() }
                assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(file).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
                ImageRotation.clockwise(context, MediaItem(0, Uri.fromFile(file), file.name, mime, 0, file.length(), null, null, null))
                assertEquals("A primeira rotação de $extension deve funcionar sem EXIF prévio", 90, ExifInterface(file).rotationDegrees)
            } finally { file.delete() }
        }
    }
    @Test fun repeatedRotationPreservesPixelsAndExistingExifForWritableFormats() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val formats = listOf(
            Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
            Triple("png", "image/png", Bitmap.CompressFormat.PNG),
            Triple("webp", "image/webp", Bitmap.CompressFormat.WEBP)
        )
        for ((extension, mime, format) in formats) {
            val file = File.createTempFile("rotate-", ".$extension", context.cacheDir)
            try {
                val input = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.YELLOW)
                    for (y in 0 until 60) for (x in 0 until 80) setPixel(x, y, Color.BLUE)
                }
                file.outputStream().use { input.compress(format, 95, it) }
                input.recycle()
                val before = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                    saveAttributes()
                }
                val uri = Uri.fromFile(file)
                val item = MediaItem(0, uri, file.name, mime, 0, file.length(), null, null, null)
                try {
                    for (expectedDegrees in listOf(180, 270, 0, 90)) {
                        ImageRotation.clockwise(context, item)
                        assertEquals(expectedDegrees, ExifInterface(file).rotationDegrees)
                        val after = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                        try { assertTrue("Girar $extension não deve recomprimir nem reduzir os pixels", before.sameAs(after)) }
                        finally { after.recycle() }
                        val previousKey = MediaContentRevision.key(context, uri)
                        MediaContentRevision.changed(context, uri)
                        assertNotEquals(previousKey, MediaContentRevision.key(context, uri))
                        val request = ImageRequest.Builder(context).data(uri)
                            .size(Size.ORIGINAL)
                            .memoryCacheKey(MediaContentRevision.key(context, uri))
                            .diskCacheKey(MediaContentRevision.key(context, uri))
                            .apply { ImageRotation.configureRequest(context, item, this) }.build()
                        val result = runBlocking { SingletonImageLoader.get(context).execute(request) }
                        assertTrue("O decoder deve abrir a imagem girada $extension: $result", result is SuccessResult)
                        val image = (result as SuccessResult).image
                        assertEquals(if (expectedDegrees % 180 == 0) 160 else 120, image.width)
                        assertEquals(if (expectedDegrees % 180 == 0) 120 else 160, image.height)
                    }
                } finally { before.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun rotationKeepsMotionPhotoEmbeddedVideoIntact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("rotate-motion-", ".jpg", context.cacheDir)
        try {
            val still = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            file.outputStream().use { still.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            still.recycle()
            val video = instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() }
            FileOutputStream(file, true).use {
                it.write("MotionPhoto_Data".toByteArray())
                it.write(video)
            }
            val uri = Uri.fromFile(file)
            ImageRotation.clockwise(context, MediaItem(0, uri, file.name, "image/jpeg", 0, file.length(), null, null, null))
            val clip = MotionPhotoSupport.detect(context, uri)
            assertNotNull("Girar não pode apagar o vídeo da foto em movimento", clip)
            val extracted = MotionPhotoSupport.cachedClip(context, uri, requireNotNull(clip))
            try { assertTrue(video.contentEquals(extracted.readBytes())) }
            finally { extracted.delete() }
            // Check preservation before orientation so a failed giro does not
            // prevent the test from detecting damage to the embedded video.
            assertEquals(90, ExifInterface(file).rotationDegrees)
        } finally { file.delete() }
    }
}
