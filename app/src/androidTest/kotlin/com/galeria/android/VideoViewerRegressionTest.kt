package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.`is`
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoViewerRegressionTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun swipeOnVideoSurfaceChangesMediaAndDeleteActuallyTrashesIt() {
        val folder = "Movies/ViewerRegression-${System.nanoTime()}/"
        val first = insert(folder, "first.mp4")
        val second = insert(folder, "second.mp4")
        try {
            MediaStoreRepository.refreshMedia(context, force = true)
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", first.toString()); putExtra("mime", "video/mp4"); putExtra("name", "first.mp4")
                putExtra("album_key", folder); putExtra("path", folder)
            }).use { scenario ->
                await { var ready = false; scenario.onActivity {
                    val queue = it.javaClass.getDeclaredField("queueController").apply { isAccessible = true }.get(it) as DetailMediaQueueController
                    ready = queue.items.size == 2 && it.window.decorView.findViewWithTag<PlayerView>("detail_video_player").player?.duration?.let { d -> d > 0 } == true
                }; ready }
                onView(withTagValue(`is`("video_gesture_surface" as Any))).perform(swipeLeft())
                await { var changed = false; scenario.onActivity {
                    changed = current(it).name == "second.mp4"
                }; changed }
                onView(withContentDescription("Excluir")).perform(clickClickableAncestor())
                onView(withText(R.string.action_move_to_trash)).perform(clickClickableAncestor())
                await { MediaStoreRepository.loadTrashedMedia(context).any { MediaIdentityRules.sameUri(it.uri.toString(), second.toString()) } }
                assertFalse(MediaStoreRepository.refreshMedia(context, force = true).any { MediaIdentityRules.sameUri(it.uri.toString(), second.toString()) })
                scenario.onActivity { assertEquals("first.mp4", current(it).name) }
                onView(withTagValue(`is`("video_gesture_surface" as Any))).perform(click())
            }
        } finally {
            listOf(first, second).forEach { context.contentResolver.delete(it, null, null) }
            MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun filesystemVideoCanBeTrashedAndRestoredOnModernAndroid() {
        // Private external test media: no permission escalation and no user's files involved.
        val file = File(context.getExternalFilesDir(null), "hidden-trash-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("playback-sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
        var trashUri: Uri? = null
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { assertEquals(MediaActions.RESULT_DONE, MediaActions.requestDelete(it, Uri.fromFile(file), 711)) }
                assertFalse("Arquivo original deve sair da pasta", file.exists())
                trashUri = MediaStoreRepository.loadTrashedMedia(context).first { it.name == file.name }.uri
                scenario.onActivity { assertEquals(MediaActions.RESULT_DONE, MediaActions.requestRestore(it, requireNotNull(trashUri), 712)) }
                assertTrue(file.exists())
                assertFalse(MediaStoreRepository.loadTrashedMedia(context).any { it.uri == trashUri })
            }
        } finally {
            file.delete()
            trashUri?.let { uri -> if (uri.scheme == "file") File(uri.path!!).delete(); LegacyTrashStore.forget(context, uri) }
        }
    }

    private fun insert(folder: String, name: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        instrumentation.context.assets.open("playback-sample.mp4").use { input -> context.contentResolver.openOutputStream(uri)!!.use(input::copyTo) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun current(activity: DetailActivity) = activity.javaClass.getDeclaredMethod("currentItem").apply { isAccessible = true }.invoke(activity) as MediaItem
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Condição do visualizador não atingida")
    }
}
