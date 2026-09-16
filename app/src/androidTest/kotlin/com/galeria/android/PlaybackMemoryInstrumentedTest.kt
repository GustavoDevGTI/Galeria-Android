package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackMemoryInstrumentedTest {
    @Test
    fun expiredPositionIsDiscardedWhenVideoOpens() = withPlaybackVideo { context, uri ->
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val positionKey = "video_pos_${uri.hashCode()}"
        val savedAtKey = "${positionKey}_saved_at"
        prefs.edit()
            .putLong(positionKey, 1_000L)
            .putLong(savedAtKey, System.currentTimeMillis() - DetailPlaybackRules.RESUME_RETENTION_MS - 1L)
            .commit()

        launchVideo(context, uri).use { scenario ->
            waitUntil {
                var startedAtBeginning = false
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)?.player
                    startedAtBeginning = player?.playbackState == Player.STATE_READY &&
                        player.duration > 0L && player.currentPosition < 250L
                }
                startedAtBeginning
            }
        }

        assertFalse(prefs.contains(positionKey))
        assertFalse(prefs.contains(savedAtKey))
    }

    @Test
    fun completedVideoIsPausedAtBeginningAndForgetsItsPosition() = withPlaybackVideo { context, uri ->
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val positionKey = "video_pos_${uri.hashCode()}"
        val savedAtKey = "${positionKey}_saved_at"

        launchVideo(context, uri).use { scenario ->
            waitUntil {
                var ready = false
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)?.player
                    ready = player?.playbackState == Player.STATE_READY && player.duration > 500L
                }
                ready
            }
            scenario.onActivity { activity ->
                val player = requireNotNull(find<PlayerView>(activity.window.decorView)?.player)
                player.seekTo(player.duration - 200L)
                player.play()
            }
            waitUntil {
                var reset = false
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)?.player
                    reset = player?.playbackState == Player.STATE_READY &&
                        !player.playWhenReady && player.currentPosition < 250L
                }
                reset
            }
        }

        assertFalse(prefs.contains(positionKey))
        assertFalse(prefs.contains(savedAtKey))
    }

    private fun withPlaybackVideo(test: (Context, android.net.Uri) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val originalPreferences = prefs.all
        prefs.edit()
            .putBoolean("autoplay_videos", false)
            .putBoolean("loop_videos", false)
            .putBoolean("remember_video_position", true)
            .commit()
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "playback-memory-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GaleriaPlaybackMemoryTest/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            test(context, uri)
        } finally {
            resolver.delete(uri, null, null)
            prefs.edit().apply {
                for (key in listOf("autoplay_videos", "loop_videos", "remember_video_position")) {
                    if (originalPreferences.containsKey(key)) putBoolean(key, originalPreferences[key] as Boolean)
                    else remove(key)
                }
                remove("video_pos_${uri.hashCode()}")
                remove("video_pos_${uri.hashCode()}_saved_at")
            }.commit()
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun launchVideo(context: Context, uri: android.net.Uri): ActivityScenario<DetailActivity> =
        ActivityScenario.launch(Intent(context, DetailActivity::class.java).apply {
            putExtra("uri", uri.toString())
            putExtra("name", "playback-memory.mp4")
            putExtra("mime", "video/mp4")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue("A condição de reprodução não foi atingida dentro do prazo", condition())
    }

    private inline fun <reified T : View> find(root: View): T? =
        descendants(root).filterIsInstance<T>().firstOrNull()

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
