package com.galeria.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImageTextRecognitionInstrumentedTest {
    @Test fun recognizesTextFromPhotoWithoutChangingSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("GALERIA TESTE OCR 2026", 60f, 210f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 88f
        })
        val photo = File(context.cacheDir, "ocr_instrumented_test.jpg")
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val originalSize = photo.length()
        val done = CountDownLatch(1)
        var text = ""
        ImageTextRecognition.recognize(context, Uri.fromFile(photo)) { result ->
            text = result.getOrThrow()
            done.countDown()
        }
        assertTrue("OCR não terminou", done.await(30, TimeUnit.SECONDS))
        assertTrue("Texto reconhecido: $text", text.contains("GALERIA"))
        assertTrue("O arquivo foi alterado", originalSize == photo.length())
    }
}
