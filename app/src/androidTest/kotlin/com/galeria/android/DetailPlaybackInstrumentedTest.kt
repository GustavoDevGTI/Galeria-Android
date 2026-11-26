package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class DetailPlaybackInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun muteAndSpeedSurviveActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertEmptyVideo(context)
        val intent = ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java)).apply {
            putExtra("uri", uri.toString())
            putExtra("name", "estado-video.mp4")
            putExtra("mime", "video/mp4")
            putExtra("path", "Pictures/GaleriaPlaybackTest/")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
                onView(withContentDescription("Desativar som")).perform(click())
                onView(withContentDescription("Ativar som")).check(matches(isDisplayed()))

                onView(withText("1x")).perform(quickTap())
                onView(withText("1,5x")).check(matches(isDisplayed()))

                scenario.recreate()

                onView(withContentDescription("Ativar som")).check(matches(isDisplayed()))
                onView(withText("1,5x")).check(matches(isDisplayed()))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun quickTapsChangeThePlayerAndHoldingOpensTheExistingSelectorWithoutCycling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "speed-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("playback-sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch<DetailActivity>(ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java)).apply {
                putExtra("uri", Uri.fromFile(file).toString())
                putExtra("mime", "video/mp4")
                putExtra("name", file.name)
            }).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 15_000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity {
                        val player = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player
                        ready = player?.playbackState == Player.STATE_READY
                    }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("O vídeo real deve estar pronto", ready)
                lateinit var original: Player
                scenario.onActivity {
                    original = it.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                    original.pause()
                }
                // What reached the speed button since the last check, to tell a lost tap from one
                // that arrived (the listener only watches; the button still handles every touch).
                val touches = java.util.Collections.synchronizedList(ArrayList<String>())
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("video_speed").setOnTouchListener { _, event ->
                        touches += MotionEvent.actionToString(event.actionMasked); false
                    }
                }
                var step = 0
                fun assertSpeed(expected: Float) = scenario.onActivity { activity ->
                    step++
                    val player = activity.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player!!
                    assertSame(original, player)
                    val button = activity.window.decorView.findViewWithTag<View>("video_speed")
                    val bar = button.rootView.findViewWithTag<View>("video_playback_controls")?.parent as? View
                    // If the selector is open, the tap reached the button as a long press.
                    assertEquals("Velocidade no passo $step (seletor aberto: ${activity.speedPopup?.isShowing == true}, " +
                        "toques no botão: $touches, barra visível: ${activity.hudVisible} alpha=${bar?.alpha} " +
                        "vis=${bar?.visibility}, botão exibido: ${button.isShown})",
                        expected, player.playbackParameters.speed, 0.001f)
                    touches.clear()
                    assertEquals(DetailPlaybackRules.speedLabel(expected), activity.window.decorView.findViewWithTag<TextView>("video_speed").text.toString())
                    assertFalse("Mudar velocidade não deve dar play", player.playWhenReady)
                }
                repeat(2) {
                    for (speed in listOf(1.5f, 2f, 1f)) {
                        onView(withTagValue(`is`("video_speed"))).perform(quickTap())
                        assertSpeed(speed)
                    }
                }
                onView(withTagValue(`is`("video_speed"))).perform(quickTap())
                assertSpeed(1.5f)
                onView(withTagValue(`is`("video_speed"))).perform(longClick())
                assertSpeed(1.5f)
                for (label in listOf("0,5x", "1x", "1,5x", "2x")) {
                    onView(withText(label)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
                }
                onView(withText("0,5x")).inRoot(isPlatformPopup()).perform(click())
                assertSpeed(0.5f)
                scenario.onActivity {
                    val popup = DetailActivity::class.java.getDeclaredField("speedPopup").apply { isAccessible = true }.get(it) as PopupWindow
                    assertFalse("Escolher velocidade fecha o seletor", popup.isShowing)
                }
                onView(withTagValue(`is`("video_speed"))).perform(quickTap())
                assertSpeed(1f)
            }
        } finally { file.delete() }
    }

    /**
     * One quick tap, exactly once. On a busy emulator Espresso waits for the main thread between
     * finger down and up; with the video player active that passed the long-press time, so taps
     * opened the speed selector instead of changing the speed (and the rollback variant tapped
     * three times). Here down and up reach the button in the same main-thread turn, through its
     * real touch handling, so a tap can never turn into a long press; a tap the app ignores still
     * fails the test.
     */
    private fun quickTap() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints(): org.hamcrest.Matcher<View> = isDisplayed()
        override fun getDescription() = "um toque rápido no botão"
        override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
            val time = SystemClock.uptimeMillis()
            val x = view.width / 2f
            val y = view.height / 2f
            val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(time, time + 40L, android.view.MotionEvent.ACTION_UP, x, y, 0)
            try {
                view.dispatchTouchEvent(down)
                view.dispatchTouchEvent(up)
            } finally { down.recycle(); up.recycle() }
            // The click itself runs right after, from the button's own touch handling.
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun insertEmptyVideo(context: Context): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "playback-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/GaleriaPlaybackTest/")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        return requireNotNull(uri)
    }
}
