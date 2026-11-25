package com.galeria.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The one montage being written, shared by the editor and the service that runs it. */
internal object MediaJoinJobs {
    sealed interface State {
        data object Idle : State
        data class Running(val progress: Int) : State
        data class Saved(val uri: Uri) : State
        data class Failed(val message: String?) : State
        data object Cancelled : State
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    @Volatile private var pending: JoinRequest? = null

    val watched: Boolean get() = listeners.isNotEmpty()

    fun observe(listener: (State) -> Unit) { listeners += listener; listener(state) }
    fun stopObserving(listener: (State) -> Unit) { listeners -= listener }

    /** False while another montage is still being written. */
    fun start(context: Context, request: JoinRequest): Boolean {
        if (state is State.Running) return false
        pending = request
        cancelled.set(false)
        publish(State.Running(0))
        ContextCompat.startForegroundService(context, Intent(context, MediaJoinService::class.java))
        return true
    }

    fun cancel() { cancelled.set(true) }

    /** The editor read the outcome; the next montage starts clean. */
    fun acknowledge() { if (state !is State.Running) state = State.Idle }

    internal fun take(): JoinRequest? = pending.also { pending = null }
    internal fun isCancelled(): Boolean = cancelled.get()

    internal fun publish(next: State) {
        state = next
        main.post { listeners.forEach { it(next) } }
    }
}

/** Writes the montage in the foreground, so it finishes even after the editor closes. */
class MediaJoinService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var lastPercent = -1
    private var title = R.string.join_running

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = MediaJoinJobs.take()
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A single video is a plain edit being saved; two or more media are being joined.
        title = if (request.clips.size > 1) R.string.join_running else R.string.video_edit_saving
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        // The platform call keeps the media-processing type, which older compat helpers drop.
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, progress(0), type) else startForeground(NOTIFICATION_ID, progress(0))
        executor.execute {
            val result = runCatching {
                MediaJoinExporter.export(this, request, ::report, MediaJoinJobs::isCancelled)
            }
            result.onSuccess { uri ->
                // The media of a join leave after 48 hours unless the user taps their clock.
                MediaClocks.start(this, MediaClockRules.targets(request.clips, GalleryPreferences.flag(this, GalleryPreferences.CLOCK_PHOTOS)))
                MediaJoinJobs.publish(MediaJoinJobs.State.Saved(uri))
                if (!MediaJoinJobs.watched) finished(getString(R.string.join_saved), open(uri))
            }.onFailure { error ->
                if (error is CancellationException) {
                    MediaJoinJobs.publish(MediaJoinJobs.State.Cancelled)
                } else {
                    MediaJoinJobs.publish(MediaJoinJobs.State.Failed(error.message))
                    if (!MediaJoinJobs.watched) finished(getString(R.string.join_failed), null)
                }
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun report(percent: Int) {
        if (percent == lastPercent) return
        lastPercent = percent
        MediaJoinJobs.publish(MediaJoinJobs.State.Running(percent))
        manager().notify(NOTIFICATION_ID, progress(percent))
    }

    /**
     * A normal channel, so the notification sits with the others instead of being folded into the
     * silent ones. Android never raises an existing channel's importance, so the old low channel
     * is removed and this one takes its place.
     */
    private fun channel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().deleteNotificationChannel(OLD_CHANNEL)
            manager().createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.join_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        return CHANNEL
    }

    // The title names the app ("Galery"), so the notification is easy to recognize; the text
    // says what is happening. The progress stays quiet while it moves.
    private fun progress(percent: Int) = NotificationCompat.Builder(this, channel())
        .setSmallIcon(R.drawable.ic_join)
        .setContentTitle(getString(R.string.notification_brand))
        .setContentText(getString(title))
        .setProgress(100, percent, percent == 0)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

    private fun finished(title: String, tap: PendingIntent?) {
        manager().notify(RESULT_ID, NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_join)
            .setContentTitle(getString(R.string.notification_brand))
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build())
    }

    private fun open(uri: Uri): PendingIntent = PendingIntent.getActivity(this, 0,
        ViewerLaunch.internal(this, Intent(this, DetailActivity::class.java).apply {
            putExtra("uri", uri.toString())
            putExtra("mime", "video/mp4")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun manager() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "media_join_alerts"
        const val OLD_CHANNEL = "media_join"
        const val NOTIFICATION_ID = 4101
        const val RESULT_ID = 4102
    }
}
