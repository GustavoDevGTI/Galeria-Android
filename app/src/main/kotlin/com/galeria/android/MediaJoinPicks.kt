package com.galeria.android

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Turns photo-picker results into the gallery's own addresses, so a montage can be separated later. */
internal object MediaJoinPicks {
    /** The picker's id for local media, or null when [uri] is not a picker address. */
    fun pickerId(uri: Uri): Long? {
        if (uri.authority != MediaStore.AUTHORITY || uri.pathSegments.none { it == "picker" || it.contains("photopicker") }) return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    /**
     * The MediaStore address behind a picker result. Cloud items or ids the gallery cannot see keep
     * the picker address, which still reads for as long as the editor is open.
     */
    fun galleryUri(context: Context, uri: Uri): Uri {
        val id = pickerId(uri) ?: return uri
        val mime = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns._ID}=?", arrayOf(id.toString()), null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return uri
        val collection = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> return uri
        }
        return ContentUris.withAppendedId(collection, id)
    }

    fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) (it.getString(0) ?: "") to it.getLong(1) else null
        }
    }.getOrNull() ?: ("" to 0L)
}
