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
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.hamcrest.Matchers.not
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
                // Tapping the photo's square shows its own time (3 s at first); one more second for it.
                scenario.onActivity { strip.onSelect?.invoke(0) }
                onView(withText(context.getString(R.string.join_seconds, 3))).check(matches(isDisplayed()))
                tap(scenario, "${context.getString(R.string.join_photo_time)} +")
                awaitTrue("O tempo da foto deve aumentar a duração") { timelineTotal(scenario, timeline) == before + 1_000L }
                onView(withText(context.getString(R.string.join_seconds, 4))).check(matches(isDisplayed()))
                // The video's square has no photo time.
                scenario.onActivity { strip.onSelect?.invoke(1) }
                onView(withText(context.getString(R.string.join_seconds, 4))).check(matches(not(isDisplayed())))

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

                // One button at the top picks the transition for every junction; Dissolver overlaps them.
                tap(scenario, context.getString(R.string.join_transition))
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
            // The merge notification uses a normal channel (not folded into the silent ones).
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, notifications.getNotificationChannel("media_join_alerts")?.importance)
            assertEquals("O canal silencioso antigo sai", null, notifications.getNotificationChannel("media_join"))
            val record = requireNotNull(MediaJoinExporter.readRecord(context, joined!!))
            assertEquals(listOf(photo.toString(), video.toString()), record.clips.map { it.media.uri })
            assertEquals("A foto guarda o próprio tempo", 4_000L, record.clips[0].photoMs)
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

    @Test fun aNewEditorOpensEvenWhenTheLastMergeFinishedInTheBackground() {
        val suffix = System.nanoTime()
        val folder = "Pictures/GaleriaJoinAgain-$suffix/"
        val photo = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo-$suffix.jpg", "image/jpeg", folder, jpeg(Color.YELLOW))
        val items = listOf(MediaItem(1L, photo, "photo-$suffix.jpg", "image/jpeg", 0L, 1L, folder, null, null),
            MediaItem(2L, photo, "photo-$suffix.jpg", "image/jpeg", 0L, 1L, folder, null, null))
        try {
            // A merge that ended after its editor closed (sent to the background) leaves "saved" behind.
            MediaJoinJobs.publish(MediaJoinJobs.State.Saved(photo))
            ActivityScenario.launch<MediaJoinActivity>(MediaJoinActivity.intent(context, items)).use { scenario ->
                awaitTrue("O editor novo deve abrir com as mídias") { var n = 0; scenario.onActivity { n = it.clipsForTest().size }; n == 2 }
                SystemClock.sleep(500L)
                assertEquals("O editor novo não fecha com o resultado da mescla anterior", Lifecycle.State.RESUMED, scenario.state)
                assertEquals(MediaJoinJobs.State.Idle, MediaJoinJobs.state)
            }
        } finally {
            MediaJoinJobs.acknowledge()
            context.contentResolver.delete(photo, null, null)
        }
    }

    @Test fun aWrongPickCanBeRemovedAndManyQuickMovesLeaveThePreviewReady() {
        val suffix = System.nanoTime()
        val folder = "Pictures/GaleriaJoinRemove-$suffix/"
        val video = insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "clip-$suffix.mp4", "video/mp4", folder,
            instrumentation.context.assets.open("playback-sample.mp4").use { it.readBytes() })
        val photos = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
            insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo$index-$suffix.jpg", "image/jpeg", folder, jpeg(color))
        }
        // The same photo picked twice by mistake.
        val items = listOf(MediaItem(1L, video, "clip-$suffix.mp4", "video/mp4", 0L, 1L, folder, null, null)) +
            (photos + photos[0]).mapIndexed { index, uri -> MediaItem(10L + index, uri, "photo-$index.jpg", "image/jpeg", 0L, 1L, folder, null, null) }
        try {
            ActivityScenario.launch<MediaJoinActivity>(MediaJoinActivity.intent(context, items)).use { scenario ->
                val strip = await(scenario) { it.window.decorView.findViewWithClass<JoinStripView>() }
                awaitTrue("O editor deve abrir com as cinco mídias") { var n = 0; scenario.onActivity { n = it.clipsForTest().size }; n == 5 }
                // The repeated photo (last square) goes away with the remove button.
                scenario.onActivity { strip.onSelect?.invoke(4) }
                tap(scenario, context.getString(R.string.join_remove))
                scenario.onActivity { activity ->
                    assertEquals(4, activity.clipsForTest().size)
                    assertEquals(listOf(video, photos[0], photos[1], photos[2]).map { it.toString() }, activity.clipsForTest().map { it.media.uri })
                }
                var state = ""
                fun previewReady(): Boolean {
                    var ready = false
                    scenario.onActivity { activity ->
                        val player = MediaJoinActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as androidx.media3.common.Player
                        val dirty = MediaJoinActivity::class.java.getDeclaredField("previewDirty").apply { isAccessible = true }.getBoolean(activity)
                        val causes = generateSequence(activity.lastPreviewError) { it.cause }.take(8).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message} @ ${it.stackTrace.firstOrNull()}" }
                        state = "dirty=$dirty state=${player.playbackState} error=$causes position=${player.currentPosition}"
                        ready = !dirty && player.playbackState == androidx.media3.common.Player.STATE_READY
                    }
                    return ready
                }
                try {
                    awaitTrue("A prévia inicial deve ficar pronta", 15_000L) { previewReady() }
                } catch (error: AssertionError) { throw AssertionError("${error.message}: $state") }
                // Many squares moved in a row: the preview gets only the last order and stays ready.
                repeat(12) { step ->
                    scenario.onActivity { strip.onMove?.invoke(step % 4, (step + 1) % 4) }
                }
                try {
                    awaitTrue("A prévia deve ficar pronta com a última ordem", 15_000L) { previewReady() }
                } catch (error: AssertionError) { throw AssertionError("${error.message}: $state") }
                // Report any error the preview recovered from, so a recurring one is not hidden.
                scenario.onActivity { activity -> activity.lastPreviewError?.let { android.util.Log.w("JoinTest", "Prévia se recuperou de: $state", it) } }
                // The last media cannot be removed: the montage always keeps one.
                scenario.onActivity { activity ->
                    repeat(3) { strip.onRemove?.invoke(0) }
                    assertEquals(1, activity.clipsForTest().size)
                    strip.onRemove?.invoke(0)
                    assertEquals(1, activity.clipsForTest().size)
                }
            }
        } finally {
            (photos + video).forEach { runCatching { context.contentResolver.delete(it, null, null) } }
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

    private fun awaitTrue(message: String, timeoutMs: Long = 10_000L, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
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
