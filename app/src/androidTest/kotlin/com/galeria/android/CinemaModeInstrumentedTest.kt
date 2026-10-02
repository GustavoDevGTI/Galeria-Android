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
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
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
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
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
            waitForSystemBars(scenario, visible = true)
            assertPlaybackCentered(scenario)
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
            waitForSystemBars(scenario, visible = false)
            waitForCinemaTransition(scenario)
            assertPlaybackCentered(scenario)
            assertCinemaButtonState(true)
            scenario.onActivity { activity ->
                val playerAfter = find<PlayerView>(activity.window.decorView)?.player
                assertSame("A troca de modo não deve recriar o player.", playerBefore, playerAfter)
            }

            clickMoreAndRequirePopup(scenario)
            onView(withText("Trilha de áudio")).inRoot(isPlatformPopup()).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Legenda")).inRoot(isPlatformPopup()).perform(scrollTo()).check(matches(isDisplayed()))
            pressBack()
            waitForSystemBars(scenario, visible = false)
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
            waitForSystemBars(scenario, visible = true)
            waitForCinemaTransition(scenario)
            assertPlaybackCentered(scenario)
            assertCinemaButtonState(false)
            scenario.onActivity { activity ->
                assertSame(playerBefore, find<PlayerView>(activity.window.decorView)?.player)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            }
            assertFalse(CinemaModePreferences(context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)).isEnabled(albumKey))
        }
    }

    @Test
    fun pausingDuringModeAnimationSettlesOrientationAndAllowsNextClick() = withVideo { context, uri, albumKey ->
        ActivityScenario.launch<DetailActivity>(videoIntent(context, uri, albumKey)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).first { it.contentDescription == "Modo cinema" }.performClick()
                assertTrue(activity.cinemaController.transitioning)
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { assertFalse("Pausar deve concluir a transição, não deixar o botão bloqueado", it.cinemaController.transitioning) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            waitForCinemaTransition(scenario)
            waitForSystemBars(scenario, visible = false)
            assertCinemaButtonState(true)
            onView(withContentDescription("Modo cinema")).perform(click())
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            waitForCinemaTransition(scenario)
            waitForSystemBars(scenario, visible = true)
            assertCinemaButtonState(false)
        }
    }

    @Test
    fun albumCinemaPreferenceOpensVideoInCinemaMode() = withVideo { context, uri, albumKey ->
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        CinemaModePreferences(prefs).setEnabled(albumKey, true)

        ActivityScenario.launch<DetailActivity>(videoIntent(context, uri, albumKey)).use { scenario ->
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            waitForSystemBars(scenario, visible = false)
            assertCinemaButtonState(true)
            onView(withContentDescription("Modo cinema")).perform(click())
            waitForCinemaTransition(scenario)
            assertCinemaButtonState(false)
            assertTrue(CinemaModePreferences(prefs).isEnabled(albumKey))
            scenario.recreate()
            assertCinemaButtonState(false)
            waitForSystemBars(scenario, visible = true)
        }
    }

    @Test
    fun cinemaHidesSystemBarsAgainAfterFocusResumeAndRecreation() = withVideo { context, uri, albumKey ->
        CinemaModePreferences(context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE))
            .setEnabled(albumKey, true)
        ActivityScenario.launch<DetailActivity>(videoIntent(context, uri, albumKey)).use { scenario ->
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            waitForSystemBars(scenario, visible = false)
            // Simulate bars being restored while another window has focus. On
            // returning to the viewer, cinema must explicitly hide them again.
            scenario.onActivity { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            waitForSystemBars(scenario, visible = true)
            scenario.onActivity {
                it.onWindowFocusChanged(false)
                it.onWindowFocusChanged(true)
            }
            waitForSystemBars(scenario, visible = false)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForSystemBars(scenario, visible = false)
            scenario.recreate()
            waitForSystemBars(scenario, visible = false)
            assertCinemaButtonState(true)
        }
    }

    @Test
    fun virtualAlbumUsesTheVideosPhysicalAlbumCinemaPreference() = withVideo { context, uri, albumKey ->
        val preferences = CinemaModePreferences(context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE))
        preferences.setEnabled(albumKey, true)
        preferences.setAudioPreference(albumKey, "language:pt")

        val intent = videoIntent(context, uri, VirtualAlbumRules.RECENT_KEY).apply {
            putExtra("path", albumKey)
        }
        ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            assertCinemaButtonState(true)
            scenario.onActivity { activity ->
                val controller = DetailActivity::class.java.getDeclaredField("videoTrackController").apply {
                    isAccessible = true
                }.get(activity) as VideoTrackController
                assertEquals("language:pt", controller.audioPreference())
            }
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

    private fun clickMoreAndRequirePopup(scenario: ActivityScenario<DetailActivity>) {
        // View focus/layout do not prove Android's input transition is finished.
        // Keep a real injected click, without retrying or calling performClick.
        awaitAndroidInputReady()
        val actions = arrayListOf<Int>()
        var bounds = ""
        scenario.onActivity { activity ->
            val more = descendants(activity.window.decorView).first { it.contentDescription == "Mais opções" }
            val location = IntArray(2).also(more::getLocationOnScreen)
            bounds = "${location.toList()}, ${more.width}x${more.height}"
            more.setOnTouchListener { _, event -> actions.add(event.actionMasked); false }
        }
        try { onView(withContentDescription("Mais opções")).perform(click()) }
        finally {
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).first { it.contentDescription == "Mais opções" }.setOnTouchListener(null)
            }
        }
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var popup = false
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                popup = WindowInspector.getGlobalWindowViews().any { it.javaClass.name.endsWith("PopupDecorView") }
            }
            if (popup) break
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("O toque deve alcançar o botão: actions=$actions; bounds=$bounds", actions.contains(MotionEvent.ACTION_DOWN) && actions.contains(MotionEvent.ACTION_UP))
        assertTrue("Um único toque deve abrir o menu de cinema: actions=$actions; bounds=$bounds", popup)
    }

    private fun waitForOrientation(scenario: ActivityScenario<DetailActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var actual = Configuration.ORIENTATION_UNDEFINED
        var hasFocus = false
        do {
            scenario.onActivity {
                actual = it.resources.configuration.orientation
                hasFocus = it.hasWindowFocus()
            }
            if (actual == expected && hasFocus) return
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("O clique deve atualizar a orientação da tela.", expected, actual)
        assertTrue("A janela deve recuperar o foco após alterar a orientação.", hasFocus)
    }

    private fun waitForCinemaTransition(scenario: ActivityScenario<DetailActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var ready = false
        do {
            scenario.onActivity { activity ->
                val transitioning = activity.cinemaController.transitioning
                // Orientation/focus can be ready while the final layout and
                // content fade are still running. Do not tap the menu mid-flight.
                val surfaces = listOf("content", "topBar", "bottomBar").map { name ->
                    DetailActivity::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(activity) as View
                }
                ready = !transitioning && activity.hasWindowFocus() &&
                    activity.window.decorView.isLaidOut && !activity.window.decorView.isLayoutRequested &&
                    surfaces.all { it.isLaidOut && !it.isLayoutRequested && kotlin.math.abs(it.alpha - 1f) < 0.001f }
            }
            if (ready) return
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("A troca de modo deve concluir antes de recriar a Activity.", ready)
    }

    private fun waitForSystemBars(scenario: ActivityScenario<DetailActivity>, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var status: Boolean? = null
        var navigation: Boolean? = null
        do {
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                status = insets?.isVisible(WindowInsetsCompat.Type.statusBars())
                navigation = insets?.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
            if (status == visible && navigation == visible) return
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Visibilidade da barra de notificações.", visible, status)
        assertEquals("Visibilidade da barra de navegação.", visible, navigation)
    }

    private fun assertPlaybackCentered(scenario: ActivityScenario<DetailActivity>) {
        scenario.onActivity { activity ->
            val row = activity.window.decorView.findViewWithTag<View>("video_playback_controls")
            val play = activity.window.decorView.findViewWithTag<View>("video_play_pause")
            assertTrue("Os controles devem estar medidos.", row.width > 0 && play.width > 0)
            assertTrue("Play/pause deve ficar centralizado na barra, inclusive após rotação.",
                kotlin.math.abs(play.x + play.width / 2f - row.width / 2f) <= 1f)
        }
    }

    @Test
    fun trackPreferencesAreRestoredWhenPlayerIsBound() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val albumKey = "Movies/TrackSelectionTest-${System.nanoTime()}/"
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val preferences = CinemaModePreferences(prefs)
        val controller = VideoTrackController(preferences)
        var player: ExoPlayer? = null
        var audioToken = ""
        val subtitleToken = "language:en"
        fun awaitTracks(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10_000L
            var ready = false
            do {
                instrumentation.runOnMainSync { ready = condition() }
                if (ready) return
                SystemClock.sleep(50L)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue(message, ready)
        }
        TrackPlaybackFixture(context, instrumentation.context.assets).use { fixture ->
            try {
                fun bindPlayer() = instrumentation.runOnMainSync {
                    controller.unbind()
                    player?.release()
                    player = ExoPlayer.Builder(context).build().also {
                        controller.bind(it, albumKey)
                        it.setMediaItem(fixture.mediaItem)
                        it.prepare()
                    }
                }
                bindPlayer()
                awaitTracks("Duas trilhas de áudio e duas legendas devem ser carregadas") {
                    player?.playbackState == androidx.media3.common.Player.STATE_READY &&
                        controller.audioChoices().size == 2 && controller.subtitleChoices().size == 2
                }
                instrumentation.runOnMainSync {
                    val audio = controller.audioChoices().last()
                    audioToken = audio.preferenceToken
                    assertEquals(2, controller.audioChoices().map { it.preferenceToken }.distinct().size)
                    controller.selectAudio(audio)
                    controller.selectSubtitle(controller.subtitleChoices().first { it.preferenceToken == subtitleToken })
                }
                fun chosenTracksAreActive() =
                    controller.audioChoices().filter { it.selected }.map { it.preferenceToken } == listOf(audioToken) &&
                        controller.subtitleChoices().filter { it.selected }.map { it.preferenceToken } == listOf(subtitleToken)
                awaitTracks("A seleção deve alterar as trilhas ativas do player", ::chosenTracksAreActive)
                instrumentation.runOnMainSync { controller.disableSubtitles() }
                awaitTracks("Desativar legenda deve remover a trilha de texto ativa") {
                    !controller.subtitlesEnabled() && controller.subtitleChoices().none { it.selected }
                }
                instrumentation.runOnMainSync {
                    controller.selectSubtitle(controller.subtitleChoices().first { it.preferenceToken == subtitleToken })
                    assertEquals(audioToken, preferences.audioPreference(albumKey))
                    assertEquals(subtitleToken, preferences.subtitlePreference(albumKey))
                }
                awaitTracks("Reativar legenda deve restaurar a seleção real", ::chosenTracksAreActive)
                bindPlayer()
                awaitTracks("Um novo player deve restaurar as trilhas salvas, não apenas as strings") {
                    player?.playbackState == androidx.media3.common.Player.STATE_READY && chosenTracksAreActive()
                }
            } finally {
                instrumentation.runOnMainSync { controller.unbind(); player?.release() }
                val hash = albumKey.hashCode()
                prefs.edit().remove("cinema_audio_$hash").remove("cinema_subtitle_$hash")
                    .remove("cinema_subtitles_enabled_$hash").commit()
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
