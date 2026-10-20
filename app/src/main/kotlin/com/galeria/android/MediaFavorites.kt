package com.galeria.android

import android.content.SharedPreferences

/** Favorite membership and the time of an actual addition are saved together.
 * Timestamps belong to media identity, not to a particular MediaStore URI alias. */
internal object MediaFavorites {
    private fun timestampKey(uri: String) = "favorite_added_at_${MediaIdentityRules.canonicalKey(uri)}"

    fun toggle(prefs: SharedPreferences, uri: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        val aliases = favorites.filter { MediaIdentityRules.sameUri(it, uri) }
        val added = aliases.isEmpty()
        val editor = prefs.edit()
        if (added) {
            favorites.add(uri)
            editor.putLong(timestampKey(uri), nowSeconds)
        } else {
            favorites.removeAll(aliases.toSet())
            editor.remove(timestampKey(uri))
        }
        editor.putStringSet("favorites", favorites).apply()
        return added
    }

    fun add(prefs: SharedPreferences, uris: List<String>, nowSeconds: Long = System.currentTimeMillis() / 1000L) {
        val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        val known = favorites.mapTo(HashSet(), MediaIdentityRules::canonicalKey)
        val editor = prefs.edit()
        for (uri in uris) {
            if (known.add(MediaIdentityRules.canonicalKey(uri))) {
                favorites.add(uri)
                editor.putLong(timestampKey(uri), nowSeconds)
            }
        }
        editor.putStringSet("favorites", favorites).apply()
    }

    fun latestAddition(prefs: SharedPreferences, visibleFavoriteUris: List<String>): Long =
        visibleFavoriteUris.maxOfOrNull { prefs.getLong(timestampKey(it), 0L) } ?: 0L
}
