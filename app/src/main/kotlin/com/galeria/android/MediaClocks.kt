package com.galeria.android

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/** The 48-hour clocks of the media used in joins, kept across restarts. */
internal object MediaClocks {
    private const val PREF = "media_clocks"
    private const val WORK = "media_clock"
    private val lock = Any()
    @Volatile private var cache: List<MediaClock>? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Grows on every change, so screens redraw only when a clock came or went. */
    @Volatile var version = 0
        private set

    fun all(context: Context): List<MediaClock> = cache ?: synchronized(lock) {
        cache ?: GalleryPreferences.prefs(context).getStringSet(PREF, emptySet()).orEmpty()
            .mapNotNull(MediaClockRules::decode).also { cache = it }
    }

    fun of(context: Context, uri: String): MediaClock? {
        val key = MediaIdentityRules.canonicalKey(uri)
        return all(context).firstOrNull { it.key == key }
    }

    /** Starts the clock of the media a join just used. */
    fun start(context: Context, uris: List<String>, now: Long = System.currentTimeMillis()) {
        if (uris.isEmpty()) return
        update(context) { MediaClockRules.started(it, uris, now) }
        schedule(context, includeOverdue = false)
    }

    /** Tapping the clock keeps the media. */
    fun cancel(context: Context, uri: String) {
        update(context) { MediaClockRules.cancelled(it, uri) }
        schedule(context, includeOverdue = false)
    }

    internal fun forget(context: Context, clocks: Collection<MediaClock>) {
        if (clocks.isEmpty()) return
        val keys = clocks.mapTo(HashSet()) { it.key }
        update(context) { current -> current.filterNot { it.key in keys } }
    }

    fun observe(listener: () -> Unit) { listeners += listener }
    fun stopObserving(listener: () -> Unit) { listeners -= listener }

    /** Wakes the worker at the next clock; overdue clocks are retried only when asked. */
    fun schedule(context: Context, includeOverdue: Boolean) {
        val app = context.applicationContext
        val clocks = all(app)
        // Opening the app with no clock touches nothing.
        if (clocks.isEmpty() && includeOverdue) return
        val now = System.currentTimeMillis()
        val manager = WorkManager.getInstance(app)
        val next = MediaClockRules.nextRun(clocks, now, includeOverdue)
        if (next == null) {
            manager.cancelUniqueWork(WORK)
            return
        }
        manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MediaClockWorker>().setInitialDelay((next - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS).build())
    }

    private fun update(context: Context, change: (List<MediaClock>) -> List<MediaClock>) {
        synchronized(lock) {
            val next = change(all(context))
            GalleryPreferences.prefs(context).edit().putStringSet(PREF, next.mapTo(HashSet(), MediaClockRules::encode)).commit()
            cache = next
            version++
        }
        main.post { listeners.forEach { it() } }
    }
}

/** What happened to one media when its clock ran out. */
internal enum class ClockResult { DONE, GONE, NEEDS_CONFIRMATION }

/** Sends the media whose clock ran out to the Trash, or deletes them when the user chose that. */
internal object MediaClockExpiry {
    fun outcome(context: Context): ClockOutcome = MediaClockRules.outcome(
        TrashPreferences.isEnabled(context), GalleryPreferences.flag(context, GalleryPreferences.CLOCK_DELETES_PERMANENTLY)
    )

    /** Handles every clock that ran out and wakes again at the next one; returns how many wait for the user. */
    fun runDue(context: Context): Int {
        val due = MediaClockRules.due(MediaClocks.all(context), System.currentTimeMillis())
        val outcome = outcome(context)
        val finished = ArrayList<MediaClock>()
        var waiting = 0
        due.forEach { clock ->
            if (expire(context, clock, outcome) == ClockResult.NEEDS_CONFIRMATION) waiting++ else finished += clock
        }
        MediaClocks.forget(context, finished)
        // Without full file access Android asks the user first; the notification opens that question.
        if (waiting > 0) MediaClockNotice.show(context, waiting) else MediaClockNotice.clear(context)
        MediaClocks.schedule(context, includeOverdue = false)
        return waiting
    }

    fun expire(context: Context, clock: MediaClock, outcome: ClockOutcome): ClockResult {
        val uri = Uri.parse(clock.uri)
        if (!exists(context, uri)) return ClockResult.GONE
        val done = try {
            when (outcome) {
                ClockOutcome.TRASH -> trash(context, uri)
                ClockOutcome.DELETE -> MediaActions.deleteDirect(context, uri)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!done) return ClockResult.NEEDS_CONFIRMATION
        if (outcome == ClockOutcome.DELETE) LegacyTrashStore.forget(context, uri)
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context.applicationContext)
        return ClockResult.DONE
    }

    /** Sends to the Trash whatever the Trash setting says; false when Android must ask the user. */
    internal fun trash(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file" || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return LegacyTrashStore.trash(context, uri)
        return context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null) > 0
    }

    /** Media already removed or in the Trash need nothing more. */
    fun exists(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }
}

class MediaClockWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        MediaClockExpiry.runDue(applicationContext)
        return Result.success()
    }
}

internal object MediaClockNotice {
    private const val CHANNEL = "media_clock"
    private const val ID = 4201

    fun show(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.clock_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MediaClockConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_clock)
                // The app's name first, so the notification is easy to recognize.
                .setContentTitle(context.getString(R.string.notification_brand))
                .setContentText(context.getString(R.string.clock_confirm_title))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.clock_confirm_title) + "\n" +
                    context.resources.getQuantityString(R.plurals.clock_confirm_text, count, count)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}

/** Asks Android once for every media whose clock ran out while the app could not remove it alone. */
class MediaClockConfirmActivity : Activity() {
    private var asking: List<MediaClock> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        MediaClockNotice.clear(this)
        val outcome = MediaClockExpiry.outcome(this)
        val due = MediaClockRules.due(MediaClocks.all(this), System.currentTimeMillis())
        val left = ArrayList<MediaClock>()
        val gone = ArrayList<MediaClock>()
        due.forEach { clock ->
            when (MediaClockExpiry.expire(this, clock, outcome)) {
                ClockResult.NEEDS_CONFIRMATION -> left += clock
                else -> gone += clock
            }
        }
        MediaClocks.forget(this, gone)
        val mediaStore = left.filter { it.uri.startsWith("content://") }
        if (mediaStore.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishAndReschedule()
            return
        }
        asking = mediaStore
        val uris = mediaStore.map { Uri.parse(it.uri) }
        try {
            val request = if (outcome == ClockOutcome.DELETE) MediaStore.createDeleteRequest(contentResolver, uris)
                else MediaStore.createTrashRequest(contentResolver, uris, true)
            startIntentSenderForResult(request.intentSender, REQUEST, null, 0, 0, 0)
        } catch (_: Exception) {
            finishAndReschedule()
        }
    }

    @Deprecated("Activity result of the system confirmation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK) {
            MediaClocks.forget(this, asking)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(applicationContext)
        }
        finishAndReschedule()
    }

    private fun finishAndReschedule() {
        MediaClocks.schedule(this, includeOverdue = false)
        finish()
    }

    private companion object {
        const val REQUEST = 4202
    }
}
