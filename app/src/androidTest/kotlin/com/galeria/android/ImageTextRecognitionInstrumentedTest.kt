package com.galeria.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
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
    @Test fun documentLongPressAndCustomEditorOfferCopyableText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = File(context.cacheDir, "document-ocr-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(2400, 3400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
            drawText("DOCUMENTO GALERIA - TEXTO PARA COPIAR", 120f, 180f, paint)
            repeat(32) { drawText("Linha ${it + 1}: Documento de teste com informacoes e numeros 2026.", 120f, 300f + it * 76f, paint) }
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString()); putExtra("mime", "image/png"); putExtra("name", photo.name)
            }).use {
                waitForUi {
                    onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.github.panpf.zoomimage.CoilZoomImageView::class.java))
                        .check { view, error ->
                            if (error != null) throw error
                            assertTrue("Aguardar foto e janela prontas antes do toque", view.alpha == 1f && view.hasWindowFocus())
                        }
                }
                onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.github.panpf.zoomimage.CoilZoomImageView::class.java))
                    .perform(androidx.test.espresso.action.ViewActions.longClick())
                waitForUi { onView(withText(R.string.ocr_copy_all)).perform(click()) }
            }
            ActivityScenario.launch<ImageEditActivity>(Intent(context, ImageEditActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString()); putExtra("mime", "image/png"); putExtra("name", photo.name)
            }).use { scenario ->
                onView(withContentDescription(R.string.action_recognize_text)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                waitForUi { onView(withText(R.string.ocr_copy_all)).perform(click()) }
                scenario.onActivity {
                    val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("DOCUMENTO GALERIA"))
                }
            }
        } finally { photo.delete() }
    }

    private fun waitForUi(action: () -> Unit) {
        val deadline = System.currentTimeMillis() + 20000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try { action(); return } catch (error: Throwable) { last = error; Thread.sleep(150) }
        }
        throw AssertionError("Interface do OCR não ficou disponível", last)
    }

    @Test fun viewerDetectsTextAutomaticallyAndCopiesItFromTheIcon() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = File(context.cacheDir, "ocr-icon-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(1400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("GALERIA TEXTO COPIAVEL", 40f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 85f })
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString()); putExtra("mime", "image/jpeg"); putExtra("name", photo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use { scenario ->
                val deadline = System.currentTimeMillis() + 20_000L
                var visible = false
                while (!visible && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { visible = it.window.decorView.findViewWithTag<View>("ocr_text_action").visibility == View.VISIBLE }
                    if (!visible) Thread.sleep(100L)
                }
                assertTrue("O texto foi reconhecido, mas o ícone não apareceu", visible)
                onView(withContentDescription(R.string.ocr_text_available)).perform(click())
                onView(withText(R.string.ocr_copy_all)).perform(click())
                scenario.onActivity {
                    val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("GALERIA"))
                }
            }
            val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            photo.outputStream().use { blank.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            blank.recycle()
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString()); putExtra("mime", "image/jpeg"); putExtra("name", photo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use { scenario ->
                val done = CountDownLatch(1)
                ImageTextRecognition.recognize(context, Uri.fromFile(photo)) { done.countDown() }
                assertTrue(done.await(15, TimeUnit.SECONDS))
                Thread.sleep(1000L)
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("ocr_text_action").visibility == View.GONE) }
            }
        } finally { photo.delete() }
    }

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
