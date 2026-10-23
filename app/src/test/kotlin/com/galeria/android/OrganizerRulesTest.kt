package com.galeria.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OrganizerRulesTest {
    @Test fun gesturesCoverEverySectorForOneThroughEightDestinations() {
        for (count in 1..8) repeat(count) { index ->
            val center = -90.0 + index * 360.0 / count
            for (offset in listOf(-.49, 0.0, .49)) {
                val angle = Math.toRadians(center + offset * 360 / count)
                assertEquals("count=$count index=$index offset=$offset", index,
                    OrganizerRules.releasedTarget((cos(angle) * 80).toFloat(), (sin(angle) * 80).toFloat(), 400f, 0f, count))
            }
        }
    }
    @Test fun shortFastPushWorksButJitterAndSlowShortDragDoNotSend() {
        assertEquals(2, OrganizerRules.releasedTarget(30f, 0f, 400f, .5f))
        assertNull(OrganizerRules.releasedTarget(30f, 0f, 400f, .1f))
        assertNull(OrganizerRules.releasedTarget(8f, 0f, 400f, 4f))
        assertNull(OrganizerRules.releasedTarget(30f, 0f, 0f, 4f))
    }
    @Test fun configurationAcceptsOneThroughEightDistinctDestinationsExcludingTheSource() {
        val keys = (0..7).map { "album$it" }
        for (count in 1..8) assertTrue(OrganizerRules.validSelection("source", keys.take(count)))
        assertFalse(OrganizerRules.validSelection(null, keys))
        assertFalse(OrganizerRules.validSelection("source", emptyList()))
        assertFalse(OrganizerRules.validSelection("source", keys + "ninth"))
        assertFalse(OrganizerRules.validSelection("album0", keys))
        assertFalse(OrganizerRules.validSelection("source", keys.drop(1) + "album1"))
    }
    @Test fun singleDestinationReceivesEveryDirectionAndInvalidCountsNeverDispatch() {
        for (degrees in 0..359 step 15) {
            val angle = Math.toRadians(degrees.toDouble())
            assertEquals(0, OrganizerRules.releasedTarget((cos(angle) * 80).toFloat(), (sin(angle) * 80).toFloat(), 400f, 0f, 1))
        }
        assertNull(OrganizerRules.releasedTarget(80f, 0f, 400f, 1f, 0))
        assertNull(OrganizerRules.releasedTarget(80f, 0f, 400f, 1f, 9))
    }
    @Test fun swappingConservesAllAlbumsAndDoesNotShiftNeighbors() {
        val albums = (0..7).toMutableList()
        assertTrue(OrganizerRules.swap(albums, 1, 6))
        assertEquals(listOf(0, 6, 2, 3, 4, 5, 1, 7), albums)
        assertFalse(OrganizerRules.swap(albums, -1, 3))
        assertFalse(OrganizerRules.swap(albums, 3, 8))
        assertFalse(OrganizerRules.swap(albums, 2, 2))
    }
    @Test fun rapidDispatchPromotesImmediatelyAndFailedMovesAreRestorable() {
        val media = OrganizerQueue(listOf("a", "b", "c"))
        val a = requireNotNull(media.take("a", false))
        assertEquals("b", media.queue.first())
        val b = requireNotNull(media.take("b", false))
        assertEquals(listOf("c"), media.queue)
        media.restore(b); media.restore(a)
        assertEquals(listOf("a", "b", "c"), media.queue)
        assertNull(media.take("c", false))
    }
    @Test fun reserveCanBeDistributedWithoutConsumingTheCentralQueue() {
        val media = OrganizerQueue(listOf("a", "b", "c"))
        media.reserve(); media.reserve()
        assertEquals(listOf("a", "b"), media.reserved)
        val ticket = requireNotNull(media.take("a", true))
        assertEquals(listOf("c"), media.queue)
        media.restore(ticket, "a-renamed-on-undo")
        assertEquals(listOf("a-renamed-on-undo", "b"), media.reserved)
    }
    @Test fun serialFailuresRestoreTheOriginalOrderEvenWhenEveryTicketWasTakenAtZero() {
        val media = OrganizerQueue(listOf("a", "b", "c"))
        val a = requireNotNull(media.take("a", false))
        val b = requireNotNull(media.take("b", false))
        media.restore(a); media.restore(b)
        assertEquals(listOf("a", "b", "c"), media.queue)
    }
    @Test fun reserveAndUndoWorkAtTheEndOfTheQueueWithoutDuplicates() {
        val media = OrganizerQueue(listOf("last"))
        val ticket = requireNotNull(media.reserve())
        assertTrue(media.queue.isEmpty()); assertNull(media.reserve())
        media.reserved.remove(ticket.item); media.restore(ticket); media.restore(ticket)
        assertEquals(listOf("last"), media.queue); assertTrue(media.reserved.isEmpty())
    }
}
