package com.galeria.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class OrganizerInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val created = ArrayList<Uri>()
    private val prefix = "Pictures/Organizer-${System.nanoTime()}/"
    private val source = prefix + "A Origem/"
    private val targets = (0..7).map { prefix + "B Destino $it/" }
    private val prefs get() = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private var oldAccess = "default"

    @Before fun prepare() {
        oldAccess = Regex("MANAGE_EXTERNAL_STORAGE: (\\w+)").find(shell("appops get ${context.packageName} MANAGE_EXTERNAL_STORAGE"))?.groupValues?.get(1) ?: "default"
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        repeat(4) { insert(source, "photo$it.png", Color.rgb(70 + it * 35, 140, 180)) }
        targets.forEach { insert(it, "cover.png", Color.rgb(160, 110, 150)) }
        MediaStoreRepository.invalidateCache()
    }

    @After fun cleanup() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        // Direct moves may be rescanned with a new MediaStore row; remove only this fixture's subtree.
        context.contentResolver.delete(MediaStore.Files.getContentUri("external"),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$prefix%"))
        prefs.edit().remove("organizer_slots_$source").remove("organizer_targets_$source").remove("organizer_reserve_$source").commit()
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE $oldAccess")
        MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
    }

    @Test fun sourceSelectionEntersTheWheelWithAFixedTrashSlot() {
        launch().use { scenario ->
            waitUntil { var done = false; scenario.onActivity { done = !it.controller.loading }; done }
            scenario.onActivity { activity ->
                val list = activity.window.decorView.findViewWithTag<ListView>("organizer_album_picker")
                val index = (0 until list.adapter.count).first { (list.adapter.getItem(it) as AlbumItem).key == source }
                list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
            }
            awaitBoard(scenario)
            scenario.onActivity { activity ->
                assertEquals(8, activity.controller.destinations.size)
                assertEquals(OrganizerController.TRASH_KEY, activity.controller.destinations[4].key)
                assertTrue(activity.window.decorView.findViewWithTag<View>("organizer_new_folder").isShown)
                assertEquals(7, activity.controller.destinations.count { OrganizerController.isEmpty(it) })
            }
        }
    }

    @Test fun pushIsOnlyAPlanUntilSaveAndUndoDoesNotTouchTheFile() {
        organized().use { scenario ->
            lateinit var original: Uri
            scenario.onActivity { original = it.controller.media.queue.first().uri }
            gesture(scenario, "organizer_current", "organizer_destination_0", 100)
            scenario.onActivity {
                assertEquals(3, it.controller.media.queue.size)
                assertEquals(1, it.controller.unsavedCount)
                assertEquals(1, it.controller.sentCount(targets[0]))
                assertEquals(source, path(original))
                it.controller.undo()
                assertEquals(4, it.controller.media.queue.size)
                assertFalse(it.controller.hasChanges)
                it.controller.send(it.controller.media.queue.first(), false, 0)
                it.controller.save()
            }
            waitUntil { path(original) == targets[0] }
            waitIdle(scenario)
            scenario.onActivity { assertFalse(it.controller.hasChanges); assertFalse(it.controller.canUndo) }
        }
    }

    @Test fun destinationIsCapturedBeforeAnAlbumIsRemovedOrReplaced() {
        organized().use { scenario ->
            lateinit var original: Uri
            scenario.onActivity {
                original = it.controller.media.queue.first().uri
                it.controller.send(it.controller.media.queue.first(), false, 0)
                it.controller.removeDestination(0)
                it.controller.place(it.controller.albums.first { a -> a.key == targets[7] }, 0)
            }
            scenario.recreate(); awaitBoard(scenario)
            scenario.onActivity { assertEquals(1, it.controller.unsavedCount); it.controller.save() }
            waitUntil { path(original) == targets[0] }; waitIdle(scenario)
        }
    }

    @Test fun drawerReceivesAndReturnsAlbumsByRealDragWithoutMovingTrash() {
        organized().use { scenario ->
            gesture(scenario, "organizer_destination_0", "organizer_drawer_handle", 400, ViewConfiguration.getLongPressTimeout() + 150L)
            waitUntil { var empty = false; scenario.onActivity { empty = OrganizerController.isEmpty(it.controller.destinations[0]) }; empty }
            gesture(scenario, "organizer_drawer_${targets[0]}", "organizer_destination_1", 400, ViewConfiguration.getLongPressTimeout() + 150L)
            waitUntil { var placed = false; scenario.onActivity { placed = it.controller.destinations[1].key == targets[0] }; placed }
            scenario.onActivity {
                val before = it.controller.destinations.toList()
                it.controller.swap(1, 4); it.controller.removeDestination(4)
                it.controller.place(it.controller.albums.first { a -> a.key == targets[7] }, 4)
                assertEquals(before, it.controller.destinations)
            }
            screenshot("organizer-drawer.png")
        }
    }

    @Test fun drawerToggleAndPortraitLayoutNeverCoverTheWheel() {
        organized().use { scenario ->
            fun check() = scenario.onActivity { activity ->
                val root = activity.window.decorView
                val drawer = root.findViewWithTag<View>("organizer_album_drawer")
                val trash = root.findViewWithTag<View>("organizer_destination_4")
                val undo = root.findViewWithTag<View>("organizer_undo")
                assertTrue(trash.bottom <= drawer.top)
                assertTrue(undo.bottom < root.findViewWithTag<View>("organizer_destination_0").top)
            }
            check()
            screenshot("organizer-drawer-layout.png")
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("organizer_drawer_handle").performClick() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(350); check()
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("organizer_drawer_handle").performClick() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(350); check()
        }
    }

    @Test fun photoPreviewRemainsStraightAndClosesWithoutChangingTheQueue() {
        organized().use { scenario ->
            gesture(scenario, "organizer_current", "organizer_current", 80)
            waitUntil { var open = false; scenario.onActivity { open = it.window.decorView.findViewWithTag<View>("organizer_preview")?.scaleX == 1f }; open }
            scenario.onActivity {
                val preview = it.window.decorView.findViewWithTag<View>("organizer_preview")
                assertEquals(0f, preview.rotationX, .001f); assertEquals(0f, preview.rotationY, .001f); assertEquals(0f, preview.rotation, .001f)
                assertEquals(4, it.controller.media.queue.size)
            }
            screenshot("organizer-straight-preview.png")
            androidx.test.espresso.Espresso.pressBack()
            waitUntil { var closed = false; scenario.onActivity { closed = it.window.decorView.findViewWithTag<View>("organizer_preview") == null }; closed }
        }
    }

    @Test fun reserveStillMorphsInPlaceAndSendingItOnlyStagesAnAction() {
        organized().use { scenario ->
            scenario.onActivity { it.controller.reserve() }
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("organizer_reserve_button").performClick() }
            waitUntil { var shown = false; scenario.onActivity { shown = it.window.decorView.findViewWithTag<View>("organizer_reserve_carousel").isShown }; shown }
            scenario.onActivity {
                val reserved = it.controller.media.reserved.first()
                assertTrue(it.controller.send(reserved, true, 0))
                assertEquals(3, it.controller.media.queue.size)
                assertEquals(source, path(reserved.uri))
                it.controller.undo(); assertEquals(1, it.controller.media.reserved.size)
            }
        }
    }

    @Test fun backOffersDiscardAndLeavesFilesUntouched() {
        organized().use { scenario ->
            lateinit var uri: Uri
            scenario.onActivity { uri = it.controller.media.queue.first().uri; it.controller.send(it.controller.media.queue.first(), false, 0) }
            androidx.test.espresso.Espresso.pressBack()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Continuar")).perform(androidx.test.espresso.action.ViewActions.click())
            scenario.onActivity { assertTrue(it.controller.hasChanges) }
            androidx.test.espresso.Espresso.pressBack()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Sair sem salvar")).perform(androidx.test.espresso.action.ViewActions.click())
            assertEquals(source, path(uri))
        }
    }

    @Test fun trashIsRecoverableWhenEnabledAndPermanentOnlyAfterSavingWhenDisabled() {
        val was = prefs.getBoolean(TrashPreferences.ENABLED, true)
        try {
            for (enabled in listOf(true, false)) {
                prefs.edit().putBoolean(TrashPreferences.ENABLED, enabled).commit()
                organized().use { scenario ->
                    lateinit var uri: Uri
                    scenario.onActivity {
                        uri = it.controller.media.queue.first().uri
                        it.controller.send(it.controller.media.queue.first(), false, 4)
                        assertEquals(source, path(uri))
                        it.controller.save()
                    }
                    waitIdle(scenario)
                    if (enabled) {
                        val trashed = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), android.os.Bundle().apply {
                            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        }, null)?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: false
                        assertTrue(trashed)
                    } else assertNull(path(uri))
                }
            }
        } finally { prefs.edit().putBoolean(TrashPreferences.ENABLED, was).commit() }
    }

    @Test fun failedSaveRetainsThePlanForRetryOrUndoAndDoesNotBlockTheQueue() {
        lateinit var session: OrganizerController
        instrumentation.runOnMainSync {
            session = OrganizerController(context, move = { _, _, _, _ -> null })
            session.loadAlbums(listOf(source) + targets, false, source)
        }
        waitUntil { !session.loading }
        instrumentation.runOnMainSync { session.chooseSource(session.albums.first { it.key == source }); session.begin() }
        waitUntil { session.stage == 2 }
        instrumentation.runOnMainSync {
            session.place(session.albums.first { it.key == targets[0] }, 0)
            session.send(session.media.queue.first(), false, 0)
            assertEquals(3, session.media.queue.size); session.save()
        }
        waitUntil { !session.saving }
        instrumentation.runOnMainSync {
            assertEquals(1, session.unsavedCount); session.undo()
            assertEquals(4, session.media.queue.size); session.close()
        }
    }

    @Test fun videoPreviewUsesOnlyPlayPauseAndProgressInTheSameBackdrop() {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "preview.mp4"); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, source); put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        created.add(uri)
        instrumentation.context.assets.open("playback-sample.mp4").use { input -> context.contentResolver.openOutputStream(uri)!!.use(input::copyTo) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        MediaStoreRepository.invalidateCache()
        organized().use { scenario ->
            scenario.onActivity {
                assertTrue(it.controller.media.queue.any { item -> item.isVideo() })
                while (!it.controller.media.queue.first().isVideo()) it.controller.reserve()
            }
            gesture(scenario, "organizer_current", "organizer_current", 80)
            waitUntil { var playing = false; scenario.onActivity { playing = it.window.decorView.findViewWithTag<View>("organizer_video_preview")?.isShown == true }; playing }
            waitUntil { var advanced = false; scenario.onActivity { advanced = it.window.decorView.findViewWithTag<android.widget.SeekBar>("organizer_video_seek").progress > 0 }; advanced }
            scenario.onActivity {
                val root = it.window.decorView
                assertTrue(root.findViewWithTag<View>("organizer_video_seek").isShown)
                root.findViewWithTag<View>("organizer_video_play_pause").performClick()
                assertTrue(root.findViewWithTag<View>("organizer_preview_fog").isShown)
                assertFalse(it.controller.hasChanges)
            }
            screenshot("organizer-video-preview.png")
        }
    }

    @Test fun scrollingTheDrawerKeepsTheWheelInPlace() {
        organized(0).use { scenario ->
            var wheelTop = 0
            scenario.onActivity {
                wheelTop = it.window.decorView.findViewWithTag<View>("organizer_destination_0").top
                it.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("organizer_drawer_list").scrollBy(0, Ui.dp(it, 600))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val list = it.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("organizer_drawer_list")
                assertTrue((list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition() > 0)
                assertEquals(wheelTop, it.window.decorView.findViewWithTag<View>("organizer_destination_0").top)
            }
            screenshot("organizer-drawer-scroll.png")
        }
    }

    @Test fun backSaveAppliesThePlanBeforeLeaving() {
        organized().use { scenario ->
            lateinit var uri: Uri
            scenario.onActivity { uri = it.controller.media.queue.first().uri; it.controller.send(it.controller.media.queue.first(), false, 0) }
            androidx.test.espresso.Espresso.pressBack()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Salvar e sair")).perform(androidx.test.espresso.action.ViewActions.click())
            waitUntil { path(uri) == targets[0] }
        }
    }

    @Test fun landscapeKeepsTheWheelAboveTheScrollableDrawer() {
        organized().use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUntil { var ready = false; scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }; ready }
            awaitBoard(scenario)
            scenario.onActivity {
                val root = it.window.decorView
                val drawer = root.findViewWithTag<View>("organizer_album_drawer")
                assertTrue(root.findViewWithTag<View>("organizer_destination_4").bottom <= drawer.top)
                assertEquals(4, it.controller.media.queue.size)
            }
            screenshot("organizer-drawer-landscape.png")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUntil { var ready = false; scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }; ready }
        }
    }
    @Test fun partialSaveRetriesOnlyFailedFiles() {
        lateinit var session: OrganizerController
        lateinit var second: Uri
        lateinit var first: Uri
        val attempts = java.util.concurrent.ConcurrentHashMap<Uri, Int>()
        instrumentation.runOnMainSync {
            session = OrganizerController(context, move = { ctx, item, target, name ->
                val count = attempts.merge(item.uri, 1) { a, b -> a + b }!!
                if (item.uri == second && count == 1) null else OrganizerMediaMover.move(ctx, item, target, name)
            })
            session.loadAlbums(listOf(source) + targets, false, source)
        }
        waitUntil { !session.loading }
        instrumentation.runOnMainSync { session.chooseSource(session.albums.first { it.key == source }); session.begin() }
        waitUntil { session.stage == 2 }
        instrumentation.runOnMainSync {
            session.place(session.albums.first { it.key == targets[0] }, 0)
            first = session.media.queue[0].uri; second = session.media.queue[1].uri
            repeat(2) { session.send(session.media.queue.first(), false, 0) }; session.save()
        }
        waitUntil { !session.saving }
        instrumentation.runOnMainSync {
            assertEquals(1, session.unsavedCount); assertEquals(targets[0], path(first)); assertEquals(source, path(second))
            session.save()
        }
        waitUntil { !session.saving }
        instrumentation.runOnMainSync {
            assertFalse(session.hasChanges); assertEquals(1, attempts[first]); assertEquals(2, attempts[second]); session.close()
        }
    }
    @Test fun priorDestinationsMigrateToSevenSlotsAndRemainAfterReopening() {
        prefs.edit().putString("organizer_targets_$source", org.json.JSONArray(targets).toString()).commit()
        organized(0).use { scenario ->
            // The helper clears slots; explicitly reopen the migration input to inspect it.
            prefs.edit().remove("organizer_slots_$source").commit()
            scenario.onActivity { it.controller.chooseSource(it.controller.albums.first { a -> a.key == source }) }
            scenario.onActivity {
                assertEquals(OrganizerController.TRASH_KEY, it.controller.destinations[4].key)
                assertEquals(targets.take(7), it.controller.destinations.filter { a -> a.key != OrganizerController.TRASH_KEY }.map { a -> a.key })
                it.controller.swap(0, 7)
            }
        }
        launch().use { scenario ->
            waitUntil { var done = false; scenario.onActivity { done = !it.controller.loading }; done }
            scenario.onActivity {
                it.controller.chooseSource(it.controller.albums.first { a -> a.key == source })
                assertEquals(targets[6], it.controller.destinations[0].key)
                assertEquals(OrganizerController.TRASH_KEY, it.controller.destinations[4].key)
            }
        }
    }
    private fun waitIdle(scenario: ActivityScenario<OrganizerActivity>) = waitUntil {
        var done = false; scenario.onActivity { done = !it.controller.saving }; done
    }
    @Test fun collisionsAndInvalidPathsNeverOverwriteExistingMedia() {
        val collision = insert(targets[0], "photo0.png", Color.RED)
        val originalUri = created.first()
        val item = MediaItem(ContentUris.parseId(originalUri), originalUri, "photo0.png", "image/png", 0, 1, source, source, "Origem")
        assertNull(OrganizerMediaMover.move(context, item, "Pictures/../../escape/"))
        assertEquals(source, path(originalUri))
        val moved = requireNotNull(OrganizerMediaMover.move(context, item, targets[0]))
        assertEquals(targets[0], path(moved.uri))
        assertNotEquals("photo0.png", moved.name)
        assertEquals(targets[0], path(collision))
        val restored = requireNotNull(OrganizerMediaMover.move(context, moved, source, item.name))
        assertEquals(source, path(restored.uri)); assertEquals(item.name, restored.name)
    }

    @Test fun directFileMovesAndUndoUseTheActualRenamedFile() {
        val uri = created.first()
        val file = requireNotNull(MediaActions.fileFromMediaStore(context, uri))
        val item = MediaItem(ContentUris.parseId(uri), Uri.fromFile(file), file.name, "image/png", 0, file.length(), source, source, "Origem")
        val moved = requireNotNull(OrganizerMediaMover.move(context, item, targets[1]))
        assertEquals("file", moved.uri.scheme)
        assertFalse(file.exists()); assertTrue(File(requireNotNull(moved.uri.path)).isFile)
        val restored = requireNotNull(OrganizerMediaMover.move(context, moved, source, item.name))
        assertEquals(file.canonicalFile, File(requireNotNull(restored.uri.path)).canonicalFile)
        assertTrue(file.isFile)
    }

    @Test fun contrastingHighlightFollowsLightDarkBlackAndWhiteThemes() {
        val oldTheme = prefs.getInt("theme_color", Color.BLACK)
        try {
            for (color in listOf(Color.BLACK, Color.WHITE, Color.rgb(40, 70, 95), Color.rgb(200, 220, 240))) {
                prefs.edit().putInt("theme_color", color).commit()
                val fill = OrganizerColors.highlight(context)
                if (color == Color.BLACK) assertEquals(Color.WHITE, fill)
                if (color == Color.WHITE) assertEquals(Color.BLACK, fill)
                assertTrue(if (Ui.darkMode(context)) Ui.luminance(fill) > .56 else Ui.luminance(fill) < .56)
                assertEquals(if (Ui.luminance(fill) > .56) Color.BLACK else Color.WHITE, OrganizerColors.highlightText(context))
            }
        } finally { prefs.edit().putInt("theme_color", oldTheme).commit() }
    }

    private fun launch() = ActivityScenario.launch<OrganizerActivity>(OrganizerActivity.intent(context, listOf(source) + targets, false, source))
    private fun organized(count: Int = 7): ActivityScenario<OrganizerActivity> = launch().also { scenario ->
        waitUntil { var ready = false; scenario.onActivity { ready = !it.controller.loading }; ready }
        scenario.onActivity { activity ->
            activity.controller.chooseSource(activity.controller.albums.first { it.key == source })
            (0..7).filter { it != 4 }.forEach(activity.controller::removeDestination)
            (0..7).filter { it != 4 }.take(count).forEachIndexed { index, slot ->
                activity.controller.place(activity.controller.albums.first { it.key == targets[index] }, slot)
            }
            activity.controller.begin()
        }
        awaitBoard(scenario)
    }
    private fun awaitBoard(scenario: ActivityScenario<OrganizerActivity>) {
        waitUntil {
            var ready = false
            scenario.onActivity { activity ->
                val current = activity.window.decorView.findViewWithTag<ImageView>("organizer_current")
                ready = activity.controller.stage == 2 && current != null && current.isShown && current.drawable != null && current.width > 0 && current.hasWindowFocus()
            }
            ready
        }
    }
    private fun gesture(scenario: ActivityScenario<OrganizerActivity>, from: String, to: String, duration: Long, hold: Long = 0) {
        awaitAndroidInputReady()
        val start = IntArray(2); val end = IntArray(2)
        scenario.onActivity { activity ->
            val a = requireNotNull(activity.window.decorView.findViewWithTag<View>(from)); a.getLocationOnScreen(start)
            val b = requireNotNull(activity.window.decorView.findViewWithTag<View>(to)); b.getLocationOnScreen(end)
            start[0] += a.width / 2; start[1] += a.height / 2; end[0] += b.width / 2; end[1] += b.height / 2
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            start[0] + (end[0] - start[0]) * fraction, start[1] + (end[1] - start[1]) * fraction, 0).let {
            if (hold > 0) assertTrue("Evento do arrasto aceito pelo Android", instrumentation.uiAutomation.injectInputEvent(it, true))
            else instrumentation.sendPointerSync(it)
            it.recycle()
        }
        try {
            send(MotionEvent.ACTION_DOWN, 0f)
            if (hold > 0) SystemClock.sleep(hold)
            repeat(6) { SystemClock.sleep(duration / 6); send(MotionEvent.ACTION_MOVE, (it + 1) / 6f) }
            send(MotionEvent.ACTION_UP, 1f); instrumentation.waitForIdleSync()
        } catch (error: Throwable) {
            File(context.getExternalFilesDir(null), "organizer-input-failure.txt").writeText(shell("dumpsys input"))
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, end[0].toFloat(), end[1].toFloat(), 0).let {
                instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
            throw error
        }
    }
    private fun path(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun insert(folder: String, name: String, color: Int): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        created.add(uri); return uri
    }
    private fun waitUntil(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (test()) return; SystemClock.sleep(80) }
        assertTrue("Estado esperado não apareceu", test())
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
