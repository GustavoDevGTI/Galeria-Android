package com.galeria.android

import android.content.Context
import android.content.Intent
import java.io.File

internal object MediaOperationNavigation {
    const val EXTRA_REMOVED_URIS = "operation_removed_uris"
    const val EXTRA_MOVED_URIS = "operation_moved_uris"
    const val EXTRA_DESTINATION_KEY = "operation_destination_key"
    const val EXTRA_DESTINATION_NAME = "operation_destination_name"

    // A filtered/partially authorized query is not evidence that a physical folder is empty.
    fun isEmptyFolder(folder: File?): Boolean =
        folder?.listFiles()?.none { it.name != ".nomedia" } == true

    fun destinationIntent(context: Context, source: Intent, key: String, name: String): Intent =
        Intent(context, AlbumMediaActivity::class.java).apply {
            putExtra("album_key", key)
            putExtra("album_name", name)
            putExtra("include_hidden_filesystem", source.getBooleanExtra("include_hidden_filesystem", false))
            source.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.let {
                putStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS, ArrayList(it))
            }
        }
}
