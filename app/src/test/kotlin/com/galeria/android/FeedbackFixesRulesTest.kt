package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAlbumOrderRulesTest {
    private fun album(key: String, latest: Long, path: String = "Pictures/$key/") = AlbumItem(key, key, 1, null, latest, 1, 1, path)

    @Test
    fun customOrderFollowsTheDraggedOrderAndNewAlbumsComeAfterNewestFirst() {
        val albums = mutableListOf(album("a", 10), album("b", 30), album("c", 20), album("new-old", 5), album("new-recent", 50))
        AlbumRules.sort(albums, AlbumRules.SORT_CUSTOM, descending = true, customOrder = listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b", "new-recent", "new-old"), albums.map { it.key })
    }

    @Test
    fun inCustomOrderPinnedAlbumsStayTogetherInTheirOwnOrder() {
        val camera = album("camera", 10, "DCIM/Camera/")
        val trip = album("trip", 99)
        val pinnedMine = album("mine", 1)
        val source = listOf(trip, camera, album(VirtualAlbumRules.RECENT_KEY, 0, ""), pinnedMine)
        // Already in the dragged order: trash-like rules and latest activity no longer reorder them.
        assertEquals(listOf("camera", VirtualAlbumRules.RECENT_KEY, "mine", "trip"),
            VirtualAlbumRules.pinEssential(source, setOf("mine"), activeScreenshotKey = null, customOrder = true).map { it.key })
    }

    @Test
    fun aDraggedAlbumOnlySwapsInsideItsGroup() {
        val pinned = setOf("camera", "mine")
        assertTrue(AlbumReorderRules.sameGroup("camera", "mine", pinned))
        assertTrue(AlbumReorderRules.sameGroup("trip", "beach", pinned))
        assertFalse(AlbumReorderRules.sameGroup("camera", "trip", pinned))
        assertEquals(listOf("a", "b"), AlbumReorderRules.savedOrder("a,b"))
        assertEquals(emptyList<String>(), AlbumReorderRules.savedOrder(null))
    }
}

class RecoveryRulesTest {
    @Test
    fun answersMatchIgnoringCaseAccentsAndSpaces() {
        assertEquals("sao paulo", RecoveryRules.normalize("  São   PAULO "))
        assertEquals(RecoveryRules.normalize("Rex"), RecoveryRules.normalize("rex "))
        assertFalse(RecoveryRules.isAcceptable(" a "))
        assertTrue(RecoveryRules.isAcceptable("Rex"))
    }

    @Test
    fun readyQuestionsAreStoredByNumberAndOwnQuestionsAsText() {
        assertEquals(2, RecoveryRules.presetIndex("preset:2"))
        assertNull(RecoveryRules.presetIndex("Qual o apelido do meu avô?"))
        assertNull(RecoveryRules.presetIndex(null))
    }
}
