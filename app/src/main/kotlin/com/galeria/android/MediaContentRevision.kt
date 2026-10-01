package com.galeria.android

import android.content.Context
import android.net.Uri
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Content can change without its URI, size or date-added changing. */
internal object MediaContentRevision {
    private const val PREFERENCES = "media_content_revisions"
    private val generation = AtomicLong()

    fun generation(): Long = generation.get()

    fun key(context: Context, uri: Uri): String {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(uri.toString(), "").orEmpty()
        return if (value.isEmpty()) uri.toString() else "$uri:$value"
    }

    fun changed(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(uri.toString(), UUID.randomUUID().toString()).apply()
        generation.incrementAndGet()
    }
}
