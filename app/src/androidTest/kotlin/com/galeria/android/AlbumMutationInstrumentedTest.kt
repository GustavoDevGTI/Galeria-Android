package com.galeria.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.action.ViewActions.click
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class AlbumMutationInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
    )
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val created = mutableListOf<Uri>()
    private val suffix = System.nanoTime()
    private val source = "Pictures/MutationSource-$suffix/"
    private val target = "Pictures/MutationTarget-$suffix/"
    private val auxiliaryFiles = mutableListOf<java.io.File>()
    private val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private var previousTrashEnabled: Boolean? = null

    @Before fun allowFileManagement() {
        previousTrashEnabled = if (prefs.contains(TrashPreferences.ENABLED))
            prefs.getBoolean(TrashPreferences.ENABLED, true) else null
        prefs.edit().putBoolean(TrashPreferences.ENABLED, true).commit()
        // As with GrantPermissionRule, don't revoke during instrumentation: Android kills
        // the tested process on revocation. This grant belongs to the test installation.
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
    }

    @After fun cleanup() {
        prefs.edit().apply {
            previousTrashEnabled?.let { putBoolean(TrashPreferences.ENABLED, it) }
                ?: remove(TrashPreferences.ENABLED)
        }.commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach(Activity::finish)
        }
        created.forEach { context.contentResolver.delete(it, null, null) }
        auxiliaryFiles.asReversed().forEach { it.delete() }
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context)
        io { MediaStoreRepository.refreshMedia(context, false, force = true) }
    }

    @Test fun deletionRemovesOnlyConfirmedItemsImmediatelyAndTheyDoNotReturn() {
        val first = insert(source, "delete.png")
        insert(source, "keep.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 2)
            lateinit var removed: MediaItem
            scenario.onActivity { activity ->
                val adapter = adapter(activity)
                val selected = adapter.currentOrder().first { MediaIdentityRules.sameUri(it.uri.toString(), first.toString()) }
                removed = selected
                invoke(activity, "deleteSelected", arrayOf(List::class.java, Boolean::class.javaPrimitiveType!!),
                    listOf(selected), false)
            }
            waitForMutation(scenario)
            scenario.onActivity { activity ->
                val adapter = adapter(activity)
                val selected = removed
                assertEquals("A saída deve ser visível na própria conclusão da ação", 1, adapter.getCount())
                assertEquals("keep.png", adapter.getItem(0).name)
                // Even a late delivery of the old data must not resurrect a removed item.
                invoke(activity, "showMedia", arrayOf(List::class.java, String::class.java, Int::class.javaPrimitiveType!!),
                    listOf(selected) + adapter.currentOrder(), "", 0)
                assertEquals(1, adapter.getCount())
            }
            waitForMutation(scenario)
            waitForCount(scenario, 1)
            scenario.recreate()
            waitForCount(scenario, 1)
        }
    }

    @Test fun partialMoveStaysInSourceAndUpdatesImmediately() {
        insert(source, "move.png")
        insert(source, "stay.png")
        insert(target, "existing.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 2)
            scenario.onActivity { activity ->
                val selected = adapter(activity).currentOrder().filter { it.name == "move.png" }
                invokeMove(activity, selected)
            }
            waitForMutation(scenario)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(1, adapter(activity).getCount())
                assertEquals("stay.png", adapter(activity).getItem(0).name)
            }
            waitForCount(scenario, 1)
        }
    }

    @Test fun movingEveryFileOpensDestinationAndFinishesEmptySource() {
        insert(source, "one.png")
        insert(source, "two.png")
        insert(target, "existing.png")
        val folder = java.io.File(Environment.getExternalStorageDirectory(), source)
        auxiliaryFiles += java.io.File(folder, "notes.txt").apply { writeText("Not gallery media") }
        auxiliaryFiles += java.io.File(folder, "nested-album").apply { mkdir() }
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 2)
            scenario.onActivity { activity ->
                invokeMove(activity, adapter(activity).currentOrder())
            }
            waitForDestination(3)
            waitForMutation(scenario, finishing = true)
        }
    }

    @Test fun movingAllSearchResultsDoesNotTreatFilteredFolderAsEmpty() {
        insert(source, "matching.png")
        insert(source, "unrelated.png")
        insert(target, "existing.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 2)
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<EditText>().first().setText("matching")
            }
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                val selected = adapter(activity).currentOrder().filter { it.name == "matching.png" }
                invokeMove(activity, selected)
            }
            waitForMutation(scenario)
            scenario.onActivity { activity ->
                assertFalse("Ainda há arquivo fora da busca", activity.isFinishing)
            }
        }
    }

    @Test fun failedMovePreservesFileAndSourceScreen() {
        insert(source, "keep.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                val invalid = AlbumItem("", "", 0, null, 0, 0, 0, "")
                invoke(activity, "moveSelected", arrayOf(List::class.java, AlbumItem::class.java), adapter(activity).currentOrder(), invalid)
            }
            waitForMutation(scenario)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(1, adapter(activity).getCount())
            }
        }
    }

    @Test fun movingLastFileFromViewerOpensDestinationWithoutLeavingEmptyAlbumInBackStack() {
        insert(source, "last.png")
        insert(target, "existing.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                invoke(activity, "openDetail", arrayOf(MediaItem::class.java, Int::class.javaPrimitiveType!!), adapter(activity).getItem(0), 0)
            }
            val detail = waitForActivity<DetailActivity>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val item = invoke(detail, "currentItem", emptyArray()) as MediaItem
                invoke(detail, "moveCurrentToFolder", arrayOf(MediaItem::class.java, String::class.java), item, target)
            }
            waitForDestination(2)
        }
    }

    @Test fun deniedDeleteConfirmationKeepsCurrentFile() {
        insert(source, "keep.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                val dialog = Ui.showConfirmationDialog(activity, "Confirmar", "Teste", "Excluir") {
                    fail("Cancelar não pode executar a operação")
                }
                val attributes = requireNotNull(dialog.window).attributes
                assertEquals(android.view.Gravity.CENTER, attributes.gravity)
                dialog.cancel()
                assertEquals(1, adapter(activity).getCount())
            }
        }
    }

    @Test fun albumOverviewDropsEmptySourceButKeepsGlobalCountForMoves() {
        insert(source, "move.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                val item = adapter(activity).getItem(0)
                val overview = AlbumRecyclerAdapter(activity, object : AlbumRecyclerAdapter.Callbacks {
                    override fun onAlbumClick(position: Int) = Unit
                    override fun onAlbumLongClick(view: View, position: Int) = false
                })
                val sourceAlbum = AlbumItem(source, "Origem", 1, item, 0, 0, item.size, source)
                val global = AlbumItem("all_media", "Todas", 1, item, 0, 0, item.size, "")
                overview.submit(listOf(sourceAlbum, global))
                overview.removeCompletedItems(listOf(item), moved = true)
                assertEquals(1, overview.getCount())
                assertEquals("all_media", overview.getItem(0).key)
                assertEquals(1, overview.getItem(0).count)
                overview.removeCompletedItems(listOf(item), moved = false)
                assertEquals(0, overview.getItem(0).count)
            }
        }
    }

    private fun prepareCatalog() = io { MediaStoreRepository.refreshMedia(context, false, force = true) }

    @Test fun physicalAlbumLoadsWhileGlobalScanIsBlockedAndCatalogIsDirty() {
        insert(source, "scoped.png")
        insert(target, "unrelated.png")
        prepareCatalog()
        GalleryCatalogStore.markCatalogDirty(context)
        val lock = requireNotNull(MediaStoreRepository::class.java.getDeclaredField("scanLock").apply { isAccessible = true }.get(null))
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocker = Thread {
            synchronized(lock) {
                held.countDown()
                release.await(20, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
            launchAlbum().use { scenario ->
                // This is a dependency contract, not an emulator speed benchmark:
                // the global scan lock is intentionally held until after loading.
                waitForCount(scenario, 1)
                scenario.onActivity { assertEquals("scoped.png", adapter(it).getItem(0).name) }
                assertTrue("Um álbum não pode certificar o catálogo inteiro", GalleryCatalogStore.isCatalogDirty(context, false))
                assertEquals(1, io { GalleryCatalogStore.readAlbumMedia(context, false, target) }.size)
            }
        } finally {
            release.countDown()
            blocker.join(5000)
        }
    }

    @Test fun visibilityDialogOpensBeforeItsMetadataExecutorCanRun() {
        insert(source, "dialog.png")
        prepareCatalog()
        val release = java.util.concurrent.CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val executor = activity.javaClass.getDeclaredField("mediaLoader").apply { isAccessible = true }
                        .get(activity) as java.util.concurrent.ExecutorService
                    executor.execute { release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                    invoke(activity, "showFolderVisibilityDialog", emptyArray())
                    assertNotNull("O painel abre na própria ação, antes de consultas/recontagem",
                        activity.javaClass.getDeclaredField("visibilityDialogAdapter").apply { isAccessible = true }.get(activity))
                }
                // New physical album did not exist in the initial main delivery.
                insert(target, "late-dialog.png")
                scenario.onActivity { activity ->
                    // A main-screen delivery arriving after opening the panel must
                    // populate it too, without reopening or waiting for this executor.
                    invoke(activity, "submitAlbumsNow", arrayOf(List::class.java, String::class.java),
                        listOf(AlbumItem(target, "Novo álbum carregado", 1, null, 0, 0, 0, target)), "")
                    val rows = activity.javaClass.getDeclaredField("visibilityDialogAdapter")
                        .apply { isAccessible = true }.get(activity) as android.widget.BaseAdapter
                    assertTrue("O álbum novo entra no painel já aberto, mesmo fora da viewport",
                        (0 until rows.count).any { (rows.getItem(it) as AlbumItem).key == target })
                }
                onView(withText(R.string.main_folder_visibility)).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    assertNotNull(view)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun hiddenVideoDurationIsPersistentAndInvalidatedWhenFileChanges() {
        val file = java.io.File(context.cacheDir, "duration-$suffix.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        val metadata = context.getSharedPreferences("hidden_video_durations", Context.MODE_PRIVATE)
        var extractions = 0
        try {
            val extract: (java.io.File) -> Long = { extractions++; 7000L }
            assertEquals(7000L, HiddenVideoDurationCache(context).duration(file, extract))
            assertEquals(7000L, HiddenVideoDurationCache(context).duration(file, extract))
            assertEquals("Outro cache/processo reaproveita metadados persistidos", 1, extractions)
            file.appendBytes(byteArrayOf(3))
            assertEquals(7000L, HiddenVideoDurationCache(context).duration(file, extract))
            assertEquals(2, extractions)
        } finally {
            metadata.edit().remove(file.absolutePath).commit()
            file.delete()
        }
    }

    @Test fun automaticRefreshResumesWhenDialogReturnsWindowFocus() {
        insert(source, "initial.png")
        prepareCatalog()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil { mainAlbum(scenario, source) != null }
            var dialog: android.app.AlertDialog? = null
            scenario.onActivity { activity ->
                dialog = Ui.showConfirmationDialog(activity, "Teste de foco", "Teste", "OK") {}
            }
            waitUntil {
                var lostFocus = false
                scenario.onActivity { lostFocus = !it.hasWindowFocus() }
                lostFocus
            }
            insert(target, "created-with-dialog-open.png")
            waitUntil {
                var pending = false
                scenario.onActivity { activity ->
                    pending = activity.javaClass.getDeclaredField("mediaObserverRefreshPending").apply { isAccessible = true }
                        .getBoolean(activity)
                }
                pending
            }
            scenario.onActivity { dialog!!.dismiss() }
            waitUntil { mainAlbum(scenario, target)?.count == 1 }
        }
    }

    @Test fun revealedNomediaAlbumUpdatesFromFilesystemWithoutManualRefresh() {
        val key = "Pictures/.watch-$suffix/"
        val directory = java.io.File(Environment.getExternalStorageDirectory(), key).apply { mkdirs() }
        val marker = java.io.File(directory, ".nomedia").apply { createNewFile() }
        val first = java.io.File(directory, "first.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = java.io.File(directory, "second.png")
        auxiliaryFiles += listOf(directory, marker, first, second)
        val oldHidden = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty().toSet()
        prefs.edit().putStringSet("hidden_folder_keys", oldHidden + key).commit()
        try {
            prepareCatalog()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    TemporaryAlbumVisibility.toggle(key, true)
                    invoke(activity, "loadAlbums", emptyArray())
                }
                waitUntil { mainAlbum(scenario, key)?.count == 1 }
                // No scanFile/update/refresh gesture: only a filesystem event.
                second.writeBytes(byteArrayOf(4, 5, 6))
                waitUntil { mainAlbum(scenario, key)?.count == 2 }
                assertTrue(key in prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty())
                val overview = io { MediaStoreRepository.queryOverviewMedia(context, true, setOf(key), false) }
                val hidden = AutomaticHiddenAlbums.keysForMedia(context, overview,
                    HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())) + oldHidden + key
                assertTrue(VirtualAlbumRules.mediaForAlbum(overview, VirtualAlbumRules.RECENT_KEY, emptySet(), hidden)
                    .none { it.albumKey == key })
            }
        } finally {
            prefs.edit().putStringSet("hidden_folder_keys", oldHidden).commit()
        }
    }

    @Test fun repeatedMaintenanceRequestsObserveTheSameActualWork() {
        val ids = java.util.concurrent.ConcurrentLinkedQueue<java.util.UUID>()
        val completed = java.util.concurrent.CountDownLatch(2)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        repeat(2) {
            MediaScanScheduler.enqueue(context, false, false,
                onEnqueued = { ids.add(it); completed.countDown() },
                onFailure = { failed.set(true); completed.countDown() })
        }
        assertTrue(completed.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(failed.get())
        assertEquals(2, ids.size)
        assertEquals("KEEP deve observar o trabalho real, não um UUID descartado", ids.first(), ids.last())
        androidx.work.WorkManager.getInstance(context).cancelWorkById(ids.first()).result.get()
    }

    @Test fun reopeningDetectsMediaChangesWithoutADirtyFlagOrManualRefresh() {
        insert(source, "initial.png")
        // Establish the baseline before the tested insertion. Android may still
        // finish indexing a fixture after IS_PENDING=0; do not assume synchrony.
        waitUntil {
            prepareCatalog()
            io { GalleryCatalogStore.hasFreshCatalog(context, false, MediaActions.hasAllFilesAccess(context), 180_000L) }
        }
        insert(target, "added-while-closed.png")
        assertFalse("getVersion sozinho não detecta inserções comuns",
            io { GalleryCatalogStore.hasFreshCatalog(context, false, true, 180_000L) })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil { mainAlbum(scenario, target)?.count == 1 }
        }
    }

    private fun mainAlbum(scenario: ActivityScenario<MainActivity>, key: String): AlbumItem? {
        var result: AlbumItem? = null
        scenario.onActivity { activity ->
            val albums = activity.javaClass.getDeclaredField("adapter").apply { isAccessible = true }
                .get(activity) as AlbumRecyclerAdapter
            result = albums.visibleAlbumsSnapshot().firstOrNull { it.key == key }
        }
        return result
    }

    @Test fun disablingTrashPersistsAndDeletesPermanentlyWithoutPurgingOldTrash() {
        val previous = insert(target, "previous-trash.png")
        assertEquals(1, context.contentResolver.update(previous, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
        }, null, null))
        val doomed = insert(source, "delete-permanently.png")
        prepareCatalog()
        ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
            settings.onActivity { activity ->
                val toggle = descendants(activity.window.decorView).filterIsInstance<Switch>().first { switch ->
                    descendants(switch.parent as View).filterIsInstance<TextView>()
                        .any { it.text.toString() == context.getString(R.string.settings_use_trash) }
                }
                assertTrue(toggle.isChecked)
                toggle.performClick()
                assertFalse(TrashPreferences.isEnabled(activity))
            }
            settings.recreate()
            settings.onActivity { assertFalse(TrashPreferences.isEnabled(it)) }
        }
        launchAlbum().use { scenario ->
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                val adapter = adapter(activity)
                adapter.setSelectionMode(true)
                adapter.selectPosition(0)
                invoke(activity, "confirmDeleteSelected", emptyArray())
            }
            onView(withText(R.string.action_delete_permanently)).inRoot(isDialog()).perform(click())
            waitForActivity<MainActivity>()
            assertFalse(java.io.File(Environment.getExternalStorageDirectory(), source + "delete-permanently.png").exists())
            val trash = io { MediaStoreRepository.loadTrashedMedia(context) }
            assertTrue(trash.any { MediaIdentityRules.sameUri(it.uri.toString(), previous.toString()) })
            assertFalse(trash.any { MediaIdentityRules.sameUri(it.uri.toString(), doomed.toString()) })
        }
    }

    @Test fun deletingLastMediaReturnsToAlbumsAndKeepsRecoverableTrash() {
        val doomed = insert(source, "last.png")
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 1)
            scenario.onActivity { activity ->
                invoke(activity, "deleteSelected", arrayOf(List::class.java, Boolean::class.javaPrimitiveType!!),
                    adapter(activity).currentOrder(), false)
            }
            waitForActivity<MainActivity>()
            waitForMutation(scenario, finishing = true)
            assertTrue(io { MediaStoreRepository.loadTrashedMedia(context) }.any {
                MediaIdentityRules.sameUri(it.uri.toString(), doomed.toString())
            })
        }
    }

    @Test fun hiddenDialogCountsRefreshWithoutRevealingOrUnhidingAlbums() {
        val first = insert(source, "first.png")
        insert(source, "second.png")
        auxiliaryFiles += java.io.File(Environment.getExternalStorageDirectory(), source + ".nomedia")
            .apply { createNewFile() }
        prepareCatalog()
        val oldHidden = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty().toSet()
        prefs.edit().putStringSet("hidden_folder_keys", oldHidden + source).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val stale = AlbumItem(source, "Contagem oculta", 99, null, 0, 0, 0, source)
                val refreshed = io { AlbumCountRefresh.refresh(context, listOf(stale)) }
                assertEquals(2, refreshed.single().count)
                scenario.onActivity { activity ->
                    invoke(activity, "showFolderVisibilityDialog", arrayOf(List::class.java), refreshed)
                }
                onView(withText("Contagem oculta (2)")).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    assertNotNull(view)
                }
                context.contentResolver.delete(first, null, null)
                waitUntil {
                    runCatching {
                        onView(withText("Contagem oculta (1)")).inRoot(isDialog()).check { view, error ->
                            if (error != null) throw error
                            assertNotNull(view)
                        }
                    }.isSuccess
                }
                assertTrue(source in prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty())
                assertFalse(source in TemporaryAlbumVisibility.activeKeys())
            }
        } finally {
            prefs.edit().putStringSet("hidden_folder_keys", oldHidden).commit()
        }
    }
    private fun launchAlbum() = ActivityScenario.launch<AlbumMediaActivity>(Intent(context, AlbumMediaActivity::class.java).apply {
        putExtra("album_key", source)
        putExtra("album_name", "Origem")
    })
    private fun invokeMove(activity: AlbumMediaActivity, selected: List<MediaItem>) {
        invoke(activity, "moveSelected", arrayOf(List::class.java, AlbumItem::class.java), selected,
            AlbumItem(target, "Destino", 1, null, 0, 0, 0, target))
    }
    private fun adapter(activity: Activity) = activity.javaClass.getDeclaredField("adapter").apply { isAccessible = true }
        .get(activity) as MediaRecyclerAdapter
    private fun invoke(activity: Activity, name: String, types: Array<Class<*>>, vararg args: Any): Any? =
        activity.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *args)
    private fun waitForCount(scenario: ActivityScenario<AlbumMediaActivity>, count: Int) = waitUntil {
        var matches = false
        scenario.onActivity { matches = adapter(it).getCount() == count }
        matches
    }
    private fun waitForMutation(scenario: ActivityScenario<AlbumMediaActivity>, finishing: Boolean = false) {
        if (finishing) {
            // The source has already navigated away; its destroyed state is the
            // required assertion, not an attempt to call onActivity after finish.
            waitUntil { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            return
        }
        waitUntil {
            var complete = false
            scenario.onActivity {
                val runner = AlbumMediaActivity::class.java.getDeclaredField("operations").apply { isAccessible = true }.get(it) as ActivityOperationRunner
                complete = !runner.busy
            }
            complete
        }
    }
    private fun waitForDestination(count: Int) = waitUntil {
        var matches = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            matches = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AlbumMediaActivity>().any {
                    it.intent.getStringExtra("album_key") == target && adapter(it).getCount() == count
                }
        }
        matches
    }
    private inline fun <reified T : Activity> waitForActivity(): T {
        var result: T? = null
        waitUntil {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<T>().firstOrNull()
            }
            result != null
        }
        return requireNotNull(result)
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Estado esperado não apareceu", predicate())
    }
    private fun insert(path: String, name: String): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        created.add(uri)
        resolver.openOutputStream(uri)!!.use { it.write(android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", 0)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
