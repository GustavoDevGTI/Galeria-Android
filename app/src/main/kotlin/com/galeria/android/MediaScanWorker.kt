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

class MediaScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val includeHidden = inputData.getBoolean(KEY_INCLUDE_HIDDEN, false)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val count = MediaStoreRepository.refreshMedia(applicationContext, includeHidden, force).size
        Result.success(workDataOf(KEY_ITEM_COUNT to count))
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
    fun enqueue(context: Context, includeHidden: Boolean, replace: Boolean): UUID {
        val requestBuilder = OneTimeWorkRequestBuilder<MediaScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setInputData(
                workDataOf(
                    MediaScanWorker.KEY_INCLUDE_HIDDEN to includeHidden,
                    MediaScanWorker.KEY_FORCE to replace
                )
            )
        if (!replace) {
            requestBuilder
                .addTag(MAINTENANCE_SCAN_TAG)
                .setInitialDelay(MAINTENANCE_SCAN_DELAY_SECONDS, TimeUnit.SECONDS)
        }
        val request = requestBuilder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (includeHidden) "gallery_complete_scan" else "gallery_visible_scan",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    fun cancelMaintenance(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(MAINTENANCE_SCAN_TAG)
    }

    private const val MAINTENANCE_SCAN_DELAY_SECONDS = 15L
    private const val MAINTENANCE_SCAN_TAG = "gallery_maintenance_scan"
}
