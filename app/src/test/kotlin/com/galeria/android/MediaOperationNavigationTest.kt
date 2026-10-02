package com.galeria.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaOperationNavigationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anActuallyEmptyFolderAllowsRedirection() {
        assertTrue(MediaOperationNavigation.isEmptyMediaFolder(temporary.newFolder()))
    }

    @Test fun noMediaMarkerAloneDoesNotKeepUserInAnEmptyFolder() {
        val folder = temporary.newFolder()
        assertTrue(java.io.File(folder, ".nomedia").createNewFile())
        assertTrue(MediaOperationNavigation.isEmptyMediaFolder(folder))
    }

    @Test fun documentsAndNestedAlbumsDoNotKeepAnEmptyMediaAlbumOpen() {
        val folder = temporary.newFolder()
        assertTrue(java.io.File(folder, "document.txt").createNewFile())
        assertTrue(MediaOperationNavigation.isEmptyMediaFolder(folder))
        val parent = temporary.newFolder()
        assertTrue(java.io.File(parent, "nested").mkdir())
        assertTrue(MediaOperationNavigation.isEmptyMediaFolder(parent))
    }

    @Test fun unknownOrMissingSourceDoesNotTriggerNavigation() {
        assertFalse(MediaOperationNavigation.isEmptyMediaFolder(null))
        assertFalse(MediaOperationNavigation.isEmptyMediaFolder(java.io.File(temporary.root, "missing")))
    }

    @Test fun remainingMediaOutsideTheCurrentFilterPreventsNavigation() {
        val folder = temporary.newFolder()
        java.io.File(folder, "remaining.MKV").createNewFile()
        assertFalse(MediaOperationNavigation.isEmptyMediaFolder(folder))
        assertEquals(1, MediaOperationNavigation.mediaCount(folder))
    }

    @Test fun trashedAndPendingFilesDoNotCountAsVisibleMedia() {
        val folder = temporary.newFolder()
        java.io.File(folder, ".trashed-123-photo.jpg").createNewFile()
        java.io.File(folder, ".pending-123-video.mp4").createNewFile()
        assertTrue(MediaOperationNavigation.isEmptyMediaFolder(folder))
    }
}
