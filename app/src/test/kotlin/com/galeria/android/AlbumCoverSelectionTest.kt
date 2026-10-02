package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumCoverSelectionTest {
    @Test fun automaticCoverFollowsAlbumOrderAndManualCoverWinsOnlyWhilePresent() {
        val zeta = MediaSortRules.Key("content://media/external/images/media/3", "zeta.jpg", 3L, 100L, 0L, "image/jpeg")
        val alpha = MediaSortRules.Key("content://media/external/images/media/1", "alpha.jpg", 1L, 100L, 0L, "image/jpeg")
        val media = listOf(zeta, alpha)
        fun cover(manual: String?, mode: String = MediaSortRules.SORT_NAME, custom: List<String> = emptyList()) =
            AlbumCoverSelection.choose(media, manual, { it.uri }) { items, _ ->
                items.toMutableList().also { MediaSortRules.sort(it, mode, false, custom) { item -> item } }
            }?.uri
        assertEquals(alpha.uri, cover(null))
        assertEquals(zeta.uri, cover(zeta.uri))
        assertEquals(alpha.uri, cover("removed"))
        assertEquals(zeta.uri, cover(null, MediaSortRules.SORT_CUSTOM, listOf(zeta.uri)))
        assertNull(AlbumCoverSelection.choose(emptyList<String>(), null, { it }) { items, _ -> items })
        val preparationCalls = arrayListOf<Boolean>()
        assertEquals(alpha, AlbumCoverSelection.choose(media, null, { it.uri }) { _, unfiltered ->
            preparationCalls.add(unfiltered)
            if (unfiltered) listOf(alpha) else emptyList()
        })
        assertEquals(listOf(false, true), preparationCalls)
    }
}
