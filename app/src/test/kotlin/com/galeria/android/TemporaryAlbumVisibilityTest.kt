package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryAlbumVisibilityTest {
    @Test fun revealExpiresAfterThirtyElapsedMinutesEvenWhenNotQueried() {
        var now = 100L
        val store = TemporaryAlbumRevealStore { now }
        assertTrue(store.toggle("secret"))
        now += 29 * 60 * 1000L
        assertEquals(setOf("secret"), store.activeKeys())
        now += 60 * 1000L
        assertTrue(store.activeKeys().isEmpty())
        assertEquals(null, store.nextExpiryDelay())
    }

    @Test fun tappingAgainHidesImmediatelyAndClosingClearsAllReveals() {
        var now = 0L
        val store = TemporaryAlbumRevealStore { now }
        assertTrue(store.toggle("one"))
        assertEquals(TemporaryAlbumRevealStore.DURATION_MILLIS, store.nextExpiryDelay())
        assertFalse(store.toggle("one"))
        assertTrue(store.activeKeys().isEmpty())
        store.toggle("one")
        store.toggle("two")
        now += 10_000L
        store.clear()
        assertTrue(store.activeKeys().isEmpty())
    }
}
