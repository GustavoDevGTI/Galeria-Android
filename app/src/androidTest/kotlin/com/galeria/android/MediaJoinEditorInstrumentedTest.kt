package com.galeria.android

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MediaJoinEditorInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.POST_NOTIFICATIONS
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun editorReordersCutsAndJoinsThroughTheService() {
        val suffix = System.nanoTime()
        val folder = "Pictures/GaleriaJoinEditor-$suffix/"
        val video = insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "clip-$suffix.mp4", "video/mp4", folder,
            instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() })
        val photo = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo-$suffix.jpg", "image/jpeg", folder, jpeg(Color.rgb(30, 160, 110)))
        val items = listOf(
            MediaItem(1L, video, "clip-$suffix.mp4", "video/mp4", 0L, 1L, folder, null, null),
            MediaItem(2L, photo, "photo-$suffix.jpg", "image/jpeg", 0L, 2L, folder, null, null)
        )
        var joined: Uri? = null
        try {
            ActivityScenario.launchActivityForResult<MediaJoinActivity>(MediaJoinActivity.intent(context, items)).use { scenario ->
                val strip = await(scenario) { it.window.decorView.findViewWithClass<JoinStripView>() }
                val timeline = await(scenario) { it.window.decorView.findViewWithClass<JoinTimelineView>() }
                // Both media are in, in the order they were tapped.
                awaitTrue("O editor deve abrir com as duas mídias") { timelineTotal(scenario, timeline) > 4_000L }
                val before = timelineTotal(scenario, timeline)

                // Moving the video after the photo swaps them on the timeline too.
                scenario.onActivity { strip.performAccessibilityAction(R.id.join_move_right, null) }
                // One more second for each photo.
                tap(scenario, "${context.getString(R.string.join_photo_time)} +")
                awaitTrue("O tempo da foto deve aumentar a duração") { timelineTotal(scenario, timeline) == before + 1_000L }

                // Split the video (now second) at its middle: same length, two parts of one media.
                scenario.onActivity { activity ->
                    strip.onSelect?.invoke(1)
                    val segment = MediaJoinRules.segments(activity.clipsForTest(), activity.photoMsForTest()).first { it.clipIndex == 1 }
                    timeline.setPosition((segment.startMs + segment.endMs) / 2)
                }
                tap(scenario, context.getString(R.string.join_split))
                scenario.onActivity { activity ->
                    assertEquals(2, activity.clipsForTest()[1].ranges.size)
                    assertEquals("image/jpeg", activity.clipsForTest()[0].media.mime)
                }

                // The circle between the two media opens the transitions; Dissolver overlaps them.
                scenario.onActivity { strip.onJunction?.invoke(0, strip.junctionCenter(0)) }
                onView(withText(R.string.join_transition_dissolve)).inRoot(isPlatformPopup()).perform(click())
                awaitTrue("A transição deve ser escolhida") {
                    var chosen: List<JoinTransition> = emptyList()
                    scenario.onActivity { chosen = it.transitionsForTest() }
                    chosen == listOf(JoinTransition.DISSOLVE)
                }

                tap(scenario, context.getString(R.string.action_join))
                val deadline = SystemClock.uptimeMillis() + 60_000L
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200L)
                assertEquals("O editor fecha depois de salvar", Lifecycle.State.DESTROYED, scenario.state)
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                joined = scenario.result.resultData?.data
            }
            assertNotNull(joined)
            val record = requireNotNull(MediaJoinExporter.readRecord(context, joined!!))
            assertEquals(listOf(photo.toString(), video.toString()), record.clips.map { it.media.uri })
            assertEquals(6_000L, record.photoMs)
            assertEquals(2, record.clips[1].ranges.size)
            assertEquals(listOf(JoinTransition.DISSOLVE), record.transitions)
            assertTrue(record.clips[1].trimmed.not() || record.clips[1].ranges.size == 2)
            // The video used in the join starts its 48-hour clock; the photo only with that setting.
            assertNotNull("O vídeo usado ganha o relógio", MediaClocks.of(context, video.toString()))
            assertEquals(null, MediaClocks.of(context, photo.toString()))
        } finally {
            listOf(video, photo).forEach { MediaClocks.cancel(context, it.toString()) }
            listOfNotNull(video, photo, joined).forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    /**
     * Taps an editor button on the main thread. Espresso's click can turn into a long press while
     * the emulator is still busy with earlier exports, and a long press only shows the tooltip.
     */
    private fun tap(scenario: ActivityScenario<MediaJoinActivity>, description: String) {
        var tapped = false
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!tapped && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val found = ArrayList<android.view.View>()
                activity.window.decorView.findViewsWithText(found, description, android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                found.firstOrNull { it.isShown && it.isEnabled }?.let { tapped = it.performClick() }
            }
            if (!tapped) SystemClock.sleep(100L)
        }
        assertTrue("Botão $description não encontrado", tapped)
    }

    private fun timelineTotal(scenario: ActivityScenario<MediaJoinActivity>, timeline: JoinTimelineView): Long {
        var total = 0L
        scenario.onActivity { total = MediaJoinRules.totalMs(it.clipsForTest(), it.photoMsForTest()) }
        return total
    }

    private fun <T> await(scenario: ActivityScenario<MediaJoinActivity>, find: (MediaJoinActivity) -> T?): T {
        var found: T? = null
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { found = find(it) }
            if (found == null) SystemClock.sleep(100L)
        }
        return requireNotNull(found)
    }

    private fun awaitTrue(message: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!check()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError(message)
            SystemClock.sleep(100L)
        }
    }

    private inline fun <reified T : android.view.View> android.view.View.findViewWithClass(): T? =
        findByClass(this, T::class.java)

    private fun <T : android.view.View> findByClass(view: android.view.View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findByClass(view.getChildAt(i), type)?.let { return it }
        return null
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle() }.toByteArray()
    }

    private fun insert(collection: Uri, name: String, mime: String, folder: String, bytes: ByteArray): Uri {
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
