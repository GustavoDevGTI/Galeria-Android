package com.galeria.android

import android.content.Context
import android.content.Intent
import java.security.SecureRandom

/**
 * The viewer is open to other apps for "Abrir com". Only the gallery itself may pass the internal
 * options (album, hidden files, shuffle, path...); it signs its own launches with a random token
 * kept in the app's private storage, which other apps cannot read. Anything else keeps only the
 * file it sent.
 */
object ViewerLaunch {
    private const val EXTRA_TOKEN = "viewer_launch_token"
    private const val PREF_TOKEN = "viewer_launch_token"

    /** Extras only the gallery may set. */
    val internalExtras = listOf(
        "uri", "name", "mime", "path", "album_key", "position", "shuffle_mode",
        "include_hidden_filesystem", AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS
    )

    /** Marks a launch made by the gallery itself. */
    fun internal(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA_TOKEN, token(context))

    /** Removes the internal options from a launch that did not come from the gallery. */
    fun sanitize(context: Context, intent: Intent) {
        val trusted = runCatching { intent.getStringExtra(EXTRA_TOKEN) }.getOrNull()
        if (isTrusted(trusted, token(context))) return
        internalExtras.forEach(intent::removeExtra)
        intent.removeExtra(EXTRA_TOKEN)
    }

    internal fun isTrusted(received: String?, expected: String): Boolean =
        received != null && expected.isNotEmpty() && java.security.MessageDigest.isEqual(received.toByteArray(), expected.toByteArray())

    @Synchronized
    private fun token(context: Context): String {
        val prefs = GalleryPreferences.prefs(context)
        prefs.getString(PREF_TOKEN, null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val created = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_TOKEN, created).apply()
        return created
    }
}
