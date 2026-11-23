package com.galeria.android

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MotionFrameInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun editOpensFrameChoiceSaveKeepsAMotionPhotoAndOriginalComesBack() {
        val video = instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() }
        val still = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 220)) }
        val photo = ByteArrayOutputStream().also { still.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        still.recycle()
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description rdf:about=\"\" GCamera:MicroVideo=\"1\" " +
            "GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"${video.size}\" " +
            "GCamera:MotionPhotoPresentationTimestampUs=\"0\"/></rdf:RDF></x:xmpmeta>"
        val cameraPhoto = MotionFrameRules.withXmp(photo, xmp)
        val camera = cameraPhoto + video
        val suffix = System.nanoTime()
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "quadro-$suffix.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GaleriaFrameTest-$suffix/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(camera) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)

            // In the viewer, Edit on a Motion Photo opens straight on choosing the frame.
            val monitor = instrumentation.addMonitor(MotionFrameActivity::class.java.name, null, false)
            ActivityScenario.launch<DetailActivity>(ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java)).apply {
                putExtra("uri", uri.toString())
                putExtra("mime", "image/jpeg")
                putExtra("name", "quadro-$suffix.jpg")
            }).use {
                waitFor("O botão da foto em movimento deve aparecer") {
                    onView(withContentDescription(R.string.action_motion_photo)).check(matches(isDisplayed()))
                }
                onView(withContentDescription(R.string.action_edit)).perform(click())
                val frameScreen = instrumentation.waitForMonitorWithTimeout(monitor, 10_000L)
                assertNotNull("Editar uma foto em movimento deve abrir a escolha do quadro", frameScreen)
                frameScreen.finish()
                instrumentation.removeMonitor(monitor)
            }

            // Choose a later moment and save: the photo is replaced in place and stays a Motion Photo.
            ActivityScenario.launchActivityForResult<MotionFrameActivity>(frameIntent(uri)).use { scenario ->
                val strip = awaitStrip(scenario)
                scenario.onActivity { activity ->
                    assertEquals(android.view.View.INVISIBLE, activity.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<android.view.View>("motion_frame_original").visibility)
                    strip.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    strip.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                }
                onView(withTagValue(`is`("motion_frame_save"))).perform(click())
                awaitResult(scenario)
            }
            val edited = read(uri)
            val layout = MotionFrameRules.layout(edited)!!
            assertTrue("O arquivo deve guardar a foto original", layout.edited)
            assertTrue((layout.frameTimeUs ?: 0L) > 0L)
            assertArrayEquals("A foto original deve ficar intacta dentro do arquivo",
                cameraPhoto, edited.copyOfRange(layout.originalStart, layout.originalEnd))
            assertArrayEquals("O vídeo deve continuar idêntico no fim do arquivo", video, edited.copyOfRange(layout.tailStart, edited.size))
            val shown = BitmapFactory.decodeByteArray(edited, 0, layout.originalStart)
            assertNotNull("O quadro escolhido deve ser um JPEG válido", shown)
            val retriever = MediaMetadataRetriever()
            val clipFile = java.io.File(context.cacheDir, "frame-test-$suffix.mp4").apply { writeBytes(video) }
            try {
                retriever.setDataSource(clipFile.absolutePath)
                val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                assertEquals(setOf(videoWidth, videoHeight), setOf(shown.width, shown.height))
            } finally { retriever.release(); clipFile.delete() }
            shown.recycle()
            assertEquals("Depois de salvar, continua sendo foto em movimento",
                MotionPhotoClip(edited.size - video.size.toLong(), video.size.toLong()), MotionPhotoSupport.detect(context, uri))

            // Back to the original: exactly the camera's file again.
            ActivityScenario.launchActivityForResult<MotionFrameActivity>(frameIntent(uri)).use { scenario ->
                awaitStrip(scenario)
                onView(withTagValue(`is`("motion_frame_original"))).check(matches(isDisplayed())).perform(click())
                awaitResult(scenario)
            }
            assertArrayEquals("Voltar ao original deve devolver o arquivo da câmera", camera, read(uri))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun frameIntent(uri: Uri) = Intent(context, MotionFrameActivity::class.java).apply {
        putExtra(MotionFrameActivity.EXTRA_URI, uri.toString())
        putExtra(MotionFrameActivity.EXTRA_MIME, "image/jpeg")
    }

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun awaitStrip(scenario: ActivityScenario<MotionFrameActivity>): MotionFrameStripView {
        var strip: MotionFrameStripView? = null
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (strip == null && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                strip = activity.window.decorView.findViewWithTag<MotionFrameStripView>("motion_frame_strip")
                    ?.takeIf { it.isEnabled && it.durationMs > 0L }
            }
            if (strip == null) SystemClock.sleep(100L)
        }
        return requireNotNull(strip) { "A faixa de quadros não ficou pronta" }
    }

    private fun awaitResult(scenario: ActivityScenario<MotionFrameActivity>) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        assertEquals("A tela deve fechar depois de gravar", androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
    }

    private fun waitFor(message: String, check: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (true) {
            try { check(); return } catch (error: Throwable) {
                if (SystemClock.uptimeMillis() > deadline) throw AssertionError(message, error)
                SystemClock.sleep(100L)
            }
        }
    }
}
