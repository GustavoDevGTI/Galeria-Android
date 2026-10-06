package com.galeria.android

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OptimizationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(latch: CountDownLatch) = assertTrue("Operação não terminou", latch.await(10, TimeUnit.SECONDS))
    private fun item(index: Int, name: String = "media-$index.jpg") = MediaItem(index.toLong(),
        Uri.parse("content://media/test/$index"), name, if (index % 3 == 0) "video/mp4" else "image/jpeg",
        (index % 11).toLong() * 86400, index.toLong() * 100, "Pictures/Test/", "Pictures/Test/", "Test", index.toLong() * 1000)

    @Test fun largeAlbumDiffPreservesSelectionAndCannotResurrectRemovedMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = (0 until 10_000).map(::item)
        lateinit var adapter: MediaRecyclerAdapter
        val fullRebinds = AtomicInteger()
        val changes = AtomicInteger()
        instrumentation.runOnMainSync {
            adapter = MediaRecyclerAdapter(context, object : MediaRecyclerAdapter.Callbacks {
                override fun onMediaClick(position: Int) = Unit
                override fun onMediaPreview(position: Int) = Unit
                override fun onMediaLongClick(view: android.view.View, position: Int) = true
            })
            adapter.submit(original)
            adapter.setSelectionMode(true); adapter.selectPosition(5000)
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { fullRebinds.incrementAndGet() }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { changes.addAndGet(itemCount) }
            })
        }
        val ready = CountDownLatch(1)
        val updated = original.toMutableList().apply { set(123, item(123, "renamed.jpg")) }
        instrumentation.runOnMainSync { adapter.submitAsync(updated) { ready.countDown() } }
        await(ready)
        instrumentation.runOnMainSync {
            assertEquals(10_000, adapter.itemCount)
            assertTrue(adapter.isSelected(5000)); assertEquals(1, adapter.selectedCount())
            assertEquals("renamed.jpg", adapter.getItem(123).name)
            assertEquals(0, fullRebinds.get()); assertEquals(1, changes.get())
        }
        val reconciled = CountDownLatch(1)
        instrumentation.runOnMainSync {
            adapter.submitAsync(updated)
            adapter.removeCompletedItems(listOf(original[5000].uri.toString()))
            adapter.submitAsync(adapter.currentOrder()) { reconciled.countDown() }
        }
        await(reconciled)
        instrumentation.runOnMainSync {
            assertEquals(9999, adapter.itemCount); assertEquals(0, adapter.selectedCount())
            assertEquals(-1, adapter.positionOf(original[5000].uri.toString()))
            adapter.cancelPendingUpdates()
        }
    }

    @Test fun linearCoverMatchesExistingSortForEveryModeGroupAndDirection() {
        val source = (0 until 80).map { item(it, "${79 - it}.${if (it % 3 == 0) "mp4" else "jpg"}") }
        val custom = listOf(source[19].uri.toString(), source[27].uri.toString())
        val modes = listOf(MediaSortRules.SORT_CUSTOM, MediaSortRules.SORT_DATE, MediaSortRules.SORT_NAME,
            MediaSortRules.SORT_SIZE, MediaSortRules.SORT_DURATION, MediaSortRules.SORT_TYPE)
        val groups = listOf(AlbumMediaRules.GROUP_NONE, AlbumMediaRules.GROUP_TYPE, AlbumMediaRules.GROUP_EXTENSION,
            AlbumMediaRules.GROUP_DAY, AlbumMediaRules.GROUP_MONTH)
        for (mode in modes) for (group in groups) for (descending in listOf(false, true)) {
            val options = AlbumMediaPreparationOptions(MediaFilterOptions(), group, mode, descending)
            assertEquals("$mode/$group/$descending", AlbumMediaRules.prepare(source, options, custom).firstOrNull()?.uri,
                AlbumMediaRules.first(source, options, custom)?.uri)
            assertEquals(source[33].uri, AlbumCoverRules.choose(source, source[33].uri.toString(), options, custom)?.uri)
        }
    }

    @Test fun resolvedDurationInvalidatesBothSnapshotsWithoutRepeatedInvalidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = GalleryDatabase.get(context).galleryDao()
        val album = "optimization-duration-${java.util.UUID.randomUUID()}/"
        val uri = "content://media/test/$album"
        try {
            dao.insertMedia(listOf("visible", "complete").flatMap { scope ->
                listOf(
                    CachedMediaEntity(scope, uri, 1, "movie.mp4", "video/mp4", 100, 4096,
                        album, album, "Test", 0),
                    CachedMediaEntity(scope, "$uri/other", 2, "other.mp4", "video/mp4", 100, 4096,
                        "$album-other", "$album-other", "Other", 0)
                )
            })
            for (hidden in listOf(false, true)) {
                GalleryCatalogStore.readMedia(context, hidden)
                assertEquals(0L, GalleryCatalogStore.snapshot(hidden).first { it.uri.toString() == uri }.duration)
            }
            val revision = GalleryCatalogStore.currentMutationRevision()
            GalleryCatalogStore.saveResolvedDuration(context, uri, 12345)
            assertEquals(revision + 1, GalleryCatalogStore.currentMutationRevision())
            for (hidden in listOf(false, true)) {
                assertTrue(GalleryCatalogStore.snapshot(hidden).isEmpty())
                assertEquals(12345L, GalleryCatalogStore.readMedia(context, hidden).first { it.uri.toString() == uri }.duration)
            }
            GalleryCatalogStore.saveResolvedDuration(context, uri, 12345)
            GalleryCatalogStore.saveResolvedDuration(context, "$uri/missing", 12345)
            assertEquals(revision + 1, GalleryCatalogStore.currentMutationRevision())
            assertTrue(GalleryCatalogStore.snapshot(false).isNotEmpty())
            GalleryCatalogStore.saveResolvedDuration(context, "$uri/other", 20000)
            assertTrue(GalleryCatalogStore.snapshot(false).isEmpty())
            GalleryCatalogStore.writeAlbumMedia(context, listOf(MediaItem(1, Uri.parse(uri),
                "renamed.mp4", "video/mp4", 100, 4096, album, album, "Test", 12345)), false, album)
            val rebuilt = GalleryCatalogStore.snapshot(false)
            assertEquals("renamed.mp4", rebuilt.first { it.uri.toString() == uri }.name)
            assertEquals(20000L, rebuilt.first { it.uri.toString() == "$uri/other" }.duration)
        } finally {
            for (scope in listOf("visible", "complete")) {
                dao.deleteAlbumMedia(scope, album)
                dao.deleteAlbumMedia(scope, "$album-other")
            }
            GalleryCatalogStore.readMedia(context, false)
            GalleryCatalogStore.readMedia(context, true)
        }
    }

    @Test fun mutationsLeaveUiResponsiveRejectDuplicatesAndFinishAfterHostCloses() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(1)
        val executions = AtomicInteger(); val callbackOnMain = AtomicBoolean()
        lateinit var runner: ActivityOperationRunner
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            scenario.onActivity { activity ->
                runner = ActivityOperationRunner(activity)
                runner.run({ executions.incrementAndGet(); entered.countDown(); await(release); 7 }) {
                    assertEquals(7, it); callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper()); delivered.countDown()
                }
            }
            await(entered)
            // This main-thread pulse must work while the file worker is blocked.
            instrumentation.runOnMainSync {
                assertTrue(runner.busy)
                runner.run({ executions.incrementAndGet() }) { fail("Clique duplicado executado") }
            }
            release.countDown(); await(delivered)
            assertEquals(1, executions.get()); assertTrue(callbackOnMain.get())
            val secondEntered = CountDownLatch(1); val secondRelease = CountDownLatch(1); val finished = CountDownLatch(1)
            instrumentation.runOnMainSync {
                runner.run({ secondEntered.countDown(); await(secondRelease); finished.countDown() }) { fail("Tela fechada recebeu resultado") }
            }
            await(secondEntered)
            instrumentation.runOnMainSync { runner.close() }
            scenario.close()
            secondRelease.countDown(); await(finished)
            instrumentation.waitForIdleSync()
        } finally { release.countDown(); instrumentation.runOnMainSync { runner.close() }; scenario.close() }
    }
}
