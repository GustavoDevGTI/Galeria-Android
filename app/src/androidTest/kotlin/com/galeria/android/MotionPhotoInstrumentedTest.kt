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
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.Press
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
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

        ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
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
            onView(withContentDescription(R.string.action_motion_photo)).perform(click())
            onView(withText(R.string.album_back)).check(matches(isDisplayed()))
            awaitAndroidInputReady()
            onView(withText(R.string.album_back)).perform(GeneralClickAction(Tap.SINGLE, { view ->
                val location = IntArray(2).also(view::getLocationOnScreen)
                // Tap the visible label, not the status-bar padding included in
                // this edge-to-edge TextView's bounding box. Still inject touch.
                floatArrayOf(location[0] + view.paddingLeft + (view.width - view.paddingLeft - view.paddingRight) / 2f,
                    location[1] + view.paddingTop + (view.height - view.paddingTop - view.paddingBottom) / 2f)
            }, Press.FINGER, null))
            val returnDeadline = SystemClock.uptimeMillis() + 10_000L
            var returned = false
            while (!returned && SystemClock.uptimeMillis() < returnDeadline) {
                if (scenario.state == Lifecycle.State.RESUMED) {
                    scenario.onActivity { returned = it.hasWindowFocus() }
                }
                if (!returned) SystemClock.sleep(50L)
            }
            assertTrue("Voltar da Motion Photo deve retomar o visualizador original", returned)
            awaitAndroidInputReady()
            onView(withContentDescription(R.string.action_motion_photo)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val queue = DetailActivity::class.java.getDeclaredField("queueController")
                    .apply { isAccessible = true }.get(activity) as DetailMediaQueueController
                assertEquals("Motion Photo não deve avançar a fila da galeria", originalIndex, queue.currentIndex)
                assertEquals(originalUri, queue.items[queue.currentIndex].uri.toString())
            }
        }

        ActivityScenario.launch<MotionPhotoActivity>(Intent(context, MotionPhotoActivity::class.java).apply {
            putExtra(MotionPhotoActivity.EXTRA_URI, Uri.fromFile(photo).toString())
            putExtra(MotionPhotoActivity.EXTRA_OFFSET, clip.offset)
            putExtra(MotionPhotoActivity.EXTRA_LENGTH, clip.length)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }).use { scenario ->
            val deadline = System.currentTimeMillis() + 10_000L
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val state = activity.window.decorView.findViewWithTag<PlayerView>("motion_photo_player")
                        ?.player?.playbackState
                    ready = (state == Player.STATE_READY || state == Player.STATE_ENDED) &&
                        activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline").isPrepared
                }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("O vídeo embutido não ficou pronto para reprodução", ready)
            scenario.onActivity { activity ->
                val controls = activity.window.decorView.findViewWithTag<android.view.View>("video_playback_controls")
                val play = activity.window.decorView.findViewWithTag<android.view.View>("video_play_pause")
                assertTrue("Play/pause da Motion Photo deve seguir o alinhamento central dos vídeos",
                    controls.width > 0 && kotlin.math.abs(play.x + play.width / 2f - controls.width / 2f) <= 1f)
                val timeline = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                assertNotNull("Motion Photo deve ter a mesma linha do tempo", timeline)
                assertEquals(android.view.View.GONE, timeline.visibility)
                activity.window.decorView.findViewWithTag<android.view.View>("video_timeline_toggle").performClick()
                assertEquals(android.view.View.VISIBLE, timeline.visibility)
                val player = activity.window.decorView.findViewWithTag<PlayerView>("motion_photo_player").player!!
                player.pause()
                timeline.update(0L, player.duration)
                timeline.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                assertTrue(player.currentPosition > 0L)
            }
            var ownedPlayer: Player? = null
            scenario.onActivity { activity ->
                ownedPlayer = activity.window.decorView.findViewWithTag<PlayerView>("motion_photo_player").player
                ownedPlayer!!.play()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                assertTrue("Motion Photo deve pausar em segundo plano", ownedPlayer?.playWhenReady == false)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                org.junit.Assert.assertSame(ownedPlayer,
                    activity.window.decorView.findViewWithTag<PlayerView>("motion_photo_player").player)
                assertTrue("Retornar não deve forçar reprodução que ficou pausada", ownedPlayer?.playWhenReady == false)
            }
        }
    }
}
