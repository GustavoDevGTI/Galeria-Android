package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class CatalogPagingQueryTest {
    @Test fun dateQueryUsesIndexedPredicatesWithoutCaseSortOrUnneededJoin() {
        val physical = CatalogPagingQuery.build("visible", "Pictures/Test/", "Pictures/Test/", "", MediaSortRules.SORT_DATE, true)
        assertEquals(2, physical.argCount)
        assertTrue(physical.sql.contains("cached_media.albumKey = ?"))
        assertTrue(physical.sql.contains("cached_media.dateAdded DESC"))
        assertFalse(physical.sql.contains("LEFT JOIN")); assertFalse(physical.sql.contains("CASE"))
        assertFalse(physical.sql.contains(" OR "))
        val all = CatalogPagingQuery.build("visible", "__all__", "all_media", "", MediaSortRules.SORT_DATE, false)
        assertEquals(1, all.argCount); assertFalse(all.sql.contains("albumKey = ?"))
    }

    @Test fun customSortJoinsOnlyWhenNeededAndSearchInputIsAlwaysBound() {
        val hostile = "%' OR 1=1 --"
        val search = CatalogPagingQuery.build("visible", "__all__", "all_media", hostile, MediaSortRules.SORT_NAME, false)
        assertEquals(3, search.argCount); assertFalse(search.sql.contains(hostile))
        assertTrue(search.sql.contains("LOWER(cached_media.name) ASC"))
        assertFalse(search.sql.contains("LEFT JOIN"))
        val custom = CatalogPagingQuery.build("complete", "Pictures/Test/", "Pictures/Test/", "", MediaSortRules.SORT_CUSTOM, true)
        assertEquals(3, custom.argCount); assertTrue(custom.sql.contains("LEFT JOIN custom_media_order"))
    }
}
