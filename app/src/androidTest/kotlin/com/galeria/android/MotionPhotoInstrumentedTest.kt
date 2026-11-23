package com.galeria.android

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class MotionPhotoInstrumentedTest {
    @Test fun cancelledScanDoesNotPersistAndNegativeDetectionIsReused() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("motion_photo_detection_v1", android.content.Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val photo = File(context.cacheDir, "motion-negative-${System.nanoTime()}.jpg")
        try {
            photo.outputStream().use { output -> repeat(32) { output.write(ByteArray(64 * 1024) { 42 }) } }
            val uri = Uri.fromFile(photo)
            var cancelledChecks = 0
            assertEquals(null, MotionPhotoSupport.detect(context, uri) { ++cancelledChecks < 3 })
            assertEquals(previous, preferences.all)
            var scanChecks = 0
            assertEquals(null, MotionPhotoSupport.detect(context, uri) { scanChecks++; true })
            assertTrue("Varredura deve percorrer vários blocos", scanChecks > 10)
            var cachedChecks = 0
            assertEquals(null, MotionPhotoSupport.detect(context, uri) { cachedChecks++; true })
            assertTrue("Resultado negativo deve evitar nova varredura", cachedChecks <= 2)
        } finally {
            photo.delete()
            preferences.edit().clear().apply {
                previous.forEach { (key, value) -> putString(key, value as String) }
            }.commit()
        }
    }

    @Test fun detectsAndExtractsEmbeddedVideoWithoutChangingPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "motion_instrumented_test.jpg")
        val still = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        photo.outputStream().use { still.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        still.recycle()
        val offset = photo.length()
        val marker = "MotionPhoto_Data".toByteArray()
        FileOutputStream(photo, true).use { output ->
            output.write(marker)
            InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input -> input.copyTo(output) }
        }
        val sizeBefore = photo.length()
        val clip = MotionPhotoSupport.detect(context, Uri.fromFile(photo))
        assertNotNull(clip)
        assertEquals(offset + marker.size, clip!!.offset)
        val extracted = MotionPhotoSupport.cachedClip(context, Uri.fromFile(photo), clip)
        assertEquals(clip.length, extracted.length())
        assertTrue(extracted.length() > 100L)
        assertEquals(sizeBefore, photo.length())

        ActivityScenario.launch<DetailActivity>(ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java)).apply {
            putExtra("uri", Uri.fromFile(photo).toString())
            putExtra("name", photo.name)
            putExtra("mime", "image/jpeg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }).use { scenario ->
            var originalIndex = -1
            var originalUri = ""
            scenario.onActivity { activity ->
                val queue = DetailActivity::class.java.getDeclaredField("queueController")
                    .apply { isAccessible = true }.get(activity) as DetailMediaQueueController
                originalIndex = queue.currentIndex
                originalUri = queue.items[queue.currentIndex].uri.toString()
            }
            val deadline = System.currentTimeMillis() + 10_000L
            while (true) {
                val visible = runCatching {
                    onView(withContentDescription(R.string.action_motion_photo)).check(matches(isDisplayed()))
                }.isSuccess
                if (visible) break
                assertTrue("A ação de Motion Photo não apareceu", System.currentTimeMillis() < deadline)
                Thread.sleep(100)
            }
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val button = root.findViewWithTag<android.view.View>("viewer_motion_photo")
                val overlay = root.findViewWithTag<android.view.View>("motion_photo_overlay")
                val bar = root.findViewWithTag<android.view.ViewGroup>("viewer_actions")
                assertTrue("Motion Photo deve ficar sobre a imagem, fora da barra", button.parent === overlay)
                val overlayPosition = IntArray(2).also(overlay::getLocationOnScreen)
                val barPosition = IntArray(2).also(bar::getLocationOnScreen)
                assertTrue(overlayPosition[1] + overlay.height <= barPosition[1])
                assertEquals(5, (0 until bar.childCount).count { bar.getChildAt(it).visibility == android.view.View.VISIBLE })
            }
            fun awaitMotionControls(visible: Boolean) {
                val deadline = SystemClock.uptimeMillis() + 6_000L
                var settled = false
                while (!settled && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val overlay = activity.window.decorView.findViewWithTag<android.view.View>("motion_photo_overlay")
                        settled = if (visible) overlay.isShown && overlay.alpha >= 0.99f else !overlay.isShown
                    }
                    if (!settled) SystemClock.sleep(50L)
                }
                assertTrue("O gesto deve alternar também o atalho flutuante de Motion Photo", settled)
            }
            onView(withContentDescription("Visualizador de mídia")).perform(click())
            // Single taps wait for the double-tap window and then fade the HUD.
            awaitMotionControls(false)
            onView(withContentDescription(R.string.action_motion_photo)).check(matches(org.hamcrest.Matchers.not(isDisplayed())))
            onView(withContentDescription("Visualizador de mídia")).perform(click())
            awaitMotionControls(true)
            onView(withContentDescription(R.string.action_motion_photo)).check(matches(isDisplayed()))
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                File(context.getExternalFilesDir(null), "motion-photo-overlay-qa.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            // The footer button plays the clip on the photo itself: no other screen opens.
            fun surface(activity: DetailActivity) =
                activity.window.decorView.findViewWithTag<android.view.TextureView>(InlineMotionPhotoPlayer.SURFACE_TAG)
            fun awaitSurface(present: Boolean, message: String) {
                val deadline = SystemClock.uptimeMillis() + 10_000L
                var settled = false
                while (!settled && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val view = surface(activity)
                        settled = if (present) view != null && view.alpha >= 0.99f else view == null
                    }
                    if (!settled) SystemClock.sleep(50L)
                }
                assertTrue(message, settled)
            }
            onView(withContentDescription(R.string.action_motion_photo)).perform(click())
            awaitSurface(true, "O vídeo da foto em movimento deve aparecer sobre a própria foto")
            assertEquals("O visualizador deve continuar em primeiro plano", Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                val view = surface(activity)
                val page = view.parent as android.view.ViewGroup
                val image = page.getChildAt(page.indexOfChild(view) - 1)
                assertTrue("O vídeo deve ficar logo acima da foto ampliável, na mesma página",
                    image is com.github.panpf.zoomimage.CoilZoomImageView)
                image as android.widget.ImageView
                assertTrue("O vídeo não deve receber toques", !view.isClickable)
                // The clip lands inside the photo's on-screen rectangle (same place and zoom).
                val drawable = image.drawable
                val photo = android.graphics.RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
                image.imageMatrix.mapRect(photo)
                val clipRect = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                view.getTransform(null).mapRect(clipRect)
                val tolerance = 2f
                assertTrue("O vídeo deve ocupar o retângulo da foto: $clipRect em $photo",
                    clipRect.left >= photo.left - tolerance && clipRect.top >= photo.top - tolerance &&
                        clipRect.right <= photo.right + tolerance && clipRect.bottom <= photo.bottom + tolerance &&
                        (clipRect.width() >= photo.width() - tolerance || clipRect.height() >= photo.height() - tolerance))
                val button = activity.window.decorView.findViewWithTag<android.widget.ImageButton>("viewer_motion_photo")
                assertEquals(activity.getString(R.string.action_motion_photo_stop), button.contentDescription)
            }
            // The same button stops it, and the photo is back.
            onView(withContentDescription(R.string.action_motion_photo_stop)).perform(click())
            awaitSurface(false, "Parar deve devolver a foto")
            onView(withContentDescription(R.string.action_motion_photo)).check(matches(isDisplayed()))
            // Played to the end, the clip fades back to the photo by itself.
            onView(withContentDescription(R.string.action_motion_photo)).perform(click())
            awaitSurface(true, "A foto em movimento deve tocar de novo")
            val endDeadline = SystemClock.uptimeMillis() + 20_000L
            var ended = false
            while (!ended && SystemClock.uptimeMillis() < endDeadline) {
                scenario.onActivity { ended = surface(it) == null }
                if (!ended) SystemClock.sleep(100L)
            }
            assertTrue("Ao terminar, o vídeo deve voltar sozinho para a foto", ended)
            onView(withContentDescription(R.string.action_motion_photo)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val queue = DetailActivity::class.java.getDeclaredField("queueController")
                    .apply { isAccessible = true }.get(activity) as DetailMediaQueueController
                assertEquals("Motion Photo não deve avançar a fila da galeria", originalIndex, queue.currentIndex)
                assertEquals(originalUri, queue.items[queue.currentIndex].uri.toString())
            }
            // Leaving the viewer while it plays stops it; coming back shows the photo.
            onView(withContentDescription(R.string.action_motion_photo)).perform(click())
            awaitSurface(true, "A foto em movimento deve tocar antes de sair")
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { assertEquals(null, surface(it)) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(null, surface(activity))
                assertEquals(activity.getString(R.string.action_motion_photo),
                    activity.window.decorView.findViewWithTag<android.widget.ImageButton>("viewer_motion_photo").contentDescription)
            }
        }
    }

    @Test fun metadataNamesTheClipWithoutScanningTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "motion-xmp-${System.nanoTime()}.jpg")
        try {
            val still = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val jpeg = java.io.ByteArrayOutputStream().also { still.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
            still.recycle()
            val video = InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { it.readBytes() }
            // MicroVideo XMP: the clip is the last bytes of the file, as Google cameras write it.
            val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description GCamera:MicroVideo=\"1\" " +
                "GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"${video.size}\"/></rdf:RDF></x:xmpmeta>"
            val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray()
            val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
            photo.outputStream().use { output ->
                output.write(jpeg, 0, 2)
                output.write(segment)
                output.write(jpeg, 2, jpeg.size - 2)
                output.write(video)
            }
            var checks = 0
            val clip = MotionPhotoSupport.detect(context, Uri.fromFile(photo)) { checks++; true }
            assertEquals(MotionPhotoClip(photo.length() - video.size, video.size.toLong()), clip)
            assertTrue("Os metadados devem dispensar a varredura do arquivo ($checks verificações)", checks <= 3)
        } finally {
            photo.delete()
        }
    }
}
