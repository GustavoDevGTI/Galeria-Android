package com.galeria.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

    @Before fun allowFileManagement() {
        // As with GrantPermissionRule, don't revoke during instrumentation: Android kills
        // the tested process on revocation. This grant belongs to the test installation.
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach(Activity::finish)
        }
        created.forEach { context.contentResolver.delete(it, null, null) }
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
            scenario.onActivity { activity ->
                val adapter = adapter(activity)
                val selected = adapter.currentOrder().first { MediaIdentityRules.sameUri(it.uri.toString(), first.toString()) }
                invoke(activity, "deleteSelected", arrayOf(List::class.java), listOf(selected))
                assertEquals("A saída deve ser visível na própria conclusão da ação", 1, adapter.getCount())
                assertEquals("keep.png", adapter.getItem(0).name)
                // Even a late delivery of the old data must not resurrect a removed item.
                invoke(activity, "showMedia", arrayOf(List::class.java, String::class.java, Int::class.javaPrimitiveType!!),
                    listOf(selected) + adapter.currentOrder(), "", 0)
                assertEquals(1, adapter.getCount())
            }
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
        prepareCatalog()
        launchAlbum().use { scenario ->
            waitForCount(scenario, 2)
            scenario.onActivity { activity ->
                invokeMove(activity, adapter(activity).currentOrder())
                assertTrue(activity.isFinishing)
            }
            waitForDestination(3)
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
