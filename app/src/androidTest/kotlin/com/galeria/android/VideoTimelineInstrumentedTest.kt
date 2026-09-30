package com.galeria.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VideoTimelineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun dragSeeksBeforeFingerIsReleasedAndRestoresPlaybackState() {
        val file = sample()
        try {
            ActivityScenario.launch<DetailActivity>(intent(file)).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        ready = player.playbackState == Player.STATE_READY && player.duration > 0L
                    }
                    ready
                }
                var duration = 0L
                scenario.onActivity { activity ->
                    val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                    duration = player.duration
                    player.pause()
                }
                val down = SystemClock.uptimeMillis()
                fun gesture(action: Int, fraction: Float) = scenario.onActivity { activity ->
                    val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, strip.width * fraction, strip.height / 2f, 0)
                    try { assertTrue(strip.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                gesture(MotionEvent.ACTION_DOWN, 0.15f)
                gesture(MotionEvent.ACTION_MOVE, 0.65f)
                await {
                    var matched = false
                    scenario.onActivity { activity ->
                        val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        matched = abs(player.currentPosition - (duration * 0.65).toLong()) < 150L
                        assertFalse(player.playWhenReady)
                    }
                    matched
                }
                gesture(MotionEvent.ACTION_UP, 0.65f)
                gesture(MotionEvent.ACTION_DOWN, 1f)
                gesture(MotionEvent.ACTION_UP, 1f)
                Thread.sleep(350L)
                scenario.onActivity { activity ->
                    val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                    assertTrue("Prévia do fim não deve voltar sozinha ao início", player.currentPosition >= duration - 100L)
                }
                scenario.onActivity { activity ->
                    val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                    assertFalse("Um vídeo pausado deve continuar pausado após o arraste", player.playWhenReady)
                    player.play()
                }
                gesture(MotionEvent.ACTION_DOWN, 0.2f)
                gesture(MotionEvent.ACTION_UP, 0.2f)
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!.playWhenReady)
                }
                await {
                    var synchronized = false
                    scenario.onActivity { activity ->
                        val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                        synchronized = abs(strip.positionMs - player.currentPosition) <= 200L
                    }
                    synchronized
                }
                await {
                    var rendered = false
                    scenario.onActivity { activity ->
                        val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                        val bitmap = Bitmap.createBitmap(strip.width, strip.height, Bitmap.Config.ARGB_8888)
                        strip.draw(Canvas(bitmap))
                        val colors = HashSet<Int>()
                        for (x in 10 until bitmap.width step 10) colors.add(bitmap.getPixel(x, bitmap.height / 2))
                        rendered = colors.size > 5
                        bitmap.recycle()
                    }
                    rendered
                }
                scenario.recreate()
                await {
                    var visible = false
                    scenario.onActivity { visible = it.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline").durationMs > 0 }
                    visible
                }
            }
        } finally { file.delete() }
    }

    @Test fun thumbnailsAreBoundedReusableAndChronological() {
        val file = sample()
        try {
            val times = listOf(0L, 250L, 500L)
            val results = ArrayList<Bitmap>()
            repeat(2) {
                val ready = CountDownLatch(times.size)
                val seen = HashSet<Long>()
                val source = VideoTimelineFrames(context, Uri.fromFile(file), "test")
                source.request(times) { time, bitmap ->
                    assertTrue(bitmap.width <= 320 && bitmap.height <= 320)
                    if (seen.add(time)) { results.add(bitmap); ready.countDown() }
                }
                assertTrue("As miniaturas não foram carregadas", ready.await(15, TimeUnit.SECONDS))
                source.close()
                assertEquals(times.toSet(), seen)
            }
            assertEquals(6, results.size)
            assertSame("A segunda abertura deve reutilizar o cache", results[0], results[3])
        } finally { file.delete() }
    }

    @Test fun longPressAllowsFineSeekingWithoutOneThumbnailPerSecond() {
        var selected = 0L
        lateinit var strip: VideoTimelineView
        val file = sample()
        try {
            ActivityScenario.launch<DetailActivity>(intent(file)).use { scenario ->
                scenario.onActivity { activity ->
                    strip = VideoTimelineView(activity)
                    activity.addContentView(strip, android.view.ViewGroup.LayoutParams(1000, 120))
                    strip.onScrubMove = { selected = it }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    strip.update(0L, 7_200_000L)
                    MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, strip.width * 0.5f, 60f, 0).let {
                        strip.dispatchTouchEvent(it); it.recycle()
                    }
                }
                Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100L)
                scenario.onActivity {
                    MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, strip.width * 0.55f, 60f, 0).let {
                        strip.dispatchTouchEvent(it); it.recycle()
                    }
                    assertEquals(3_601_000L, selected)
                    strip.cancelGesture()
                    strip.update(3_600_000L, 7_200_000L)
                    strip.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    assertEquals(3_601_000L, selected)
                }
            }
        } finally { file.delete() }
    }

    @Test fun movingAwayAndBackReloadsEveryVisibleFrame() {
        val file = sample()
        val source = VideoTimelineFrames(context, Uri.fromFile(file), "viewport-race")
        try {
            val ready = CountDownLatch(1)
            val seen = HashSet<Long>()
            val times = listOf(0L, 250L, 500L)
            var switched = false
            source.request(times) { _, _ ->
                if (!switched) {
                    switched = true
                    source.request(listOf(750L, 1000L)) { _, _ -> }
                    source.request(times) { time, _ ->
                        seen.add(time)
                        if (seen.size == times.size) ready.countDown()
                    }
                }
            }
            assertTrue("Voltar ao trecho anterior deve repor todas as miniaturas", ready.await(15, TimeUnit.SECONDS))
        } finally { source.close(); file.delete() }
    }

    private fun intent(file: File) = Intent(context, DetailActivity::class.java).apply {
        putExtra("uri", Uri.fromFile(file).toString())
        putExtra("mime", "video/mp4")
        putExtra("name", file.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun sample() = File(context.cacheDir, "timeline-${System.nanoTime()}.mp4").also { file ->
        instrumentation.context.assets.open("playback-sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (!check()) {
            assertTrue("A condição da linha do tempo não foi atingida", SystemClock.uptimeMillis() < deadline)
            Thread.sleep(100L)
        }
    }
}
