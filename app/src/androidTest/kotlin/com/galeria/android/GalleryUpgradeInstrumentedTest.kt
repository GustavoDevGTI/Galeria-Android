package com.galeria.android

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GalleryUpgradeInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun skippedVersionsRepairOnlyDerivedMetadataOnceAndRetryAfterFailure() {
        val suffix = java.util.UUID.randomUUID().toString()
        val state = context.getSharedPreferences("upgrade-test-state-$suffix", Context.MODE_PRIVATE)
        val meta = context.getSharedPreferences("upgrade-test-meta-$suffix", Context.MODE_PRIVATE)
        try {
            state.edit().putInt("version_code", 8062).commit()
            meta.edit().putInt("catalog_model_version_visible", 3)
                .putString("media_store_generation_visible", "old")
                .putLong("catalog_fingerprint_visible", 123)
                .putLong("catalog_fingerprint_complete", 456)
                .putBoolean("catalog_dirty_after_media_action", true)
                .putStringSet("favorites", setOf("keep"))
                .putStringSet("hidden_folder_keys", setOf("Pictures/Private/"))
                .putString("custom_order_album", "keep-order")
                .putString("unknown_metadata", "keep-unknown").commit()
            val preserved = meta.all.filterKeys { it in setOf("favorites", "hidden_folder_keys", "custom_order_album", "unknown_metadata") }
            try {
                GalleryUpgradeCoordinator.recover(state, meta, 8065) { error("Falha simulada") }
                fail("A recuperação incompleta foi marcada como concluída")
            } catch (_: IllegalStateException) { }
            assertEquals(8062, state.getInt("version_code", 0))
            assertTrue(meta.contains("catalog_fingerprint_complete"))
            var attempts = 0
            assertTrue(GalleryUpgradeCoordinator.recover(state, meta, 8065) { attempts++ })
            assertFalse(meta.contains("catalog_model_version_visible"))
            assertFalse(meta.contains("catalog_fingerprint_visible"))
            assertFalse(meta.contains("catalog_fingerprint_complete"))
            assertFalse(meta.contains("catalog_dirty_after_media_action"))
            assertEquals(preserved, meta.all)
            meta.edit().putString("media_store_generation_visible", "new-valid").commit()
            assertFalse(GalleryUpgradeCoordinator.recover(state, meta, 8065) { attempts++ })
            assertEquals("new-valid", meta.getString("media_store_generation_visible", ""))
            assertEquals(1, attempts)
            assertTrue(GalleryUpgradeCoordinator.recover(state, meta, 8066) { attempts++ })
            assertEquals(preserved, meta.all)
        } finally {
            context.deleteSharedPreferences("upgrade-test-state-$suffix")
            context.deleteSharedPreferences("upgrade-test-meta-$suffix")
        }
    }

    @Test fun loadFailuresReturnToUiAndDoNotPoisonSubsequentRequests() {
        GalleryUpgradeCoordinator.ensureReady(context)
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = error("Provider indisponível")
        }
        val ready = CountDownLatch(2)
        val overview = AlbumCatalogController(failing)
        val album = AlbumMediaCatalogController(failing)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val failed: () -> Unit = {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            ready.countDown()
        }
        try {
            overview.load(AlbumCatalogOptions(false, false, emptySet(), "", MediaFilterOptions(),
                AlbumRules.SORT_NAME, false), { _, _ -> fail("Provider falhou") }, {}, failed)
            album.load(scope, AlbumMediaCatalogOptions("Pictures/Failure/", false, "",
                MediaFilterOptions(), AlbumMediaRules.GROUP_NONE, MediaSortRules.SORT_NAME, false, false),
                { fail("Provider falhou") }, {}, failed)
            assertTrue("Um carregamento ficou sem conclusão", ready.await(10, TimeUnit.SECONDS))
        } finally { overview.close(); album.close(); scope.cancel() }
        val retried = CountDownLatch(1)
        val controller = AlbumCatalogController(context)
        try {
            controller.load(AlbumCatalogOptions(false, false, emptySet(), "", MediaFilterOptions(),
                AlbumRules.SORT_NAME, false), { _, _ -> retried.countDown() }, {}, { fail("Pedido seguinte falhou") })
            assertTrue(retried.await(10, TimeUnit.SECONDS))
        } finally { controller.close() }
    }

    @Test fun markingChangesDoesNotWaitForDatabaseWorkOnMainThread() {
        val lock = checkNotNull(GalleryCatalogStore::class.java.getDeclaredField("mutationLock").apply { isAccessible = true }.get(null))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        }.apply { start() }
        val beforeVisible = GalleryCatalogStore.isCatalogDirty(context, false)
        val beforeComplete = GalleryCatalogStore.isCatalogDirty(context, true)
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val started = android.os.SystemClock.elapsedRealtime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                GalleryCatalogStore.markCatalogDirty(context)
                GalleryCatalogStore.currentMutationRevision()
            }
            assertTrue("A interface esperou o lock do banco", android.os.SystemClock.elapsedRealtime() - started < 1000)
        } finally {
            release.countDown(); holder.join()
            GalleryCatalogStore.clearCatalogDirty(context)
            if (beforeVisible || beforeComplete) {
                GalleryCatalogStore.markCatalogDirty(context)
                if (!beforeVisible) GalleryCatalogStore.clearCatalogDirty(context, false)
                if (!beforeComplete) GalleryCatalogStore.clearCatalogDirty(context, true)
            }
        }
    }
}
