package com.galeria.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class DetailPictureInPictureInstrumentedTest {

    @Test
    fun backWhilePlayingKeepsTheVideoInAFloatingThumbnailWithWorkingPlayPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        withLoopingVideo(context) { scenario ->
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitUntil("A tela deve virar miniatura") { scenario.inPictureInPicture() }
            waitUntil("As barras do player devem sumir na miniatura") {
                var hidden = false
                scenario.onActivity { hidden = it.privateView("topBar").visibility == View.GONE }
                hidden
            }
            scenario.onActivity { activity ->
                assertFalse("Voltar com vídeo tocando não fecha o player", activity.isFinishing)
                assertEquals("A barra superior some na miniatura", View.GONE, activity.privateView("topBar").visibility)
                assertEquals("A barra inferior some na miniatura", View.GONE, activity.privateView("bottomBar").visibility)
                assertTrue("O vídeo continua tocando na miniatura", activity.player().playWhenReady)
            }

            waitUntil("O botão da miniatura deve ficar pronto") {
                var registered = false
                scenario.onActivity {
                    registered = DetailActivity::class.java.getDeclaredField("pictureInPictureReceiver")
                        .apply { isAccessible = true }.get(it) != null
                }
                registered
            }
            // Same PendingIntent the thumbnail button fires through the system.
            val toggle = android.app.PendingIntent.getBroadcast(
                context,
                0,
                Intent("com.galeria.android.PICTURE_IN_PICTURE_TOGGLE").setPackage(context.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            toggle.send()
            waitUntil("O botão da miniatura deve pausar") { scenario.playWhenReady() == false }
            toggle.send()
            waitUntil("O botão da miniatura deve voltar a tocar") { scenario.playWhenReady() == true }
        }
    }

    @Test
    fun backWithThePausedVideoStillClosesTheViewer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withLoopingVideo(context) { scenario ->
            scenario.onActivity { it.player().pause() }
            waitUntil("O vídeo deve pausar") { scenario.playWhenReady() == false }
            var finishing = false
            var inPip = true
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                finishing = it.isFinishing
                inPip = it.isInPictureInPictureMode
            }
            assertTrue("Vídeo pausado fecha normalmente", finishing)
            assertFalse("Vídeo pausado não vira miniatura", inPip)
        }
    }

    private fun withLoopingVideo(context: Context, block: (ActivityScenario<DetailActivity>) -> Unit) {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hadLoop = prefs.contains("loop_videos")
        val oldLoop = prefs.getBoolean("loop_videos", false)
        val hadAutoplay = prefs.contains("autoplay_videos")
        val oldAutoplay = prefs.getBoolean("autoplay_videos", true)
        prefs.edit().putBoolean("loop_videos", true).putBoolean("autoplay_videos", true).commit()
        val file = File(context.cacheDir, "pip-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", Uri.fromFile(file).toString())
                putExtra("mime", "video/mp4")
                putExtra("name", file.name)
            }).use { scenario ->
                waitUntil("O vídeo real deve começar") {
                    var ready = false
                    scenario.onActivity {
                        val player = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player")?.player
                        ready = player?.playbackState == Player.STATE_READY && player.playWhenReady
                    }
                    ready
                }
                block(scenario)
            }
        } finally {
            file.delete()
            prefs.edit().apply {
                if (hadLoop) putBoolean("loop_videos", oldLoop) else remove("loop_videos")
                if (hadAutoplay) putBoolean("autoplay_videos", oldAutoplay) else remove("autoplay_videos")
            }.commit()
        }
    }

    private fun ActivityScenario<DetailActivity>.inPictureInPicture(): Boolean {
        var result = false
        onActivity { result = it.isInPictureInPictureMode }
        return result
    }

    private fun ActivityScenario<DetailActivity>.playWhenReady(): Boolean? {
        var result: Boolean? = null
        onActivity { result = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player")?.player?.playWhenReady }
        return result
    }

    private fun DetailActivity.player(): Player =
        window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!

    private fun DetailActivity.privateView(name: String): View =
        DetailActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as View

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }
}
