package com.galeria.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
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
import java.text.Normalizer
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ImageTextRecognitionInstrumentedTest {
    @Test fun sidewaysDarkScreenshotOffersTheDocumentNotOnlyStatusBarText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = File.createTempFile("ocr-sideways-", ".jpg", context.cacheDir)
        instrumentation.context.assets.open("ocr-sideways-dark.jpg").use { input ->
            photo.outputStream().use { input.copyTo(it) }
        }
        val original = photo.readBytes()
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString())
                putExtra("mime", "image/jpeg")
                putExtra("name", photo.name)
            }).use { scenario ->
                waitForUi(timeoutMs = 45000) {
                    onView(withContentDescription(R.string.ocr_text_available)).perform(click())
                }
                waitForUi(timeoutMs = 45000) { onView(withText(R.string.ocr_copy_all)).perform(click()) }
                scenario.onActivity {
                    val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                        .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT)
                    assertTrue("Não pode retornar apenas horário/ícones: $text", text.length > 250)
                    assertTrue("Título ausente: $text", normalized.contains("RESUMO") && normalized.contains("BALANCA"))
                    assertTrue("Conteúdo das duas colunas ausente: $text", normalized.contains("IMPOSTOS") && normalized.contains("FAMILIAS"))
                }
            }
            assertTrue("OCR não deve modificar a imagem", original.contentEquals(photo.readBytes()))
        } finally { photo.delete() }
    }

    @Test fun automaticDetectionFindsUpsideDownTextDespiteUprightClock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val upright = Bitmap.createBitmap(1400, 400, Bitmap.Config.ARGB_8888)
        Canvas(upright).apply {
            drawColor(0xff222326.toInt())
            drawText("DOCUMENTO GALERIA TEXTO COPIAVEL", 35f, 220f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 60f })
        }
        val rotated = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height,
            Matrix().apply { postRotate(180f) }, false)
        upright.recycle()
        Canvas(rotated).drawText("12:34", 20f, 45f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f })
        val photo = File.createTempFile("ocr-clock-", ".png", context.cacheDir)
        photo.outputStream().use { rotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rotated.recycle()
        try {
            val done = CountDownLatch(1)
            var result: Result<String>? = null
            ImageTextRecognition.recognize(context, Uri.fromFile(photo), detailed = false) {
                result = it
                done.countDown()
            }
            assertTrue("A detecção automática não terminou", done.await(45, TimeUnit.SECONDS))
            val text = requireNotNull(result).getOrThrow()
            assertTrue("Deve reconhecer o texto principal, não apenas o relógio: $text", text.contains("DOCUMENTO") && text.contains("GALERIA"))
        } finally { photo.delete() }
    }

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
            }).use { scenario ->
                waitForUi {
                    onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.github.panpf.zoomimage.CoilZoomImageView::class.java))
                        .check { view, error ->
                            if (error != null) throw error
                            val image = view as com.github.panpf.zoomimage.CoilZoomImageView
                            assertTrue("Aguardar foto e janela prontas antes do toque",
                                image.drawable != null && view.width > 0 && view.height > 0 && view.alpha == 1f && view.hasWindowFocus())
                        }
                }
                awaitAndroidInputReady()
                onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.github.panpf.zoomimage.CoilZoomImageView::class.java))
                    .perform(androidx.test.espresso.action.ViewActions.longClick())
                awaitViewerOcrIdle(scenario)
                onView(withText(R.string.ocr_copy_all)).perform(click())
            }
            ActivityScenario.launch<ImageEditActivity>(Intent(context, ImageEditActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString()); putExtra("mime", "image/png"); putExtra("name", photo.name)
            }).use { scenario ->
                awaitAndroidInputReady()
                onView(withContentDescription(R.string.action_recognize_text)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                awaitEditorOcrIdle(scenario)
                onView(withText(R.string.ocr_copy_all)).perform(click())
                scenario.onActivity {
                    val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("DOCUMENTO GALERIA"))
                }
            }
        } finally { photo.delete() }
    }

    private fun waitForUi(timeoutMs: Long = 20000, action: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
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
                waitForUi {
                    onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.github.panpf.zoomimage.CoilZoomImageView::class.java))
                        .check { view, error ->
                            if (error != null) throw error
                            assertTrue("Aguardar carregamento da foto antes do pedido automático",
                                (view as com.github.panpf.zoomimage.CoilZoomImageView).drawable != null && view.hasWindowFocus())
                        }
                }
                awaitViewerOcrIdle(scenario)
                scenario.onActivity {
                    assertTrue("O texto foi reconhecido, mas o ícone não apareceu",
                        it.window.decorView.findViewWithTag<View>("ocr_text_action").visibility == View.VISIBLE)
                }
                onView(withContentDescription(R.string.ocr_text_available)).perform(click())
                awaitViewerOcrIdle(scenario)
                waitForUi {
                    onView(withText(R.string.ocr_copy_all)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                }
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
                awaitViewerOcrIdle(scenario)
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
