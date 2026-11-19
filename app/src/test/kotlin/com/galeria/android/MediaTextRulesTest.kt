package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTextRulesTest {
    @Test
    fun textAndSearchIgnoreCaseAccentsAndSpacing() {
        assertEquals("genival santos de melo", MediaTextRules.normalize("GENIVAL  Santos\nde Mélo "))
        assertEquals(listOf("nota", "fiscal"), MediaTextRules.terms("  Nota   FISCAL nota a "))
        assertTrue("Palavras de uma letra não contam", MediaTextRules.terms("a e").isEmpty())
    }

    @Test
    fun searchWordsNeverActAsLikeWildcards() {
        assertEquals("%100\\%%", MediaTextRules.likePattern("100%"))
        assertEquals("%a\\_b%", MediaTextRules.likePattern("a_b"))
        assertEquals("%c:\\\\x%", MediaTextRules.likePattern("c:\\x"))
    }

    @Test
    fun onlyNewOrChangedPhotosAreReadAndRemovedOnesLeaveTheIndex() {
        val photos = listOf("a" to "1:10", "b" to "2:20", "c" to "3:30")
        val known = mapOf("a" to "1:10", "b" to "2:99", "gone" to "9:9")
        val pending = MediaTextRules.pending(photos, known, { it.first }) { it.second }
        assertEquals(listOf("b", "c"), pending.map { it.first })
        assertEquals(listOf("gone"), MediaTextRules.removed(known.keys, photos.map { it.first }.toSet()))
        assertEquals("5:42", MediaTextRules.signature(5, 42))
    }

    @Test
    fun searchResultsAreAVirtualAlbumKeyedByTheWords() {
        val key = VirtualAlbumRules.searchKey("  genival ")
        assertEquals("search:genival", key)
        assertEquals("genival", VirtualAlbumRules.searchTerm(key))
        assertNull(VirtualAlbumRules.searchTerm("Pictures/Viagem/"))
        assertTrue(VirtualAlbumRules.isVirtual(key))
        assertTrue(VirtualAlbumRules.remainsAfterMove(key))
        assertFalse(VirtualAlbumRules.isVirtual("Pictures/search:x/"))
    }
}
