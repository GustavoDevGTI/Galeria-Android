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
        prefs.edit().remove("organizer_targets_$source").remove("organizer_reserve_$source").commit()
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE $oldAccess")
        MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
    }

    @Test fun selectionStartsWithOneDestinationAndAcceptsUpToEight() {
        launch().use { scenario ->
            waitUntil { var ready = false; scenario.onActivity { ready = !it.controller.loading }; ready }
            scenario.onActivity { activity ->
                assertEquals(0, activity.controller.stage)
                val list = activity.window.decorView.findViewWithTag<ListView>("organizer_album_picker")
                val index = (0 until list.adapter.count).first { (list.adapter.getItem(it) as AlbumItem).key == source }
                val row = list.adapter.getView(index, null, list) as ViewGroup
                assertEquals("A capa deve usar a mesma medida do painel Mover", Ui.dp(activity, 46), row.getChildAt(0).layoutParams.width)
                assertEquals("Mostrar somente o nome do álbum, sem caminho", "A Origem", (row.getChildAt(1) as android.widget.TextView).text.toString())
                list.performItemClick(null, index, index.toLong())
                assertEquals(1, activity.controller.stage)
                val destinations = activity.window.decorView.findViewWithTag<ListView>("organizer_album_picker")
                assertFalse((0 until destinations.adapter.count).any { (destinations.adapter.getItem(it) as AlbumItem).key == source })
                val start = activity.window.decorView.findViewWithTag<View>("organizer_start")
                assertFalse(start.isEnabled)
                activity.controller.toggleDestination(activity.controller.albums.first { it.key == targets[0] })
                assertTrue("Um destino já permite começar", start.isEnabled)
                activity.controller.toggleDestination(activity.controller.albums.first { it.key == targets[0] })
                assertFalse("Sem destinos não pode começar", start.isEnabled)
                repeat(7) { activity.controller.toggleDestination(activity.controller.albums.first { album -> album.key == targets[it] }) }
                assertTrue(start.isEnabled)
                activity.controller.toggleDestination(activity.controller.albums.first { it.key == targets[7] })
                activity.controller.toggleDestination(AlbumItem(prefix + "Nono/", "Nono", 0, null, 0, 0, 0, prefix + "Nono/"))
                assertEquals(8, activity.controller.destinations.size)
                assertTrue(start.isEnabled); start.performClick()
            }
            awaitBoard(scenario)
            scenario.onActivity { assertEquals(4, it.controller.media.queue.size); assertEquals(8, it.controller.destinations.size) }
        }
    }

    @Test fun everyDestinationCountDrawsRoutesSwapsAndPersistsAfterReopening() {
        for (count in 1..8) {
            var item: MediaItem? = null
            organized(count).use { scenario ->
                scenario.onActivity { activity ->
                    item = activity.controller.media.queue.first()
                    repeat(8) { index ->
                        val label = activity.window.decorView.findViewWithTag<View>("organizer_destination_$index")
                        assertEquals("Visibilidade count=$count index=$index", index < count, label.isShown)
                    }
                }
                // For one destination the rightward push must also reach that only album.
                if (count == 1) {
                    gesture(scenario, "organizer_current", "organizer_destination_0", 100)
                } else gesture(scenario, "organizer_current", "organizer_destination_${count - 1}", 100)
                waitUntil { var ready = false; scenario.onActivity { ready = it.controller.pending == 0 }; ready }
                assertEquals("Destino do gesto com $count álbuns", targets[count - 1], path(item!!.uri))
                scenario.onActivity { it.controller.undo() }
                waitUntil { path(item!!.uri) == source }
                waitUntil { var ready = false; scenario.onActivity { ready = it.controller.pending == 0 }; ready }
                if (count > 1) gesture(scenario, "organizer_destination_0", "organizer_destination_${count - 1}", 180,
                    ViewConfiguration.getLongPressTimeout() + 100L)
                scenario.recreate(); awaitBoard(scenario)
                scenario.onActivity {
                    assertEquals(count, it.controller.destinations.size)
                    assertEquals(targets[count - 1], it.controller.destinations.first().key)
                }
                screenshot("organizer-native-$count-destinations.png")
            }
            launch().use { scenario ->
                waitUntil { var ready = false; scenario.onActivity { ready = !it.controller.loading }; ready }
                scenario.onActivity { activity ->
                    activity.controller.chooseSource(activity.controller.albums.first { it.key == source })
                    assertEquals(count, activity.controller.destinations.size)
                    assertEquals(targets[count - 1], activity.controller.destinations.first().key)
                }
            }
        }
    }

    @Test fun aPushMovesImmediatelyAndUndoRestoresTheActualFileAcrossRotation() {
        organized().use { scenario ->
            var original: MediaItem? = null
            scenario.onActivity { original = it.controller.media.queue.first() }
            gesture(scenario, "organizer_current", "organizer_destination_3", 70)
            scenario.onActivity { assertEquals("A próxima mídia já deve estar no centro", 3, it.controller.media.queue.size) }
            waitUntil { var idle = false; scenario.onActivity { idle = it.controller.pending == 0 }; idle }
            assertEquals(targets[3], path(original!!.uri))
            scenario.recreate(); awaitBoard(scenario)
            scenario.onActivity { activity ->
                assertEquals(targets[3], activity.controller.lastAlbum)
                assertEquals(3, activity.controller.media.queue.size)
                activity.window.decorView.findViewWithTag<View>("organizer_undo").performClick()
            }
            waitUntil { path(original!!.uri) == source }
            waitUntil { var count = 0; scenario.onActivity { count = it.controller.media.queue.size }; count == 4 }
            screenshot("organizer-native-circle.png")
        }
    }

    @Test fun reserveMorphsInPlaceAndDraggingFromItDoesNotConsumeTheCenter() {
        organized().use { scenario ->
            var original: MediaItem? = null
            scenario.onActivity { original = it.controller.media.queue.first() }
            gesture(scenario, "organizer_current", "organizer_reserve", 220)
            scenario.onActivity { activity ->
                assertEquals(3, activity.controller.media.queue.size); assertEquals(1, activity.controller.media.reserved.size)
                activity.window.decorView.findViewWithTag<View>("organizer_reserve_button").performClick()
            }
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val reserve = activity.window.decorView.findViewWithTag<ViewGroup>("organizer_reserve")
                val carousel = activity.window.decorView.findViewWithTag<View>("organizer_reserve_carousel")
                assertTrue(carousel.isShown)
                assertEquals(View.GONE, reserve.findViewWithTag<View>("organizer_reserve_button").visibility)
                assertTrue("A reserva deve ter espaço para miniaturas grandes", reserve.height >= Ui.dp(activity, 104))
                assertTrue("O mesmo controle se expande horizontalmente", reserve.width > reserve.height)
                val position = IntArray(2); val other = IntArray(2)
                reserve.getLocationOnScreen(position); carousel.getLocationOnScreen(other)
                assertEquals(position[1], other[1])
            }
            screenshot("organizer-native-reserve.png")
            gesture(scenario, "organizer_reserved_${original!!.uri}", "organizer_destination_4", 230)
            waitUntil { var idle = false; scenario.onActivity { idle = it.controller.pending == 0 }; idle }
            scenario.onActivity { assertEquals(3, it.controller.media.queue.size); assertTrue(it.controller.media.reserved.isEmpty()); it.controller.undo() }
            waitUntil { var size = 0; scenario.onActivity { size = it.controller.media.reserved.size }; size == 1 }
            scenario.recreate(); awaitBoard(scenario)
            scenario.onActivity { assertTrue(it.controller.reserveOpen); assertEquals(1, it.controller.media.reserved.size); assertEquals(3, it.controller.media.queue.size) }
            scenario.onActivity { it.controller.undo() }
            scenario.onActivity {
                assertTrue("Desfazer a reserva após desfazer o movimento não pode deixar a mesma mídia nos dois locais", it.controller.media.reserved.isEmpty())
                assertEquals(4, it.controller.media.queue.size)
                assertEquals(4, it.controller.media.queue.map { item -> item.relativePath + item.name }.distinct().size)
            }
        }
    }

    @Test fun holdingAndDraggingSwapsDestinationsAndPersistsTheirOrder() {
        organized().use { scenario ->
            gesture(scenario, "organizer_destination_0", "organizer_destination_5", 220, ViewConfiguration.getLongPressTimeout() + 150L)
            scenario.onActivity {
                assertEquals(targets[5], it.controller.destinations[0].key)
                assertEquals(targets[0], it.controller.destinations[5].key)
            }
            scenario.recreate(); awaitBoard(scenario)
            scenario.onActivity { assertEquals(targets[5], it.controller.destinations[0].key) }
        }
        launch().use { scenario ->
            waitUntil { var ready = false; scenario.onActivity { ready = !it.controller.loading }; ready }
            scenario.onActivity { activity ->
                activity.controller.chooseSource(activity.controller.albums.first { it.key == source })
                assertEquals(targets[5], activity.controller.destinations[0].key)
                assertEquals(targets[0], activity.controller.destinations[5].key)
            }
        }
    }

    @Test fun portraitUsesItsHeightAndTappingTheMediaOpensAReadOnlyPerspectivePreview() {
        organized().use { scenario ->
            var original: Uri? = null
            scenario.onActivity { activity ->
                original = activity.controller.media.queue.first().uri
                val root = activity.window.decorView
                val board = root.findViewWithTag<View>("organizer_board")
                val reserve = root.findViewWithTag<ViewGroup>("organizer_reserve")
                val compact = root.findViewWithTag<ViewGroup>("organizer_reserve_button")
                val undo = root.findViewWithTag<View>("organizer_undo")
                val bottomAlbum = root.findViewWithTag<View>("organizer_destination_4")
                assertTrue(reserve.height >= Ui.dp(activity, 104))
                assertTrue(reserve.width <= reserve.height * 1.1f)
                assertTrue("A miniatura ocupa a maior parte da reserva", compact.getChildAt(0).height > reserve.height * .6f)
                assertTrue("Desfazer fica abaixo do círculo", undo.y > bottomAlbum.y + bottomAlbum.height)
                assertTrue("O círculo aproveita também a metade inferior", undo.y - bottomAlbum.y < board.height * .22f)
                assertTrue("Desfazer permanece junto à base útil", board.height - undo.bottom <= Ui.dp(activity, 12))
            }
            screenshot("organizer-refined-portrait.png")
            awaitAndroidInputReady()
            gesture(scenario, "organizer_current", "organizer_current", 80)
            waitUntil {
                var open = false
                scenario.onActivity { activity ->
                    val image = activity.window.decorView.findViewWithTag<View>("organizer_preview")
                    open = image != null && image.isShown && image.rotationY <= -5f
                }
                open
            }
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val image = root.findViewWithTag<View>("organizer_preview")
                val current = root.findViewWithTag<View>("organizer_current")
                assertTrue(image.width * image.scaleX > current.width * 1.5f)
                assertTrue(root.findViewWithTag<View>("organizer_preview_fog").alpha > .42f)
                assertEquals(4, activity.controller.media.queue.size)
                assertEquals(original, activity.controller.media.queue.first().uri)
                assertEquals(source, path(original!!))
            }
            screenshot("organizer-refined-preview.png")
            androidx.test.espresso.Espresso.pressBack()
            waitUntil {
                var closed = false
                scenario.onActivity { closed = it.window.decorView.findViewWithTag<View>("organizer_preview") == null }
                closed
            }
            scenario.onActivity {
                assertEquals(1f, it.window.decorView.findViewWithTag<View>("organizer_current").alpha, .001f)
                assertEquals(original, it.controller.media.queue.first().uri)
            }
            gesture(scenario, "organizer_current", "organizer_destination_0", 120)
            waitUntil { path(original!!) == targets[0] }
        }
    }

    @Test fun destinationCountsFollowSuccessfulMovesReorderingRotationAndUndo() {
        organized().use { scenario ->
            repeat(2) {
                gesture(scenario, "organizer_current", "organizer_destination_0", 100)
                waitUntil { var idle = false; scenario.onActivity { idle = it.controller.pending == 0 }; idle }
            }
            scenario.onActivity {
                assertEquals(2, it.controller.sentCount(targets[0]))
                assertEquals(0, it.controller.sentCount(targets[1]))
                assertEquals("2", it.window.decorView.findViewWithTag<android.widget.TextView>("organizer_destination_count_0").text.toString())
            }
            gesture(scenario, "organizer_destination_0", "organizer_destination_1", 200, ViewConfiguration.getLongPressTimeout() + 100L)
            scenario.recreate(); awaitBoard(scenario)
            scenario.onActivity {
                assertEquals(targets[0], it.controller.destinations[1].key)
                assertEquals("2", it.window.decorView.findViewWithTag<android.widget.TextView>("organizer_destination_count_1").text.toString())
            }
            for (expected in 1 downTo 0) {
                scenario.onActivity { it.controller.undo() }
                waitUntil {
                    var done = false
                    scenario.onActivity { done = it.controller.pending == 0 && it.controller.sentCount(targets[0]) == expected }
                    done
                }
            }
            scenario.onActivity { assertEquals(4, it.controller.media.queue.size) }
        }
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

    @Test fun aBlockedOrFailedMoveNeverBlocksPromotionOrLosesQueueOrder() {
        val gate = CountDownLatch(1)
        val controller = OrganizerController(context) { _, _, _, _ -> gate.await(5, TimeUnit.SECONDS); null }
        try {
            instrumentation.runOnMainSync { controller.loadAlbums(listOf(source) + targets, false, source) }
            waitUntil { !controller.loading }
            instrumentation.runOnMainSync {
                controller.chooseSource(controller.albums.first { it.key == source })
                targets.forEach { key -> controller.toggleDestination(controller.albums.first { it.key == key }) }
                controller.begin()
            }
            waitUntil { controller.stage == 2 }
            val original = controller.media.queue.toList()
            instrumentation.runOnMainSync {
                assertTrue(controller.send(original[0], false, 0))
                assertEquals("Movimento pendente não conta como concluído", 0, controller.sentCount(targets[0]))
                assertEquals(original[1], controller.media.queue.first())
                assertTrue(controller.send(original[1], false, 1))
                assertEquals(original[2], controller.media.queue.first())
                assertEquals(2, controller.pending)
            }
            gate.countDown()
            waitUntil { controller.pending == 0 }
            instrumentation.runOnMainSync {
                assertEquals(original, controller.media.queue)
                assertNull(controller.lastAlbum); assertFalse(controller.canUndo)
            }
            original.forEach { assertEquals(source, path(it.uri)) }
        } finally { gate.countDown(); instrumentation.runOnMainSync { controller.close() } }
    }

    @Test fun carouselScrollsHorizontallyBeforeSelectingAndDragging() {
        repeat(10) { insert(source, "extra$it.png", Color.rgb(150, 100 + it * 10, 140)) }
        organized().use { scenario ->
            scenario.onActivity { activity ->
                repeat(12) { activity.controller.reserve() }
                activity.window.decorView.findViewWithTag<View>("organizer_reserve_button").performClick()
            }
            SystemClock.sleep(300)
            var before = -1
            scenario.onActivity { activity ->
                val carousel = activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("organizer_reserve_carousel")
                assertTrue(carousel.childCount < 12)
                before = (carousel.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
            }
            val start = IntArray(2); var w = 0; var h = 0
            scenario.onActivity {
                val carousel = it.window.decorView.findViewWithTag<View>("organizer_reserve_carousel")
                carousel.getLocationOnScreen(start); w = carousel.width; h = carousel.height
            }
            val down = SystemClock.uptimeMillis()
            repeat(8) { index ->
                val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 7 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, start[0] + w * (.85f - index / 7f * .7f), start[1] + h / 2f, 0)
                instrumentation.sendPointerSync(event); event.recycle(); SystemClock.sleep(40)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val carousel = activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("organizer_reserve_carousel")
                assertTrue((carousel.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() > before)
                assertEquals(12, activity.controller.media.reserved.size)
                assertEquals(2, activity.controller.media.queue.size)
            }
        }
    }

    @Test fun landscapeKeepsTheQueueAndAllEightDestinationsOnScreen() {
        organized().use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUntil {
                var ready = false
                scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                ready
            }
            awaitBoard(scenario)
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val board = root.findViewWithTag<View>("organizer_board")
                val boardPosition = IntArray(2); board.getLocationOnScreen(boardPosition)
                for (index in 0..7) {
                    val album = root.findViewWithTag<View>("organizer_destination_$index")
                    val position = IntArray(2); album.getLocationOnScreen(position)
                    assertTrue(album.isShown)
                    assertTrue(position[1] >= boardPosition[1])
                    assertTrue(position[1] + album.height <= boardPosition[1] + board.height)
                }
                assertEquals(4, activity.controller.media.queue.size)
                assertTrue(root.findViewWithTag<View>("organizer_reserve").isShown)
            }
            screenshot("organizer-native-landscape.png")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUntil {
                var ready = false
                scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                ready
            }
        }
    }

    private fun launch() = ActivityScenario.launch<OrganizerActivity>(OrganizerActivity.intent(context, listOf(source) + targets, false, source))
    private fun organized(count: Int = 8): ActivityScenario<OrganizerActivity> = launch().also { scenario ->
        waitUntil { var ready = false; scenario.onActivity { ready = !it.controller.loading }; ready }
        scenario.onActivity { activity ->
            activity.controller.chooseSource(activity.controller.albums.first { it.key == source })
            activity.controller.destinations.toList().forEach(activity.controller::toggleDestination)
            targets.take(count).forEach { key -> activity.controller.toggleDestination(activity.controller.albums.first { it.key == key }) }
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
        val start = IntArray(2); val end = IntArray(2)
        scenario.onActivity { activity ->
            val a = requireNotNull(activity.window.decorView.findViewWithTag<View>(from)); a.getLocationOnScreen(start)
            val b = requireNotNull(activity.window.decorView.findViewWithTag<View>(to)); b.getLocationOnScreen(end)
            start[0] += a.width / 2; start[1] += a.height / 2; end[0] += b.width / 2; end[1] += b.height / 2
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            start[0] + (end[0] - start[0]) * fraction, start[1] + (end[1] - start[1]) * fraction, 0).let {
            instrumentation.sendPointerSync(it); it.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        if (hold > 0) SystemClock.sleep(hold)
        repeat(6) { SystemClock.sleep(duration / 6); send(MotionEvent.ACTION_MOVE, (it + 1) / 6f) }
        send(MotionEvent.ACTION_UP, 1f); instrumentation.waitForIdleSync()
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
