package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumRulesTest {
    @Test
    fun detectsHiddenFolderSegmentsOnBothPathStyles() {
        assertTrue(AlbumRules.isHidden("Pictures/.private/", "camera"))
        assertTrue(AlbumRules.isHidden("DCIM\\Hidden\\", "camera"))
        assertTrue(AlbumRules.isHidden("WhatsApp/Private/", "whatsapp"))
        assertFalse(AlbumRules.isHidden("Pictures/Family/", "family"))
    }

    @Test
    fun sortsByNameAndModificationDateInBothDirections() {
        val byName = albums().toMutableList()
        AlbumRules.sort(byName, AlbumRules.SORT_NAME, descending = false)
        assertEquals(listOf("Alpha", "Beta", "Zeta"), byName.map { it.name })

        val byDate = albums().toMutableList()
        AlbumRules.sort(byDate, AlbumRules.SORT_MODIFIED, descending = true)
        assertEquals(listOf("Beta", "Zeta", "Alpha"), byDate.map { it.name })
    }

    @Test
    fun sortsByPathSizeAndCreationDate() {
        val byPath = albums().toMutableList()
        AlbumRules.sort(byPath, AlbumRules.SORT_PATH, descending = false)
        assertEquals(listOf("Alpha", "Beta", "Zeta"), byPath.map { it.name })

        val bySize = albums().toMutableList()
        AlbumRules.sort(bySize, AlbumRules.SORT_SIZE, descending = true)
        assertEquals(listOf("Zeta", "Beta", "Alpha"), bySize.map { it.name })

        val byCreation = albums().toMutableList()
        AlbumRules.sort(byCreation, AlbumRules.SORT_CREATED, descending = false)
        assertEquals(listOf("Beta", "Alpha", "Zeta"), byCreation.map { it.name })
    }

    @Test
    fun randomOrderIsRepeatableForTheSameSeed() {
        val first = albums().toMutableList()
        val second = albums().toMutableList()

        AlbumRules.sort(first, AlbumRules.SORT_RANDOM, descending = false, randomSeed = 77L)
        AlbumRules.sort(second, AlbumRules.SORT_RANDOM, descending = false, randomSeed = 77L)

        assertEquals(first.map { it.key }, second.map { it.key })
        assertEquals(albums().map { it.key }.toSet(), first.map { it.key }.toSet())
    }

    @Test
    fun hiddenDialogOnlyStartsWithCurrentAndPreviouslyVisibleAlbums() {
        val keys = HiddenAlbumDialogRules.keysForInitialDialog(
            currentlyVisible = listOf("camera", "screenshots"),
            previouslyVisible = listOf("familia", "camera")
        )

        assertEquals(setOf("camera", "screenshots", "familia"), keys)
        assertFalse(keys.contains("oculto_nunca_exibido"))
    }

    @Test
    fun visibleAlbumHistoryIsKeptWithoutTheSyntheticAllMediaAlbum() {
        val remembered = HiddenAlbumDialogRules.rememberVisible(
            previouslyVisible = listOf("camera"),
            visibleNow = listOf("all_media", "viagem")
        )

        assertEquals(setOf("camera", "viagem"), remembered)
    }

    @Test
    fun horizontalPinchChangesTheNumberOfGridColumns() {
        assertEquals(-1, GridColumnRules.columnDelta(GridColumnRules.SCALE_STEP))
        assertEquals(1, GridColumnRules.columnDelta(1f / GridColumnRules.SCALE_STEP))
        assertEquals(0, GridColumnRules.columnDelta(1f))
    }

    @Test
    fun gridColumnCountStaysInsideTheSupportedRange() {
        assertEquals(GridColumnRules.MIN_COLUMNS, GridColumnRules.changed(GridColumnRules.MIN_COLUMNS, -1))
        assertEquals(GridColumnRules.MAX_COLUMNS, GridColumnRules.changed(GridColumnRules.MAX_COLUMNS, 1))
        assertEquals(4, GridColumnRules.normalized(0, 4))
        assertEquals(GridColumnRules.MAX_COLUMNS, GridColumnRules.normalized(99, 4))
    }

    @Test
    fun mediaStoreUrisWithTheSameIdRepresentTheSameMedia() {
        assertTrue(
            MediaIdentityRules.sameUri(
                "content://media/external/images/media/321",
                "content://media/external/file/321"
            )
        )
        assertFalse(
            MediaIdentityRules.sameUri(
                "content://media/external/images/media/321",
                "content://media/external/file/654"
            )
        )
    }

    private fun albums(): List<AlbumItem> = listOf(
        AlbumItem("z", "Zeta", 3, null, 20, 10, 900, "C/Zeta"),
        AlbumItem("a", "Alpha", 1, null, 10, 5, 100, "A/Alpha"),
        AlbumItem("b", "Beta", 2, null, 30, 2, 500, "B/Beta")
    )
}
