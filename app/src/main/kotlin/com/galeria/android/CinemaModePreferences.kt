package com.galeria.android

import android.content.SharedPreferences

internal object CinemaModeRules {
    const val ALL_MEDIA_KEY = "all_media"

    fun supportsAlbum(albumKey: String?): Boolean =
        !albumKey.isNullOrBlank() && albumKey != ALL_MEDIA_KEY

    fun updatedAlbums(current: Set<String>, albumKey: String?, enabled: Boolean): Set<String> {
        if (!supportsAlbum(albumKey)) return current
        return current.toMutableSet().apply {
            if (enabled) add(requireNotNull(albumKey)) else remove(albumKey)
        }
    }
}

internal class CinemaModePreferences(private val prefs: SharedPreferences) {
    fun isEnabled(albumKey: String?): Boolean =
        CinemaModeRules.supportsAlbum(albumKey) && enabledAlbums().contains(albumKey)

    fun toggle(albumKey: String?): Boolean {
        val enabled = !isEnabled(albumKey)
        setEnabled(albumKey, enabled)
        return enabled
    }

    fun setEnabled(albumKey: String?, enabled: Boolean) {
        if (!CinemaModeRules.supportsAlbum(albumKey)) return
        prefs.edit()
            .putStringSet(PREF_CINEMA_ALBUMS, CinemaModeRules.updatedAlbums(enabledAlbums(), albumKey, enabled))
            .apply()
    }

    fun audioPreference(albumKey: String?): String? =
        albumPreference(albumKey, AUDIO_SUFFIX)

    fun setAudioPreference(albumKey: String?, value: String?) =
        setAlbumPreference(albumKey, AUDIO_SUFFIX, value)

    fun subtitlePreference(albumKey: String?): String? =
        albumPreference(albumKey, SUBTITLE_SUFFIX)

    fun setSubtitlePreference(albumKey: String?, value: String?) =
        setAlbumPreference(albumKey, SUBTITLE_SUFFIX, value)

    fun subtitlesEnabled(albumKey: String?): Boolean =
        CinemaModeRules.supportsAlbum(albumKey) &&
            prefs.getBoolean(albumPreferenceKey(requireNotNull(albumKey), SUBTITLES_ENABLED_SUFFIX), false)

    fun setSubtitlesEnabled(albumKey: String?, enabled: Boolean) {
        if (!CinemaModeRules.supportsAlbum(albumKey)) return
        prefs.edit().putBoolean(
            albumPreferenceKey(requireNotNull(albumKey), SUBTITLES_ENABLED_SUFFIX),
            enabled
        ).apply()
    }

    private fun enabledAlbums(): Set<String> =
        prefs.getStringSet(PREF_CINEMA_ALBUMS, emptySet()).orEmpty().toSet()

    private fun albumPreference(albumKey: String?, suffix: String): String? {
        if (!CinemaModeRules.supportsAlbum(albumKey)) return null
        return prefs.getString(albumPreferenceKey(requireNotNull(albumKey), suffix), null)
    }

    private fun setAlbumPreference(albumKey: String?, suffix: String, value: String?) {
        if (!CinemaModeRules.supportsAlbum(albumKey)) return
        prefs.edit().apply {
            val key = albumPreferenceKey(requireNotNull(albumKey), suffix)
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    private fun albumPreferenceKey(albumKey: String, suffix: String): String =
        "cinema_${suffix}_${albumKey.hashCode()}"

    private companion object {
        const val PREF_CINEMA_ALBUMS = "cinema_mode_album_keys"
        const val AUDIO_SUFFIX = "audio"
        const val SUBTITLE_SUFFIX = "subtitle"
        const val SUBTITLES_ENABLED_SUFFIX = "subtitles_enabled"
    }
}
