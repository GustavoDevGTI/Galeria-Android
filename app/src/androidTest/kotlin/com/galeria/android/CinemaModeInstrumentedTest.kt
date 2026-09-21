package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.not
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CinemaModeInstrumentedTest {
    @Test
    fun cinemaButtonChangesModeWithoutReplacingPlayer() = withVideo { context, uri, albumKey ->
        val intent = videoIntent(context, uri, albumKey)
        ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            onView(withContentDescription("Modo cinema"))
                .check(matches(isDisplayed()))
                .check(matches(not(isSelected())))

            var playerBefore: androidx.media3.common.Player? = null
            scenario.onActivity { activity ->
                playerBefore = find<PlayerView>(activity.window.decorView)?.player
            }
            scenario.onActivity { activity ->
                val cinemaButton = descendants(activity.window.decorView)
                    .first { it.contentDescription == "Modo cinema" }
                cinemaButton.performClick()
                val indicator = activity.window.decorView
                    .findViewWithTag<android.widget.TextView>("viewer_cinema_transition_message")
                assertEquals("Modo cinema", indicator.text.toString())
                assertEquals(View.VISIBLE, indicator.visibility)
            }
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            assertCinemaButtonState(true)
            scenario.onActivity { activity ->
                val playerAfter = find<PlayerView>(activity.window.decorView)?.player
                assertSame("A troca de modo não deve recriar o player.", playerBefore, playerAfter)
            }

            onView(withContentDescription("Mais opções")).perform(click())
            onView(withText("Trilha de áudio")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Legenda")).perform(scrollTo()).check(matches(isDisplayed()))
            pressBack()
            scenario.onActivity { activity ->
                val cinemaButton = descendants(activity.window.decorView)
                    .first { it.contentDescription == "Modo cinema" }
                cinemaButton.performClick()
                val indicator = activity.window.decorView
                    .findViewWithTag<android.widget.TextView>("viewer_cinema_transition_message")
                assertEquals("Modo normal", indicator.text.toString())
                assertEquals(View.VISIBLE, indicator.visibility)
            }
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            assertCinemaButtonState(false)
            scenario.onActivity { activity ->
                assertSame(playerBefore, find<PlayerView>(activity.window.decorView)?.player)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            }
            assertFalse(CinemaModePreferences(context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)).isEnabled(albumKey))
        }
    }

    @Test
    fun albumCinemaPreferenceOpensVideoInCinemaMode() = withVideo { context, uri, albumKey ->
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        CinemaModePreferences(prefs).setEnabled(albumKey, true)

        ActivityScenario.launch<DetailActivity>(videoIntent(context, uri, albumKey)).use { scenario ->
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            assertCinemaButtonState(true)
            onView(withContentDescription("Modo cinema")).perform(click())
            assertCinemaButtonState(false)
            assertTrue(CinemaModePreferences(prefs).isEnabled(albumKey))
            scenario.recreate()
            assertCinemaButtonState(false)
        }
    }

    private fun assertCinemaButtonState(active: Boolean) {
        onView(withContentDescription("Modo cinema"))
            .check(matches(isDisplayed()))
            .check(matches(not(isSelected())))
            .check { view, exception ->
                if (exception != null) throw exception
                assertEquals(if (active) "Ativado" else "Desativado", ViewCompat.getStateDescription(view))
            }
    }

    private fun waitForOrientation(scenario: ActivityScenario<DetailActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var actual = Configuration.ORIENTATION_UNDEFINED
        do {
            scenario.onActivity { actual = it.resources.configuration.orientation }
            if (actual == expected) return
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("O clique deve atualizar a orientação da tela.", expected, actual)
    }

    @Test
    fun trackPreferencesAreRestoredWhenPlayerIsBound() = withVideo { context, _, albumKey ->
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val preferences = CinemaModePreferences(prefs).apply {
            setAudioPreference(albumKey, "language:pt")
            setSubtitlePreference(albumKey, "language:en")
            setSubtitlesEnabled(albumKey, true)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            val controller = VideoTrackController(preferences)
            try {
                controller.bind(player, albumKey)
                org.junit.Assert.assertEquals("language:pt", controller.audioPreference())
                org.junit.Assert.assertEquals("language:en", controller.subtitlePreference())
                assertTrue(controller.subtitlesEnabled())
            } finally {
                controller.unbind()
                player.release()
            }
        }
    }

    private fun withVideo(test: (Context, android.net.Uri, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val albumKey = "Movies/GaleriaCinemaTest-${System.nanoTime()}/"
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val originalAlbums = prefs.getStringSet("cinema_mode_album_keys", null)?.toSet()
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "cinema-${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, albumKey)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        ))
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("playback-sample.mp4").use { input ->
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            test(context, uri, albumKey)
        } finally {
            context.contentResolver.delete(uri, null, null)
            prefs.edit().apply {
                if (originalAlbums == null) remove("cinema_mode_album_keys")
                else putStringSet("cinema_mode_album_keys", originalAlbums)
                val albumHash = albumKey.hashCode()
                remove("cinema_audio_$albumHash")
                remove("cinema_subtitle_$albumHash")
                remove("cinema_subtitles_enabled_$albumHash")
            }.commit()
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun videoIntent(context: Context, uri: android.net.Uri, albumKey: String) =
        Intent(context, DetailActivity::class.java).apply {
            putExtra("uri", uri.toString())
            putExtra("name", "cinema.mp4")
            putExtra("mime", "video/mp4")
            putExtra("path", albumKey)
            putExtra("album_key", albumKey)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private inline fun <reified T : View> find(root: View): T? =
        descendants(root).filterIsInstance<T>().firstOrNull()

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }
}
