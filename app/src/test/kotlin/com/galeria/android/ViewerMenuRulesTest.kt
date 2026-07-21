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
        assertFalse(options.contains(ViewerMenuRules.PRINT))
        assertFalse(options.contains(ViewerMenuRules.RESIZE))
    }

    @Test
    fun shuffleVideoMenuOmitsConflictingLoopAction() {
        val options = ViewerMenuRules.options(isVideo = true, loopEnabled = true, shuffleMode = true)

        assertFalse(options.contains(ViewerMenuRules.ENABLE_LOOP))
        assertFalse(options.contains(ViewerMenuRules.DISABLE_LOOP))
    }

    @Test
    fun imageMenuKeepsImageToolsAndOmitsVideoActions() {
        val options = ViewerMenuRules.options(isVideo = false, loopEnabled = false, shuffleMode = false)

        assertTrue(options.contains(ViewerMenuRules.SET_AS))
        assertTrue(options.contains(ViewerMenuRules.ROTATE))
        assertFalse(options.contains(ViewerMenuRules.OPEN_WITH))
        assertFalse(options.contains(ViewerMenuRules.INFORMATION))
    }
}
