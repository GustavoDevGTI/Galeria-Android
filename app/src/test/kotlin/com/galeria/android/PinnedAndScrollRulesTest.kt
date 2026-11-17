package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class PinnedAlbumsRulesTest {
    private val trash = AlbumItem(VirtualAlbumRules.TRASH_KEY, "Lixeira", 2, null, 900, 1, 1, "")
    private val source = listOf(
        AlbumItem("travel", "Viagem", 1, null, 50, 1, 1, "Pictures/Travel/"),
        trash,
        AlbumItem(VirtualAlbumRules.RECENT_KEY, "Recentes", 3, null, 100, 1, 1, ""),
        AlbumItem("camera", "Câmera", 1, null, 20, 1, 1, "DCIM/Camera/"),
        AlbumItem("downloads", "Download", 1, null, 40, 1, 1, "Download/")
    )

    @Test
    fun trashIsTheLastStandardAlbumEvenWhenItIsTheNewest() {
        assertEquals(
            listOf(VirtualAlbumRules.RECENT_KEY, "downloads", "camera", VirtualAlbumRules.TRASH_KEY, "travel"),
            VirtualAlbumRules.pinEssential(source, activeScreenshotKey = null).map { it.key }
        )
    }

    @Test
    fun standardAlbumsStartPinnedAndShowThePin() {
        val shown = VirtualAlbumRules.pinnedKeys(source, emptySet(), emptySet(), null)
        assertEquals(setOf(VirtualAlbumRules.RECENT_KEY, VirtualAlbumRules.TRASH_KEY, "camera", "downloads"), shown)
        assertFalse("travel" in shown)
    }

    @Test
    fun anUnpinnedStandardAlbumLosesThePinAndJoinsTheOrdinaryOrder() {
        val unpinned = setOf("camera", VirtualAlbumRules.TRASH_KEY)
        assertEquals(
            listOf(VirtualAlbumRules.RECENT_KEY, "downloads", "travel", VirtualAlbumRules.TRASH_KEY, "camera"),
            VirtualAlbumRules.pinEssential(source, activeScreenshotKey = null, unpinnedEssentialKeys = unpinned).map { it.key }
        )
        assertEquals(setOf(VirtualAlbumRules.RECENT_KEY, "downloads"),
            VirtualAlbumRules.pinnedKeys(source, emptySet(), unpinned, null))
    }

    @Test
    fun togglingRemembersUnpinnedStandardAlbumsAndPinsOrdinaryOnes() {
        val essential = setOf("camera", VirtualAlbumRules.TRASH_KEY)
        val unpin = PinToggleRules.toggle(listOf("camera"), shownPinned = setOf("camera"), essential = essential,
            pinned = emptySet(), unpinnedEssential = emptySet())
        assertEquals(setOf("camera"), unpin.unpinnedEssential)
        assertTrue(unpin.pinned.isEmpty())

        val pinAgain = PinToggleRules.toggle(listOf("camera"), shownPinned = emptySet(), essential = essential,
            pinned = emptySet(), unpinnedEssential = setOf("camera"))
        assertTrue(pinAgain.unpinnedEssential.isEmpty())
        assertTrue("A standard album pinned again goes back to its own group", pinAgain.pinned.isEmpty())

        val mixed = PinToggleRules.toggle(listOf("camera", "travel"), shownPinned = setOf("camera"), essential = essential,
            pinned = emptySet(), unpinnedEssential = emptySet())
        assertEquals(setOf("travel"), mixed.pinned)
        assertTrue(mixed.unpinnedEssential.isEmpty())
    }
}

class ScrollDateRulesTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun yearsAndMonthFollowTheMediaDate() {
        val january2026 = 1_767_830_400L // 8 Jan 2026 UTC
        assertEquals(2026, ScrollDateRules.year(january2026, utc))
        assertNull(ScrollDateRules.year(0L, utc))
        assertEquals("Jan. de 2026",
            ScrollDateRules.monthYear(january2026, Locale.forLanguageTag("pt-BR"), utc, "MMM 'de' yyyy"))
    }

    @Test
    fun datesOnlyShowWhileTheAlbumRunsInDateOrder() {
        // Balloon, top year and marks: by date, or the custom order before anything was placed by hand.
        assertTrue(ScrollDateRules.followsDate(MediaSortRules.SORT_DATE))
        assertTrue(ScrollDateRules.followsDate(MediaSortRules.SORT_CUSTOM, manualOrder = false))
        assertFalse(ScrollDateRules.followsDate(MediaSortRules.SORT_CUSTOM, manualOrder = true))
        assertTrue(ScrollDateRules.followsDate(MediaSortRules.SORT_DATE, manualOrder = true))
        assertFalse(ScrollDateRules.followsDate(MediaSortRules.SORT_NAME))
        assertFalse(ScrollDateRules.followsDate(MediaSortRules.SORT_SIZE))
    }

    @Test
    fun yearMarksKeepTheFirstPositionOfEachYearAndSkipUnloadedPages() {
        val years = listOf(2026, 2026, 2025, null, 2025, 2023, 2023, 2015)
        assertEquals(listOf(0 to 2026, 2 to 2025, 5 to 2023, 7 to 2015),
            ScrollDateRules.yearMarks(years.size) { years[it] })
        val sampled = ScrollDateRules.yearMarks(10_000, samples = 100) { if (it < 5_000) 2026 else 2025 }
        assertEquals(listOf(0 to 2026, 5_000 to 2025), sampled)
    }
}

class AlbumSidebarRulesTest {
    @Test
    fun aSwipeToTheRightDragsTheSidebarAndReleaseDecidesIfItStays() {
        assertTrue(AlbumSidebarRules.startsDrag(dx = 30f, dy = 5f, slopPx = 8f))
        assertFalse("Ainda dentro da folga do toque", AlbumSidebarRules.startsDrag(dx = 10f, dy = 0f, slopPx = 8f))
        assertFalse("Diagonal demais", AlbumSidebarRules.startsDrag(dx = 30f, dy = 28f, slopPx = 8f))
        assertFalse("Para a esquerda", AlbumSidebarRules.startsDrag(dx = -30f, dy = 0f, slopPx = 8f))
        assertTrue("Movimento vertical é rolagem", AlbumSidebarRules.isScroll(dx = 5f, dy = 40f, slopPx = 8f))
        assertFalse(AlbumSidebarRules.isScroll(dx = 60f, dy = 20f, slopPx = 8f))
        val fling = 1000f
        assertTrue("Passou de um terço", AlbumSidebarRules.settlesOpen(0.4f, 0f, fling))
        assertFalse("Pouco aberto e parado", AlbumSidebarRules.settlesOpen(0.2f, 0f, fling))
        assertTrue("Arrasto rápido para a direita", AlbumSidebarRules.settlesOpen(0.1f, 1500f, fling))
        assertFalse("Arrasto rápido de volta", AlbumSidebarRules.settlesOpen(0.6f, -1500f, fling))
    }
}

class DestinationPathRulesTest {
    private val root = "/storage/emulated/0"

    @Test
    fun aNewFolderInsideTheStorageBecomesARelativeDestination() {
        assertEquals("Pictures/Viagem/", MediaActions.destinationRelativePath("/storage/emulated/0/Pictures/Viagem", false, root))
        assertEquals("DCIM/Viagem 2026/", MediaActions.destinationRelativePath("/storage/emulated/0/DCIM/Viagem 2026/", true, root))
        assertEquals("Pictures/Viagem/", MediaActions.destinationRelativePath("Pictures/Viagem", false, root))
        assertEquals("Uma pasta só com o mesmo começo de nome não é a raiz",
            "storage/emulated/01/Pictures/", MediaActions.destinationRelativePath("/storage/emulated/01/Pictures", false, root))
    }
}

class StoragePathRulesTest {
    private val primary = "/storage/emulated/0"

    @Test
    fun onlyFoldersOnTheSdCardNeedTheFullPath() {
        assertEquals("DCIM/Viagem/", StoragePathRules.volumeRelative("/storage/1A2B-3C4D/DCIM/Viagem", primary))
        assertEquals("Nova/", StoragePathRules.volumeRelative("/storage/1A2B-3C4D/Nova/", primary))
        assertNull(StoragePathRules.volumeRelative("/storage/emulated/0/Pictures/Viagem", primary))
        assertNull(StoragePathRules.volumeRelative("Pictures/Viagem", primary))
        assertNull(StoragePathRules.volumeRelative("/storage/1A2B-3C4D", primary))
        assertNull(StoragePathRules.volumeRelative("/storage/self/primary/DCIM", primary))
    }
}
