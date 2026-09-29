package com.galeria.android

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
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
        }).use {
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
                    ready = state == Player.STATE_READY || state == Player.STATE_ENDED
                }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("O vídeo embutido não ficou pronto para reprodução", ready)
        }
    }
}
