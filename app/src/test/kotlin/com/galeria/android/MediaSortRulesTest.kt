package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSortRulesTest {
    @Test
    fun sortsAlphabeticallyInBothDirections() {
        val ascending = entries().toMutableList()
        MediaSortRules.sort(ascending, MediaSortRules.SORT_NAME, false, emptyList(), ::key)
        assertEquals(listOf("Alpha.jpg", "beta.mp4", "Zeta.png"), ascending.map { it.name })

        val descending = entries().toMutableList()
        MediaSortRules.sort(descending, MediaSortRules.SORT_NAME, true, emptyList(), ::key)
        assertEquals(listOf("Zeta.png", "beta.mp4", "Alpha.jpg"), descending.map { it.name })
    }

    @Test
    fun sortsByDateSizeDurationAndType() {
        val byDate = entries().toMutableList()
        MediaSortRules.sort(byDate, MediaSortRules.SORT_DATE, true, emptyList(), ::key)
        assertEquals(listOf("beta.mp4", "Zeta.png", "Alpha.jpg"), byDate.map { it.name })

        val bySize = entries().toMutableList()
        MediaSortRules.sort(bySize, MediaSortRules.SORT_SIZE, false, emptyList(), ::key)
        assertEquals(listOf("Alpha.jpg", "beta.mp4", "Zeta.png"), bySize.map { it.name })

        val byDuration = entries().toMutableList()
        MediaSortRules.sort(byDuration, MediaSortRules.SORT_DURATION, true, emptyList(), ::key)
        assertEquals("beta.mp4", byDuration.first().name)

        val byType = entries().toMutableList()
        MediaSortRules.sort(byType, MediaSortRules.SORT_TYPE, false, emptyList(), ::key)
        assertEquals(listOf("Alpha.jpg", "Zeta.png", "beta.mp4"), byType.map { it.name })
    }

    @Test
    fun customOrderKeepsSavedItemsFirstAndNewItemsByNewestDate() {
        val items = entries().toMutableList()
        MediaSortRules.sort(
            items,
            MediaSortRules.SORT_CUSTOM,
            true,
            listOf("z", "a"),
            ::key
        )

        assertEquals(listOf("z", "a", "b"), items.map { it.uri })
    }

    private fun entries() = listOf(
        Entry("z", "Zeta.png", 20, 900, 0, "image/png"),
        Entry("a", "Alpha.jpg", 10, 100, 0, "image/jpeg"),
        Entry("b", "beta.mp4", 30, 500, 12_000, "video/mp4")
    )

    private fun key(entry: Entry) = MediaSortRules.Key(
        entry.uri,
        entry.name,
        entry.date,
        entry.size,
        entry.duration,
        entry.mimeType
    )

    private data class Entry(
        val uri: String,
        val name: String,
        val date: Long,
        val size: Long,
        val duration: Long,
        val mimeType: String
    )
}
