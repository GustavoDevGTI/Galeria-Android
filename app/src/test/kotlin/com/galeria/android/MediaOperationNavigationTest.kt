package com.galeria.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaOperationNavigationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anActuallyEmptyFolderAllowsRedirection() {
        assertTrue(MediaOperationNavigation.isEmptyFolder(temporary.newFolder()))
    }

    @Test fun noMediaMarkerAloneDoesNotKeepUserInAnEmptyFolder() {
        val folder = temporary.newFolder()
        assertTrue(java.io.File(folder, ".nomedia").createNewFile())
        assertTrue(MediaOperationNavigation.isEmptyFolder(folder))
    }

    @Test fun nonMediaFilesAndSubfoldersPreventFalseEmptyDecisions() {
        val folder = temporary.newFolder()
        assertTrue(java.io.File(folder, "document.txt").createNewFile())
        assertFalse(MediaOperationNavigation.isEmptyFolder(folder))
        val parent = temporary.newFolder()
        assertTrue(java.io.File(parent, "nested").mkdir())
        assertFalse(MediaOperationNavigation.isEmptyFolder(parent))
    }

    @Test fun unknownOrMissingSourceDoesNotTriggerNavigation() {
        assertFalse(MediaOperationNavigation.isEmptyFolder(null))
        assertFalse(MediaOperationNavigation.isEmptyFolder(java.io.File(temporary.root, "missing")))
    }
}
