package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackResumeInstrumentedTest {
    @Test
    fun decodedVideoAndTimelineContinueAfterReturningFromBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val originalPreferences = prefs.all
        prefs.edit().putBoolean("autoplay_videos", true)
            .putBoolean("loop_videos", true).putBoolean("remember_video_position", false).commit()
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "playback-resume-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GaleriaPlaybackTest/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", uri.toString())
                putExtra("name", "playback-resume.mp4")
                putExtra("mime", "video/mp4")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
                waitUntil {
                    var playing = false
                    scenario.onActivity { activity ->
                        val player = find<PlayerView>(activity.window.decorView)?.player
                        playing = player?.isPlaying == true && player.videoSize.width > 0
                    }
                    playing
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val progressValues = mutableSetOf<Int>()
                waitUntil {
                    var advancing = false
                    scenario.onActivity { activity ->
                        val progress = requireNotNull(find<SeekBar>(activity.window.decorView)).progress
                        val player = requireNotNull(find<PlayerView>(activity.window.decorView)).player
                        if (player?.isPlaying == true) progressValues.add(progress)
                        advancing = progressValues.size >= 3
                    }
                    advancing
                }
            }
        } finally {
            resolver.delete(uri, null, null)
            prefs.edit().apply {
                for (key in listOf("autoplay_videos", "loop_videos", "remember_video_position")) {
                    if (originalPreferences.containsKey(key)) putBoolean(key, originalPreferences[key] as Boolean)
                    else remove(key)
                }
            }.commit()
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue("A reprodução real e a timeline deveriam avançar após onResume", condition())
    }

    private inline fun <reified T : View> find(root: View): T? =
        descendants(root).filterIsInstance<T>().firstOrNull()

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
