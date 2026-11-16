package com.galeria.android

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Android passes the shared list to the other app in one message limited to about 1 MB. Thousands
 * of addresses go past it and close the app, so a share sends at most [MAX_ITEMS] media.
 */
internal object ShareLimit {
    const val MAX_ITEMS = 500

    fun <T> limited(items: List<T>): List<T> = if (items.size > MAX_ITEMS) items.take(MAX_ITEMS) else items

    fun intent(uris: List<Uri>): Intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(limited(uris)))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Tells the user when only part of the selection was shared. */
    fun warnIfLimited(context: Context, total: Int) {
        if (total <= MAX_ITEMS) return
        Ui.toast(context, context.getString(R.string.msg_share_limited, MAX_ITEMS, total))
    }
}
