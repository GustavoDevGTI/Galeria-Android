package com.galeria.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import androidx.work.WorkInfo

class MediaScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val includeHidden = inputData.getBoolean(KEY_INCLUDE_HIDDEN, false)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val count = MediaStoreRepository.refreshMedia(applicationContext, includeHidden, force).size
        Result.success(workDataOf(KEY_ITEM_COUNT to count))
    } catch (_: MediaStoreRepository.CatalogChangedDuringScanException) {
        // A user-requested refresh must finish instead of spinning through
        // WorkManager backoff. Maintenance can retry without holding the UI.
        if (inputData.getBoolean(KEY_FORCE, false)) Result.failure() else Result.retry()
    } catch (_: SecurityException) {
        Result.retry()
    } catch (_: Exception) {
        Result.failure()
    }

    companion object {
        const val KEY_INCLUDE_HIDDEN = "include_hidden"
        const val KEY_FORCE = "force"
        const val KEY_ITEM_COUNT = "item_count"
    }
}

object MediaScanScheduler {
    private val schedulingExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun enqueue(context: Context, includeHidden: Boolean, replace: Boolean,
        onEnqueued: (UUID) -> Unit, onFailure: () -> Unit) {
        val app = context.applicationContext
        schedulingExecutor.execute {
            try {
                GalleryUpgradeCoordinator.ensureReady(app)
                val manager = WorkManager.getInstance(app)
                val name = if (includeHidden) "gallery_complete_scan" else "gallery_visible_scan"
                // Resolve KEEP to the actual existing ID, never observe an ID
                // WorkManager discarded. Do not cancel a scan already running.
                val active = manager.getWorkInfosForUniqueWork(name).get()
                    .firstOrNull { !it.state.isFinished }
                val id = if (active != null && (!replace || active.state == WorkInfo.State.RUNNING)) active.id
                    else createRequest(manager, name, includeHidden, replace)
                mainHandler.post { onEnqueued(id) }
            } catch (_: Exception) {
                mainHandler.post { onFailure() }
            }
        }
    }

    private fun createRequest(manager: WorkManager, name: String, includeHidden: Boolean, replace: Boolean): UUID {
        val requestBuilder = OneTimeWorkRequestBuilder<MediaScanWorker>()
            .setInputData(
                workDataOf(
                    MediaScanWorker.KEY_INCLUDE_HIDDEN to includeHidden,
                    MediaScanWorker.KEY_FORCE to replace
                )
            )
        if (!replace) {
            requestBuilder
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .addTag(MAINTENANCE_SCAN_TAG)
                .setInitialDelay(MAINTENANCE_SCAN_DELAY_SECONDS, TimeUnit.SECONDS)
        }
        val request = requestBuilder.build()
        manager.enqueueUniqueWork(
            name,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        ).result.get()
        return request.id
    }

    fun cancelMaintenance(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(MAINTENANCE_SCAN_TAG)
    }

    private const val MAINTENANCE_SCAN_DELAY_SECONDS = 15L
    private const val MAINTENANCE_SCAN_TAG = "gallery_maintenance_scan"
}
