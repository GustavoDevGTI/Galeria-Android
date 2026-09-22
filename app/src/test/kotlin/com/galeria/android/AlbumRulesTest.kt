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
            visibleNow = listOf("all_media", VirtualAlbumRules.RECENT_KEY, VirtualAlbumRules.FAVORITES_KEY, "viagem")
        )

        assertEquals(setOf("camera", "viagem"), remembered)
    }

    @Test
    fun moveTargetsAreLimitedToTheAlbumsExposedOnTheMainScreen() {
        val source = listOf(
            album("camera", "Camera", "DCIM/Camera/"),
            album("private", "Private", "Pictures/Private/"),
            album("screenshots", "Screenshots", "Pictures/Screenshots/")
        )

        val targets = AlbumTargetRules.exposedTargets(
            source,
            exposedKeys = setOf("camera", "screenshots"),
            hiddenKeys = emptySet(),
            excludedKeys = setOf("camera")
        )

        assertEquals(listOf("screenshots"), targets.map { it.key })
    }

    @Test
    fun moveTargetsWithoutScreenStateRejectExplicitAndFilesystemHiddenAlbums() {
        val source = listOf(
            album("camera", "Camera", "DCIM/Camera/"),
            album("private", "Private", "Pictures/Private/"),
            album("screenshots", "Screenshots", "Pictures/Screenshots/")
        )

        val targets = AlbumTargetRules.exposedTargets(
            source,
            exposedKeys = null,
            hiddenKeys = setOf("screenshots"),
            excludedKeys = emptySet()
        )

        assertEquals(listOf("camera"), targets.map { it.key })
    }

    @Test
    fun moveTargetsUseTheSameSortAsTheMainGallery() {
        val targets = AlbumTargetRules.orderedTargets(
            albums(),
            exposedKeys = listOf("z", "b", "a"),
            hiddenKeys = emptySet(),
            excludedKeys = emptySet(),
            sortMode = AlbumRules.SORT_NAME,
            sortDescending = false
        )

        assertEquals(listOf("Zeta", "Beta", "Alpha"), targets.map { it.name })
    }

    @Test
    fun moveTargetsFallBackToTheMainSortWhenNoDisplayedOrderWasProvided() {
        val targets = AlbumTargetRules.orderedTargets(
            albums(),
            exposedKeys = null,
            hiddenKeys = emptySet(),
            excludedKeys = emptySet(),
            sortMode = AlbumRules.SORT_NAME,
            sortDescending = false
        )

        assertEquals(listOf("Alpha", "Beta", "Zeta"), targets.map { it.name })
    }

    @Test
    fun selectingNameStartsAscendingAndKeepsAnExplicitDirection() {
        assertFalse(
            SortDirectionRules.whenModeSelected(
                AlbumRules.SORT_MODIFIED,
                AlbumRules.SORT_NAME,
                currentDescending = true
            )
        )
        assertTrue(
            SortDirectionRules.whenModeSelected(
                AlbumRules.SORT_NAME,
                AlbumRules.SORT_NAME,
                currentDescending = true
            )
        )
        assertTrue(SortDirectionRules.defaultDescending(AlbumRules.SORT_SIZE))
        assertFalse(SortDirectionRules.supportsDirection(MediaSortRules.SORT_CUSTOM))
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

    @Test
    fun hiddenFilesystemRequiresExplicitRequestAndAllFilesAccess() {
        assertFalse(StorageAccessRules.includeHiddenFilesystem(false, false))
        assertFalse(StorageAccessRules.includeHiddenFilesystem(true, false))
        assertFalse(StorageAccessRules.includeHiddenFilesystem(false, true))
        assertTrue(StorageAccessRules.includeHiddenFilesystem(true, true))
    }

    @Test
    fun initialAccessFlowDistinguishesExistingLimitedAndMissingAccess() {
        assertEquals(
            MainAccessStartupAction.LOAD_LIBRARY,
            MainAccessRules.startupAction(true, true, false)
        )
        assertEquals(
            MainAccessStartupAction.SHOW_INITIAL_CHOICE,
            MainAccessRules.startupAction(false, true, false)
        )
        assertEquals(
            MainAccessStartupAction.REQUEST_MEDIA_LIBRARY,
            MainAccessRules.startupAction(false, true, true)
        )
        assertEquals(
            MainAccessStartupAction.REQUEST_MEDIA_LIBRARY,
            MainAccessRules.startupAction(false, false, false)
        )
    }

    @Test
    fun catalogPreparationKeepsHiddenRulesAndBuildsAllMediaSummary() {
        val source = listOf(
            AlbumItem("camera", "Camera", 3, null, 30, 10, 300, "DCIM/Camera/"),
            AlbumItem("private", "Private", 2, null, 20, 15, 200, "Pictures/.Private/"),
            AlbumItem("ignored", "Ignored", 4, null, 40, 5, 400, "Pictures/Ignored/")
        )

        val visible = AlbumCatalogRules.prepare(source, setOf("ignored"), false, false)
        assertEquals(listOf("camera"), visible.map { it.key })

        val allMedia = AlbumCatalogRules.prepare(source, setOf("ignored"), true, true).single()
        assertEquals("all_media", allMedia.key)
        assertEquals(5, allMedia.count)
        assertEquals(500, allMedia.totalSize)
    }

    @Test
    fun albumMediaUsesPagingOnlyForUngroupedLibraryOutsideSelectionMode() {
        assertTrue(AlbumMediaRules.shouldUsePaging(null, AlbumMediaRules.GROUP_NONE, false))
        assertTrue(AlbumMediaRules.shouldUsePaging("all_media", AlbumMediaRules.GROUP_NONE, false))
        assertTrue(AlbumMediaRules.shouldUsePaging(VirtualAlbumRules.RECENT_KEY, AlbumMediaRules.GROUP_NONE, false))
        assertFalse(AlbumMediaRules.shouldUsePaging("camera", AlbumMediaRules.GROUP_NONE, false))
        assertFalse(AlbumMediaRules.shouldUsePaging("all_media", AlbumMediaRules.GROUP_DAY, false))
        assertFalse(AlbumMediaRules.shouldUsePaging("all_media", AlbumMediaRules.GROUP_NONE, true))
    }

    @Test
    fun albumMediaScrollTargetDistinguishesReloadAndReturnFromViewer() {
        assertEquals(18, AlbumMediaRules.scrollTarget(true, 18, 7))
        assertEquals(7, AlbumMediaRules.scrollTarget(false, 18, 7))
    }

    @Test
    fun bulkFileChangesRequireManagementAccessOnlyOnModernAndroidWithoutGrant() {
        assertFalse(AlbumMediaRules.requiresFileManagement(false, false))
        assertFalse(AlbumMediaRules.requiresFileManagement(true, true))
        assertTrue(AlbumMediaRules.requiresFileManagement(true, false))
    }

    @Test
    fun essentialAlbumsStayInTheExpectedOrderAheadOfOtherAlbums() {
        val source = listOf(
            album("travel", "Viagem", "Pictures/Travel/"),
            album(VirtualAlbumRules.RECENT_KEY, "Recentes", ""),
            album("downloads", "Downloads", "Download/"),
            album("screens", "Capturas de tela", "Pictures/Screenshots/"),
            album(VirtualAlbumRules.FAVORITES_KEY, "Favoritos", ""),
            album("camera", "Câmera", "DCIM/Camera/")
        )

        assertEquals(
            listOf("camera", "screens", VirtualAlbumRules.FAVORITES_KEY, "downloads", VirtualAlbumRules.RECENT_KEY, "travel"),
            VirtualAlbumRules.pinEssential(source).map { it.key }
        )
    }

    @Test
    fun virtualAlbumsCannotBeMoveTargetsAndAggregatesRemainAfterMove() {
        assertTrue(VirtualAlbumRules.isVirtual(VirtualAlbumRules.FAVORITES_KEY))
        assertTrue(VirtualAlbumRules.remainsAfterMove(VirtualAlbumRules.RECENT_KEY))
        assertFalse(VirtualAlbumRules.remainsAfterMove(VirtualAlbumRules.TRASH_KEY))
    }

    private fun albums(): List<AlbumItem> = listOf(
        AlbumItem("z", "Zeta", 3, null, 20, 10, 900, "C/Zeta"),
        AlbumItem("a", "Alpha", 1, null, 10, 5, 100, "A/Alpha"),
        AlbumItem("b", "Beta", 2, null, 30, 2, 500, "B/Beta")
    )

    private fun album(key: String, name: String, path: String): AlbumItem =
        AlbumItem(key, name, 1, null, 1, 1, 1, path)
}
