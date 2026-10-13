package com.galeria.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.panpf.zoomimage.CoilZoomImageView
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrImageSelectionInstrumentedTest {
    @Test fun touchingAWordCopiesOnlyThatWordWithoutReplacingThePhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File.createTempFile("ocr-select-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1400, 700, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("ALFA BETA GALERIA", 80f, 320f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 110f })
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val original = photo.readBytes()
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", android.net.Uri.fromFile(photo).toString())
                putExtra("mime", "image/png"); putExtra("name", photo.name)
            }).use { scenario ->
                awaitViewerOcrIdle(scenario)
                onView(withContentDescription(R.string.ocr_text_available)).perform(click())
                awaitViewerOcrIdle(scenario)
                onView(withContentDescription(R.string.ocr_select_on_image)).perform(touchWords("BETA"))
                onView(withText(R.string.ocr_copy_selection)).perform(click())
                scenario.onActivity { activity ->
                    val overlay = activity.window.decorView.findViewWithTag<OcrSelectionOverlay>("ocr_image_selection")
                    assertEquals("BETA", overlay.selectedText)
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals("BETA", clipboard.primaryClip!!.getItemAt(0).text.toString())
                    val image = children(overlay.parent as View).filterIsInstance<CoilZoomImageView>().single()
                    assertNotNull(image.drawable)
                    assertEquals(1f, image.alpha)
                    val beta = overlay.document.words.first { it.text == "BETA" }
                    val centerX = beta.corners.sumOf { it.x.toDouble() }.toFloat() / 4f
                    val centerY = beta.corners.sumOf { it.y.toDouble() }.toFloat() / 4f
                    assertTrue("A caixa deve corresponder à palavra desenhada", centerX in 0.25f..0.50f && centerY in 0.28f..0.48f)
                }
                captureLoadingFeedback("ocr-on-image-qa.png")
                var originalWordWidth = 0f
                scenario.onActivity { activity ->
                    val overlay = activity.window.decorView.findViewWithTag<OcrSelectionOverlay>("ocr_image_selection")
                    originalWordWidth = overlay.wordBounds(overlay.document.words.indexOfFirst { it.text == "BETA" }).width()
                }
                onView(withContentDescription(R.string.ocr_select_on_image)).perform(pinchImage())
                scenario.onActivity { activity ->
                    val overlay = activity.window.decorView.findViewWithTag<OcrSelectionOverlay>("ocr_image_selection")
                    val image = children(overlay.parent as View).filterIsInstance<CoilZoomImageView>().single()
                    assertTrue("O zoom real deve ampliar também a caixa OCR",
                        overlay.wordBounds(overlay.document.words.indexOfFirst { it.text == "BETA" }).width() > originalWordWidth * 1.2f)
                    assertTrue(image.zoomable.transformState.value.scaleX > image.zoomable.minScaleState.value * 1.2f)
                }
                onView(withContentDescription(R.string.ocr_text_available)).perform(click())
                onView(withContentDescription(R.string.ocr_select_on_image))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
            assertArrayEquals(original, photo.readBytes())
        } finally { photo.delete() }
    }

    @Test fun continuousDragSelectsWordsAndGeometryTracksTheImageTransform() {
        ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
            lateinit var overlay: OcrSelectionOverlay
            lateinit var parent: FrameLayout
            var matrix = Matrix().apply { setScale(500f, 500f); postTranslate(40f, 80f) }
            val document = OcrDocument("UM DOIS TRES", listOf("UM", "DOIS", "TRES").mapIndexed { i, text ->
                val left = 0.08f + i * 0.25f
                OcrWord(text, listOf(OcrPoint(left, 0.2f), OcrPoint(left + 0.20f, 0.2f),
                    OcrPoint(left + 0.20f, 0.3f), OcrPoint(left, 0.3f)), 0, 0)
            })
            scenario.onActivity { activity ->
                parent = FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(ImageView(activity), FrameLayout.LayoutParams(-1, -1))
                }
                activity.setContentView(parent)
                overlay = OcrSelectionOverlay(activity, document, { matrix })
                overlay.attachTo(parent)
            }
            onView(withContentDescription(R.string.ocr_select_on_image)).perform(touchWords("UM", "TRES"))
            scenario.onActivity {
                assertEquals("UM DOIS TRES", overlay.selectedText)
                val before = overlay.wordBounds(1)
                matrix = Matrix().apply { setScale(1000f, 1000f); postTranslate(-60f, 10f) }
                val after = overlay.wordBounds(1)
                assertEquals(before.width() * 2, after.width(), 0.1f)
                assertEquals(270f, after.left, 0.1f)
                overlay.close()
                assertEquals("A imagem original deve continuar no stage", 1, parent.childCount)
            }
        }
    }

    private fun touchWords(start: String, end: String = start): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription() = "selecionar palavras sobre a imagem com touch real"
        override fun perform(controller: UiController, view: View) {
            val overlay = view as OcrSelectionOverlay
            val first = overlay.document.words.indexOfFirst { it.text == start }
            val last = overlay.document.words.indexOfFirst { it.text == end }
            assertTrue(first >= 0 && last >= 0)
            val from = overlay.wordBounds(first); val to = overlay.wordBounds(last)
            val location = IntArray(2).also(view::getLocationOnScreen)
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int, point: PointF) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    location[0] + point.x, location[1] + point.y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(controller.injectMotionEvent(event)) } finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN, PointF(from.centerX(), from.centerY()))
            controller.loopMainThreadForAtLeast(40)
            inject(MotionEvent.ACTION_MOVE, PointF(to.centerX(), to.centerY()))
            inject(MotionEvent.ACTION_UP, PointF(to.centerX(), to.centerY()))
            controller.loopMainThreadUntilIdle()
        }
    }

    private fun pinchImage(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription() = "ampliar a foto com dois dedos durante a seleção OCR"
        override fun perform(controller: UiController, view: View) {
            val location = IntArray(2).also(view::getLocationOnScreen)
            val cx = location[0] + view.width / 2f
            val cy = location[1] + view.height / 2f
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, span: Float, count: Int) {
                val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = cx + if (i == 0) -span / 2 else span / 2; y = cy; pressure = 1f; size = 1f
                } }
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(controller.injectMotionEvent(motion)) } finally { motion.recycle() }
                controller.loopMainThreadForAtLeast(24)
            }
            val initial = view.width * 0.20f
            event(MotionEvent.ACTION_DOWN, initial, 1)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), initial, 2)
            for (step in 1..10) event(MotionEvent.ACTION_MOVE, initial * (1 + step * 0.10f), 2)
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), initial * 2, 2)
            event(MotionEvent.ACTION_UP, initial * 2, 1)
            controller.loopMainThreadForAtLeast(300)
        }
    }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
