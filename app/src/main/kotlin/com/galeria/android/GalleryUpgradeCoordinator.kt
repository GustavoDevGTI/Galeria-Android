package com.galeria.android

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** Version changes invalidate derived scan metadata, never the user's library or choices. */
internal object GalleryUpgradeCoordinator {
    private const val REPAIR_REVISION = 1
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var preparation: FutureTask<Unit>? = null

    fun initialize(context: Context): FutureTask<Unit> = preparation ?: synchronized(this) {
        preparation ?: run {
            val app = context.applicationContext
            val ready = object : FutureTask<Unit>(Callable {
                @Suppress("DEPRECATION")
                val version = app.packageManager.getPackageInfo(app.packageName, 0).versionCode
                val state = app.getSharedPreferences("gallery_upgrade_state", Context.MODE_PRIVATE)
                val metadata = app.getSharedPreferences("gallery_catalog_meta", Context.MODE_PRIVATE)
                recover(state, metadata, version) {
                    // Old scheduled maintenance must not certify a new catalog.
                    val manager = WorkManager.getInstance(app)
                    for (name in listOf("gallery_visible_scan", "gallery_complete_scan")) {
                        manager.cancelUniqueWork(name).result.get()
                    }
                    MediaStoreRepository.invalidateCache()
                    GalleryCatalogStore.invalidateSnapshots()
                }
                Unit
            }) {
                override fun done() {
                    try { get() } catch (_: Exception) {
                        // Do not poison subsequent loads after a failed repair.
                        synchronized(this@GalleryUpgradeCoordinator) {
                            if (preparation === this) preparation = null
                        }
                    }
                }
            }
            preparation = ready
            worker.execute(ready)
            ready
        }
    }

    /** Catalog workers wait for the repair before consuming derived metadata. */
    fun ensureReady(context: Context) { initialize(context).get() }

    internal fun recover(state: SharedPreferences, metadata: SharedPreferences, version: Int,
        beforeReset: () -> Unit): Boolean {
        if (state.getInt("version_code", -1) == version &&
            state.getInt("repair_revision", 0) == REPAIR_REVISION) return false
        beforeReset()
        // Explicit whitelist: do not clear preferences for hidden folders, pins,
        // favorites, covers, custom order, access, legacy trash or thumbnails.
        val committed = metadata.edit().apply {
            for (scope in listOf("visible", "complete")) {
                remove("catalog_model_version_$scope")
                remove("media_store_generation_$scope")
                remove("media_store_version_$scope")
                remove("catalog_dirty_after_media_action_$scope")
                remove("catalog_fingerprint_$scope")
            }
            remove("catalog_dirty_after_media_action")
        }.commit()
        check(committed) { "Falha ao preparar metadados da atualização" }
        check(state.edit().putInt("version_code", version).putInt("repair_revision", REPAIR_REVISION).commit())
        return true
    }
}
