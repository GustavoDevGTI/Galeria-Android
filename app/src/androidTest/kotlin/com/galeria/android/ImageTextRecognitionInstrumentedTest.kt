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
    @Test fun wordCornersMapBackOntoThePhotoInEveryRecognitionOrientation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val upright = Bitmap.createBitmap(1400, 500, Bitmap.Config.ARGB_8888)
        Canvas(upright).apply {
            drawColor(Color.WHITE)
            drawText("DOCUMENTO GALERIA TEXTO COPIAVEL", 40f, 270f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f })
        }
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val bitmap = if (rotation == 0) upright.copy(Bitmap.Config.ARGB_8888, false)
                    else Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, false)
                val file = File.createTempFile("ocr-geometry-$rotation-", ".png", context.cacheDir)
                try {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    var result: Result<OcrDocument>? = null
                    val finished = CountDownLatch(1)
                    ImageTextRecognition.recognizeDocument(context, Uri.fromFile(file)) { result = it; finished.countDown() }
                    assertTrue(finished.await(45, TimeUnit.SECONDS))
                    val word = requireNotNull(result).getOrThrow().words.first { it.text == "DOCUMENTO" }
                    val x = word.corners.sumOf { it.x.toDouble() }.toFloat() / 4
                    val y = word.corners.sumOf { it.y.toDouble() }.toFloat() / 4
                    val expected = when (rotation) {
                        0 -> 0.16f to 0.50f
                        90 -> 0.50f to 0.16f
                        180 -> 0.84f to 0.50f
                        else -> 0.50f to 0.84f
                    }
                    org.junit.Assert.assertEquals("Caixa horizontal na foto a $rotation graus", expected.first, x, 0.08f)
                    org.junit.Assert.assertEquals("Caixa vertical na foto a $rotation graus", expected.second, y, 0.08f)
                } finally { if (!bitmap.isRecycled) bitmap.recycle(); file.delete() }
            }
        } finally { upright.recycle() }
    }
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
                awaitViewerOcrIdle(scenario)
                copyAllFromImage()
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
                copyAllFromImage()
            }
            ActivityScenario.launch<ImageEditActivity>(Intent(context, ImageEditActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(photo).toString()); putExtra("mime", "image/png"); putExtra("name", photo.name)
            }).use { scenario ->
                waitForUi { onView(withContentDescription("Imagem pronta para edição"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed())) }
                scenario.onActivity { activity ->
                    val editor = activity.window.decorView.findViewWithTag<View>("editor_canvas")
                    val mapping = editor.javaClass.getDeclaredMethod("normalizedImageMatrix").apply { isAccessible = true }.invoke(editor) as Matrix
                    val point = floatArrayOf(0.5f, 0.91f)
                    mapping.mapPoints(point)
                    editor.javaClass.getDeclaredMethod("setDrawingColor", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(editor, Color.BLACK)
                    editor.javaClass.getDeclaredMethod("addText", String::class.java, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(editor, "TEXTO ADICIONADO", point[0], point[1], 30f)
                }
                awaitAndroidInputReady()
                onView(withContentDescription(R.string.action_recognize_text)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                awaitEditorOcrIdle(scenario)
                copyAllFromImage()
                scenario.onActivity {
                    val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("DOCUMENTO GALERIA"))
                    assertTrue("OCR deve reconhecer a edição, não só o arquivo original",
                        clipboard.primaryClip!!.getItemAt(0).text.contains("TEXTO ADICIONADO"))
                }
                androidx.test.espresso.Espresso.pressBack()
                onView(withContentDescription(R.string.ocr_select_on_image))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                scenario.onActivity {
                    org.junit.Assert.assertFalse("Voltar do OCR não pode sair do editor", it.isFinishing)
                    assertTrue(it.window.decorView.findViewWithTag<View>("editor_canvas").isEnabled)
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
                copyAllFromImage()
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

    private fun copyAllFromImage() {
        onView(withContentDescription(R.string.ocr_select_on_image))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        waitForUi { onView(withText(R.string.action_select_all))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed())) }
        onView(withText(R.string.action_select_all)).perform(click())
        waitForUi { onView(withText(R.string.ocr_copy_selection))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed())) }
        onView(withText(R.string.ocr_copy_selection)).perform(click())
        onView(withContentDescription(R.string.ocr_select_on_image))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
    }
}
