package com.galeria.android

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** One mutation at a time. Started file operations finish even if the screen closes;
 * only UI delivery is cancelled, avoiding partially copied/deleted files. */
internal class ActivityOperationRunner(private val activity: Activity) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val indicator = ActivityLoadingIndicator(activity)
    private var closed = false
    var busy = false
        private set

    fun <T> run(work: () -> T, complete: (T) -> Unit) {
        if (closed || busy || activity.isFinishing || activity.isDestroyed) return
        busy = true
        indicator.begin(activity.getString(R.string.operation_in_progress))
        worker.execute {
            val result = runCatching(work)
            main.post {
                busy = false
                indicator.finish()
                if (!closed && !activity.isFinishing && !activity.isDestroyed) {
                    result.fold(complete) { Ui.toast(activity, "Não foi possível concluir a operação.") }
                }
            }
        }
    }

    fun close() {
        closed = true
        indicator.finish()
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
    }
}
