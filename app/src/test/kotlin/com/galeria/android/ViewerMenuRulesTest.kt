package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMenuRulesTest {
    @Test
    fun videoMenuContainsOnlyVideoCompatibleActions() {
        val options = ViewerMenuRules.options(isVideo = true, loopEnabled = false, shuffleMode = false)

        assertEquals(ViewerMenuRules.OPEN_WITH, options.first())
        assertTrue(options.contains(ViewerMenuRules.INFORMATION))
        assertTrue(options.contains(ViewerMenuRules.ENABLE_LOOP))
        assertFalse(options.contains(ViewerMenuRules.SET_AS))
        assertFalse(options.contains(ViewerMenuRules.ROTATE))
        assertFalse(options.contains(ViewerMenuRules.EXPORT_PDF))
        assertFalse(options.contains(ViewerMenuRules.RESIZE))
    }

    @Test
    fun shuffleVideoMenuOmitsConflictingLoopAction() {
        val options = ViewerMenuRules.options(isVideo = true, loopEnabled = true, shuffleMode = true)

        assertFalse(options.contains(ViewerMenuRules.ENABLE_LOOP))
        assertFalse(options.contains(ViewerMenuRules.DISABLE_LOOP))
    }

    @Test
    fun imageMenuCombinesFileActionsAndImageTools() {
        val options = ViewerMenuRules.options(isVideo = false, loopEnabled = false, shuffleMode = false)

        assertEquals(ViewerMenuRules.RENAME, options.first())
        assertTrue(options.contains(ViewerMenuRules.OPEN_WITH))
        assertTrue(options.contains(ViewerMenuRules.INFORMATION))
        assertTrue(options.contains(ViewerMenuRules.SET_AS))
        assertTrue(options.contains(ViewerMenuRules.ROTATE))
        assertTrue(options.contains(ViewerMenuRules.EXPORT_PDF))
        assertTrue(options.contains(ViewerMenuRules.PRESENTATION))
        assertFalse(options.contains(ViewerMenuRules.ENABLE_LOOP))
    }

    @Test
    fun mapOnlyAppearsForGeotaggedImages() {
        val withoutGps = ViewerMenuRules.options(false, false, false, hasLocation = false)
        val withGps = ViewerMenuRules.options(false, false, false, hasLocation = true)

        assertFalse(withoutGps.contains(ViewerMenuRules.SHOW_ON_MAP))
        assertTrue(withGps.contains(ViewerMenuRules.SHOW_ON_MAP))
    }

    @Test
    fun renameKeepsOriginalExtensionAndSanitizesInvalidCharacters() {
        assertEquals("nova_foto.jpg", ViewerMenuRules.normalizedRename("nova/foto", "original.jpg"))
        assertEquals("nova.png", ViewerMenuRules.normalizedRename("nova.png", "original.jpg"))
        assertEquals(null, ViewerMenuRules.normalizedRename("...", "original.jpg"))
    }
}
