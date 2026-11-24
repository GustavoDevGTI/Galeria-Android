package com.galeria.android

import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** "Separar": gives back the media of a video joined by the app; the joined video goes to the Trash. */
internal fun DetailActivity.confirmSeparateCurrent() {
    Ui.showConfirmationDialog(
        this,
        getString(R.string.separate_title),
        getString(R.string.separate_message),
        getString(R.string.action_separate)
    ) { separateCurrent() }
}

internal fun DetailActivity.separateCurrent() {
    if (operations.busy) return
    val joined = currentItem().uri
    operations.run({
        val record = MediaJoinExporter.readRecord(this, joined)?.takeIf(MediaSplitRules::separable)
            ?: throw IllegalStateException("Vídeo sem montagem")
        MediaSplitter.split(this, joined, record)
    }) { result ->
        pendingSplitUri = joined
        when {
            result.joinedTrashed -> finishSplit()
            // Originals in the Trash that Android returns only with the user's yes.
            result.restoreNeedsConsent.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                askSystem(MediaStore.createTrashRequest(contentResolver, result.restoreNeedsConsent, false), DetailActivity.REQ_SPLIT_RESTORE)
            else -> trashJoinedOrAsk()
        }
    }
}

internal fun DetailActivity.onSplitRestoreResult(granted: Boolean) {
    if (!granted) {
        pendingSplitUri = null
        Ui.toast(this, getString(R.string.separate_cancelled))
        return
    }
    trashJoinedOrAsk()
}

internal fun DetailActivity.onSplitTrashResult(granted: Boolean) {
    if (granted) finishSplit() else {
        pendingSplitUri = null
        Ui.toast(this, getString(R.string.separate_cancelled))
    }
}

private fun DetailActivity.trashJoinedOrAsk() {
    val joined = pendingSplitUri ?: return
    if (MediaSplitter.trashJoined(this, joined)) {
        finishSplit()
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && joined.scheme == "content") {
        askSystem(MediaStore.createTrashRequest(contentResolver, listOf(joined), true), DetailActivity.REQ_SPLIT_TRASH)
    } else {
        pendingSplitUri = null
        Ui.toast(this, getString(R.string.msg_operation_failed))
    }
}

private fun DetailActivity.askSystem(request: android.app.PendingIntent, code: Int) {
    try {
        startIntentSenderForResult(request.intentSender, code, null, 0, 0, 0)
    } catch (_: Exception) {
        pendingSplitUri = null
        Ui.toast(this, getString(R.string.msg_operation_failed))
    }
}

private fun DetailActivity.finishSplit() {
    val joined: Uri = pendingSplitUri ?: return
    pendingSplitUri = null
    separableCache.remove(joined.toString())
    Ui.toast(this, getString(R.string.separated))
    if (currentItem().uri == joined) removeDeletedItem()
}
