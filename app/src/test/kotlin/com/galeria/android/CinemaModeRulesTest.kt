package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaModeRulesTest {
    @Test
    fun modeIsAvailableOnlyForRealAlbums() {
        assertFalse(CinemaModeRules.supportsAlbum(null))
        assertFalse(CinemaModeRules.supportsAlbum(""))
        assertFalse(CinemaModeRules.supportsAlbum("all_media"))
        assertFalse(CinemaModeRules.supportsAlbum(VirtualAlbumRules.RECENT_KEY))
        assertFalse(CinemaModeRules.supportsAlbum(VirtualAlbumRules.FAVORITES_KEY))
        assertFalse(CinemaModeRules.supportsAlbum(VirtualAlbumRules.TRASH_KEY))
        assertTrue(CinemaModeRules.supportsAlbum("Movies/Series/"))
    }

    @Test
    fun albumCanBeEnabledAndDisabledWithoutChangingOthers() {
        val initial = setOf("Movies/Existing/")
        val enabled = CinemaModeRules.updatedAlbums(initial, "Movies/New/", true)
        assertEquals(setOf("Movies/Existing/", "Movies/New/"), enabled)
        assertEquals(setOf("Movies/New/"), CinemaModeRules.updatedAlbums(enabled, "Movies/Existing/", false))
    }

    @Test
    fun unsupportedAlbumDoesNotChangeStoredSelection() {
        val initial = setOf("Movies/Existing/")
        assertEquals(initial, CinemaModeRules.updatedAlbums(initial, "all_media", true))
    }
}
