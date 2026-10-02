package com.galeria.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
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

    @Test fun playPauseOverridesFingerStillOnTimelineWithoutRestartingOnRelease() {
        withCommandFixture { scenario ->
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { activity ->
                val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                player.play()
                touchTimeline(activity, down, MotionEvent.ACTION_DOWN, 0.8f)
                touchTimeline(activity, down, MotionEvent.ACTION_MOVE, 0.7f)
                assertFalse(player.playWhenReady)
                activity.window.decorView.findViewWithTag<View>("video_play_pause").performClick()
                assertFalse("Play/pause deve cancelar a intenção de retomar do arraste", player.playWhenReady)
                touchTimeline(activity, down, MotionEvent.ACTION_UP, 0.6f)
                assertFalse("Soltar o gesto antigo não pode dar play", player.playWhenReady)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                assertFalse(player.playWhenReady)
                activity.window.decorView.findViewWithTag<View>("video_play_pause").performClick()
                assertTrue("Um novo clique continua funcional", player.playWhenReady)
            }
        }
    }

    @Test fun backgroundDuringDragKeepsSamePlayerAndLateReleaseCannotUndoPause() {
        withCommandFixture { scenario ->
            val down = SystemClock.uptimeMillis()
            lateinit var original: Player
            scenario.onActivity { activity ->
                original = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                original.play()
                touchTimeline(activity, down, MotionEvent.ACTION_DOWN, 0.8f)
                touchTimeline(activity, down, MotionEvent.ACTION_MOVE, 0.7f)
                assertFalse(original.playWhenReady)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { assertFalse("Sem reprodução em segundo plano", original.playWhenReady) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val current = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                assertSame(original, current)
                assertTrue("A pausa temporária do arraste não perde a intenção de reprodução", current.playWhenReady)
                activity.window.decorView.findViewWithTag<View>("video_play_pause").performClick()
                assertFalse(current.playWhenReady)
                val position = current.currentPosition
                touchTimeline(activity, down, MotionEvent.ACTION_UP, 0.1f)
                assertFalse("UP antigo não pode desfazer a pausa explícita", current.playWhenReady)
                assertEquals("UP cancelado não deve buscar outra posição", position, current.currentPosition)
            }
        }
    }

    private fun touchTimeline(activity: DetailActivity, down: Long, action: Int, fraction: Float) {
        val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, strip.width * fraction, strip.height / 2f, 0)
        try { strip.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun withCommandFixture(test: (ActivityScenario<DetailActivity>) -> Unit) {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val keys = listOf("autoplay_videos", "loop_videos", "remember_video_position")
        val previous = prefs.all
        prefs.edit().putBoolean(keys[0], false).putBoolean(keys[1], true).putBoolean(keys[2], false).commit()
        val file = sample()
        try {
            ActivityScenario.launch<DetailActivity>(intent(file)).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        val current = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        ready = current.playbackState == Player.STATE_READY && current.duration > 0L
                    }
                    ready
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("video_timeline_toggle").performClick() }
                await {
                    var visible = false
                    scenario.onActivity {
                        val strip = it.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                        visible = strip.isShown && strip.width > 0
                    }
                    visible
                }
                test(scenario)
            }
        } finally {
            file.delete()
            prefs.edit().apply {
                keys.forEach { key -> if (previous.containsKey(key)) putBoolean(key, previous[key] as Boolean) else remove(key) }
            }.commit()
        }
    }

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
                    player.seekTo(0L)
                    val controls = activity.window.decorView.findViewWithTag<View>("video_playback_controls")
                    val play = activity.window.decorView.findViewWithTag<View>("video_play_pause")
                    assertTrue("Play/pause deve estar no centro da barra de reprodução",
                        controls.width > 0 && abs(play.x + play.width / 2f - controls.width / 2f) <= 1f)
                    val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                    assertEquals("A faixa só abre por escolha explícita", View.GONE, strip.visibility)
                    assertTrue(activity.window.decorView.findViewWithTag<SeekBar>("video_progress").isShown)
                    activity.window.decorView.findViewWithTag<View>("video_timeline_toggle").performClick()
                }
                await {
                    var ready = false
                    scenario.onActivity {
                        val strip = it.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                        ready = strip.isShown && strip.width > 0 && strip.positionMs < 100L
                        if (ready) {
                            val controls = it.window.decorView.findViewWithTag<View>("video_playback_controls")
                            val play = it.window.decorView.findViewWithTag<View>("video_play_pause")
                            assertTrue("Abrir a linha do tempo não deve deslocar play/pause",
                                abs(play.x + play.width / 2f - controls.width / 2f) <= 1f)
                        }
                    }
                    ready
                }
                val down = SystemClock.uptimeMillis()
                fun gesture(action: Int, fraction: Float) = scenario.onActivity { activity ->
                    val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, strip.width * fraction, strip.height / 2f, 0)
                    try { assertTrue(strip.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                var dragFraction = 0f
                scenario.onActivity { activity ->
                    val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                    dragFraction = (duration * 0.65 / 1000 * Ui.dp(activity, 54) / strip.width).toFloat()
                }
                gesture(MotionEvent.ACTION_DOWN, 0.8f)
                gesture(MotionEvent.ACTION_MOVE, 0.8f - dragFraction)
                await {
                    var matched = false
                    scenario.onActivity { activity ->
                        val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        matched = abs(player.currentPosition - (duration * 0.65).toLong()) < 150L
                        assertFalse(player.playWhenReady)
                    }
                    matched
                }
                gesture(MotionEvent.ACTION_UP, 0.8f - dragFraction)
                scenario.onActivity { activity ->
                    val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                    strip.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, Bundle().apply {
                        putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, duration / 1000f)
                    })
                }
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
            val results = ArrayList<List<Pair<Long, Bitmap>>>()
            repeat(2) {
                val ready = CountDownLatch(times.size)
                val received = ArrayList<Pair<Long, Bitmap>>()
                val source = VideoTimelineFrames(context, Uri.fromFile(file), "test")
                try {
                    source.request(times) { time, bitmap ->
                        received.add(time to bitmap)
                        ready.countDown()
                    }
                    assertTrue("As miniaturas não foram carregadas", ready.await(15, TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    // Ordered comparison, not a set: reversed delivery must fail.
                    assertEquals(times, received.map { it.first })
                    assertTrue(received.all { (_, bitmap) -> bitmap.width in 1..320 && bitmap.height in 1..320 })
                    results.add(received.toList())
                } finally {
                    source.close()
                }
            }
            times.indices.forEach { index ->
                assertSame("A segunda abertura deve reutilizar cada quadro do cache",
                    results[0][index].second, results[1][index].second)
            }
        } finally { file.delete() }
    }

    @Test fun continuousStripScrollsBySecondsAndCancelsWithoutChangingPosition() {
        var selected = 0L
        lateinit var strip: VideoTimelineView
        ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    strip = VideoTimelineView(activity)
                    activity.addContentView(strip, android.view.ViewGroup.LayoutParams(1000, 120))
                    strip.onScrubMove = { selected = it }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    strip.update(3_600_000L, 7_200_000L)
                    MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, strip.width * 0.5f, 60f, 0).let {
                        strip.dispatchTouchEvent(it); it.recycle()
                    }
                }
                scenario.onActivity {
                    MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, strip.width * 0.5f - Ui.dp(context, 54), 60f, 0).let {
                        strip.dispatchTouchEvent(it); it.recycle()
                    }
                    assertEquals(3_601_000L, selected)
                    strip.cancelGesture()
                    assertEquals(3_600_000L, strip.positionMs)
                    strip.update(3_600_000L, 7_200_000L)
                    strip.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    assertEquals(3_601_000L, selected)
                }
        }
    }

    @Test fun playbackBarSeeksWithFilmstripClosedAndToggleKeepsBothAvailable() {
        val file = sample()
        try {
            ActivityScenario.launch<DetailActivity>(intent(file)).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity {
                        val player = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        ready = player.playbackState == Player.STATE_READY &&
                            it.window.decorView.findViewWithTag<SeekBar>("video_progress").isEnabled
                    }
                    ready
                }
                var target = 0L
                scenario.onActivity { activity ->
                    val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                    player.pause()
                    target = player.duration / 2L
                    val bar = activity.window.decorView.findViewWithTag<SeekBar>("video_progress")
                    assertEquals(View.GONE, activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline").visibility)
                    assertTrue(bar.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, Bundle().apply {
                        putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, bar.max / 2f)
                    }))
                }
                await {
                    var matched = false
                    scenario.onActivity {
                        val player = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                        matched = abs(player.currentPosition - target) < 100L && !player.playWhenReady &&
                            it.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline").isPrepared
                    }
                    matched
                }
                scenario.onActivity { activity ->
                    val toggle = activity.window.decorView.findViewWithTag<View>("video_timeline_toggle")
                    val strip = activity.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline")
                    assertEquals("A preparação ocorre antes da abertura", View.GONE, strip.visibility)
                    assertTrue("O trecho visível já deve estar preenchido", strip.isPrepared)
                    toggle.performClick()
                    assertEquals(View.VISIBLE, strip.visibility)
                    assertTrue(activity.window.decorView.findViewWithTag<SeekBar>("video_progress").isShown)
                    toggle.performClick()
                    assertEquals(View.GONE, strip.visibility)
                }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(View.GONE, it.window.decorView.findViewWithTag<VideoTimelineView>("video_timeline").visibility)
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
