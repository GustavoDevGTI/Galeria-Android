package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
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
import com.github.panpf.zoomimage.CoilZoomImageView

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

    @Test fun bothSwipeAxesKeepWorkingForPhotosAndVideosAfterReopening() {
        val folder = "DCIM/SwipeReopen-${System.nanoTime()}/"
        val created = ArrayList<Uri>()
        try {
            created.add(insertPhoto(folder, "photo-a.png"))
            created.add(insert(folder, "video-b.mp4"))
            created.add(insertPhoto(folder, "photo-c.png"))
            created.add(insert(folder, "video-d.mp4"))
            val catalog = MediaStoreRepository.refreshMedia(context, force = true)
            GalleryCatalogStore.saveCustomOrder(context, folder, created.map { uri ->
                catalog.first { MediaIdentityRules.sameUri(it.uri.toString(), uri.toString()) }
            })
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", created.first().toString())
                putExtra("mime", "image/png")
                putExtra("name", "photo-a.png")
                putExtra("album_key", folder)
                putExtra("path", folder)
            }
            // First opening, then a new viewer without the repository's warm cache.
            repeat(2) { opening ->
                if (opening > 0) MediaStoreRepository.invalidateCache()
                ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
                    awaitViewerReady(scenario, created[0])
                    val steps = listOf(
                        Triple(true, true, 1), Triple(true, true, 2),
                        Triple(false, true, 3), Triple(false, true, 0),
                        Triple(true, false, 3), Triple(true, false, 2),
                        Triple(false, false, 1), Triple(false, false, 0)
                    )
                    steps.forEachIndexed { index, (horizontal, forward, destination) ->
                        if (index == 4) {
                            scenario.moveToState(Lifecycle.State.STARTED)
                            scenario.moveToState(Lifecycle.State.RESUMED)
                            awaitViewerReady(scenario, created[0])
                        }
                        onView(withContentDescription("Visualizador de mídia"))
                            .perform(viewerSwipe(horizontal, forward))
                        awaitViewerReady(scenario, created[destination])
                    }
                    scenario.recreate()
                    awaitViewerReady(scenario, created[0])
                    onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(true, true))
                    awaitViewerReady(scenario, created[1])
                    onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(false, false))
                    awaitViewerReady(scenario, created[0])
                }
            }
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            GalleryDatabase.get(context).galleryDao().deleteCustomOrder(folder)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun cancelledNewTouchDuringTransitionDoesNotLeaveViewerLocked() {
        val folder = "DCIM/SwipeCancel-${System.nanoTime()}/"
        val created = ArrayList<Uri>()
        try {
            created.add(insertPhoto(folder, "photo-a.png"))
            created.add(insert(folder, "video-b.mp4"))
            created.add(insertPhoto(folder, "photo-c.png"))
            created.add(insert(folder, "video-d.mp4"))
            val catalog = MediaStoreRepository.refreshMedia(context, force = true)
            GalleryCatalogStore.saveCustomOrder(context, folder, created.map { uri ->
                catalog.first { MediaIdentityRules.sameUri(it.uri.toString(), uri.toString()) }
            })
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", created[0].toString())
                putExtra("mime", "image/png")
                putExtra("name", "photo-a.png")
                putExtra("album_key", folder)
                putExtra("path", folder)
            }).use { scenario ->
                awaitViewerReady(scenario, created[0])
                scenario.onActivity { activity ->
                    val image = descendants(activity.window.decorView).filterIsInstance<CoilZoomImageView>().first()
                    val start = SystemClock.uptimeMillis()
                    fun send(action: Int, x: Float, downTime: Long, time: Long) {
                        val event = MotionEvent.obtain(downTime, time, action, x, image.height * 0.5f, 0)
                        try { image.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    send(MotionEvent.ACTION_DOWN, image.width * 0.65f, start, start)
                    send(MotionEvent.ACTION_MOVE, image.width * 0.35f, start, start + 16)
                    send(MotionEvent.ACTION_UP, image.width * 0.35f, start, start + 32)
                    assertTrue("A troca deve estar animando antes do segundo toque.",
                        activity.javaClass.getDeclaredField("switchingItem").apply { isAccessible = true }.getBoolean(activity))
                    // A separate valid touch stream is cancelled by the system
                    // while the outgoing photo still exists during the animation.
                    send(MotionEvent.ACTION_DOWN, image.width * 0.5f, start + 33, start + 33)
                    send(MotionEvent.ACTION_CANCEL, image.width * 0.5f, start + 33, start + 34)
                }
                SystemClock.sleep(500L)
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    val switching = activity.javaClass.getDeclaredField("switchingItem")
                        .apply { isAccessible = true }.getBoolean(activity)
                    assertFalse("Um toque cancelado deixou switchingItem=true mesmo após retornar ao app, bloqueando a navegação.", switching)
                }
                awaitViewerReady(scenario, created[1])
                onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(false, true))
                awaitViewerReady(scenario, created[2])
                onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(true, true))
                awaitViewerReady(scenario, created[3])
            }
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            GalleryDatabase.get(context).galleryDao().deleteCustomOrder(folder)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    @Test fun interruptedAnimationsAndBackgroundDoNotDesynchronizeTheViewer() {
        val folder = "DCIM/SwipeInterrupted-${System.nanoTime()}/"
        val created = ArrayList<Uri>()
        try {
            created.add(insertPhoto(folder, "photo-a.png"))
            created.add(insert(folder, "video-b.mp4"))
            created.add(insertPhoto(folder, "photo-c.png"))
            created.add(insert(folder, "video-d.mp4"))
            val catalog = MediaStoreRepository.refreshMedia(context, force = true)
            GalleryCatalogStore.saveCustomOrder(context, folder, created.map { uri ->
                catalog.first { MediaIdentityRules.sameUri(it.uri.toString(), uri.toString()) }
            })
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", created[0].toString())
                putExtra("mime", "image/png")
                putExtra("name", "photo-a.png")
                putExtra("album_key", folder)
                putExtra("path", folder)
            }).use { scenario ->
                awaitViewerReady(scenario, created[0])
                scenario.onActivity { activity ->
                    beginPhotoTransition(activity).animate().cancel()
                }
                // No end action remains: the bounded fallback must settle the page/player.
                awaitViewerReady(scenario, created[1])
                onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(false, true))
                awaitViewerReady(scenario, created[2])
                scenario.onActivity { activity ->
                    beginPhotoTransition(activity).animate().cancel()
                    // Disable the fallback here to independently exercise lifecycle completion.
                    val pending = activity.javaClass.getDeclaredField("pendingMediaTransitionFinish")
                        .apply { isAccessible = true }.get(activity) as Runnable
                    val handler = activity.javaClass.getDeclaredField("handler")
                        .apply { isAccessible = true }.get(activity) as android.os.Handler
                    handler.removeCallbacks(pending)
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.onActivity { activity ->
                    assertFalse(activity.javaClass.getDeclaredField("switchingItem")
                        .apply { isAccessible = true }.getBoolean(activity))
                    val playback = activity.javaClass.getDeclaredField("playbackController")
                        .apply { isAccessible = true }.get(activity) as DetailPlaybackController
                    assertNotNull("A mídia de destino deve possuir um player após concluir a troca.", playback.player())
                    assertFalse("O vídeo não pode tocar em segundo plano.", playback.player()!!.playWhenReady)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitViewerReady(scenario, created[3])
                onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(true, true))
                awaitViewerReady(scenario, created[0])
                scenario.onActivity { activity ->
                    // Automatic/shuffle changes share the same completion and resource ownership.
                    activity.javaClass.getDeclaredMethod("switchItem", Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1, false)
                    val incoming = activity.javaClass.getDeclaredField("activePage")
                        .apply { isAccessible = true }.get(activity) as View
                    incoming.animate().cancel()
                }
                awaitViewerReady(scenario, created[1])
                scenario.recreate()
                awaitViewerReady(scenario, created[1])
                onView(withContentDescription("Visualizador de mídia")).perform(viewerSwipe(false, true))
                awaitViewerReady(scenario, created[2])
            }
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            GalleryDatabase.get(context).galleryDao().deleteCustomOrder(folder)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    private fun beginPhotoTransition(activity: DetailActivity): View {
        val image = descendants(activity.window.decorView).filterIsInstance<CoilZoomImageView>().first()
        val start = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to 0.65f, MotionEvent.ACTION_MOVE to 0.35f,
            MotionEvent.ACTION_UP to 0.35f).forEachIndexed { index, (action, fraction) ->
            val event = MotionEvent.obtain(start, start + index * 16L, action,
                image.width * fraction, image.height * 0.5f, 0)
            try { image.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        assertTrue(activity.javaClass.getDeclaredField("switchingItem")
            .apply { isAccessible = true }.getBoolean(activity))
        return activity.javaClass.getDeclaredField("dragPreviewPage")
            .apply { isAccessible = true }.get(activity) as View
    }

    private fun viewerSwipe(horizontal: Boolean, forward: Boolean): GeneralSwipeAction {
        fun point(view: View, start: Boolean): FloatArray {
            val location = IntArray(2).also(view::getLocationOnScreen)
            val fraction = if (start == forward) 0.65f else 0.35f
            return floatArrayOf(location[0] + view.width * (if (horizontal) fraction else 0.5f),
                location[1] + view.height * (if (horizontal) 0.5f else fraction))
        }
        // Stay inside the media area, away from action bars and system edges.
        return GeneralSwipeAction(Swipe.FAST, { point(it, true) }, { point(it, false) }, Press.FINGER)
    }

    private fun awaitViewerReady(scenario: ActivityScenario<DetailActivity>, uri: Uri) {
        var lastState = "Aguardando visualizador"
        await(description = { lastState }) {
            var ready = false
            scenario.onActivity { activity ->
                val queue = activity.javaClass.getDeclaredField("queueController")
                    .apply { isAccessible = true }.get(activity) as DetailMediaQueueController
                val switching = activity.javaClass.getDeclaredField("switchingItem")
                    .apply { isAccessible = true }.getBoolean(activity)
                val item = queue.current()
                val root = activity.window.decorView
                val player = root.findViewWithTag<PlayerView>("detail_video_player")?.player
                val image = descendants(root).filterIsInstance<CoilZoomImageView>().firstOrNull()
                lastState = "Esperado=$uri, atual=${item.uri} (${item.name}), fila=${queue.items.size}, " +
                    "switching=$switching, duração=${player?.duration}, foco=${activity.hasWindowFocus()}, " +
                    "zoom=${image?.zoomable?.transformState?.value?.scaleX}, mínimo=${image?.zoomable?.minScaleState?.value}"
                // RESUMED can precede the window receiving input after ActivityScenario's
                // temporary activity exits. Keep destination assertions unchanged.
                if (queue.items.size == 4 && !switching && activity.hasWindowFocus() &&
                    MediaIdentityRules.sameUri(item.uri.toString(), uri.toString())) {
                    ready = if (item.isVideo()) {
                        player?.duration?.let { it > 0L } == true
                    } else {
                        image?.let { it.drawable != null && it.width > 0 && it.height > 0 && it.alpha == 1f } == true
                    }
                }
            }
            ready
        }
        awaitAndroidInputReady()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun insertPhoto(folder: String, name: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        try {
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
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
    private fun await(description: () -> String = { "Condição do visualizador não atingida" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail(description())
    }
}
