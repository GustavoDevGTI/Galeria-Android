package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerStateRulesTest {
    @Test
    fun sameSeedRestoresTheSameShuffleFlow() {
        val media = listOf("image-1", "image-2", "video-3", "image-4")
        val first = ViewerStateRules.shuffledFromCurrent(
            availableUris = media,
            currentUri = "image-1",
            seed = 9821L
        )
        val current = first[2]
        val restored = ViewerStateRules.shuffledFromCurrent(
            availableUris = media,
            currentUri = current,
            seed = 9821L
        )
        val expectedFlow = first.drop(2) + first.take(2)

        assertEquals(expectedFlow, restored)
        assertEquals(current, restored.first())
    }

    @Test
    fun sameSeedProducesRepeatableOrder() {
        val media = listOf("image-1", "image-2", "video-3", "image-4")
        val first = ViewerStateRules.shuffledFromCurrent(media, "video-3", 77L)
        val second = ViewerStateRules.shuffledFromCurrent(media.reversed(), "video-3", 77L)

        assertEquals(first, second)
        assertEquals("video-3", first.first())
        assertEquals(media.toSet(), first.toSet())
    }

    @Test
    fun missingCurrentMediaStillReturnsEveryAvailableItem() {
        val media = listOf("image-1", "image-2", "video-3")
        val restored = ViewerStateRules.shuffledFromCurrent(
            availableUris = media,
            currentUri = "removed",
            seed = 19L
        )

        assertEquals(media.toSet(), restored.toSet())
    }

    @Test
    fun navigationWrapsAtBothEndsOfTheQueue() {
        assertEquals(0, ViewerStateRules.advancedIndex(2, 1, 3))
        assertEquals(2, ViewerStateRules.advancedIndex(0, -1, 3))
        assertEquals(1, ViewerStateRules.wrappedIndex(7, 3))
    }

    @Test
    fun removalKeepsTheSameSlotOrMovesToTheNewLastItem() {
        assertEquals(1, ViewerStateRules.indexAfterRemoval(1, 3))
        assertEquals(2, ViewerStateRules.indexAfterRemoval(3, 3))
        assertEquals(null, ViewerStateRules.indexAfterRemoval(0, 0))
    }

    @Test
    fun customOrderKeepsOnlyAvailableUrisWithoutDuplicates() {
        val ordered = ViewerStateRules.orderedUris(
            availableUris = listOf("a", "b", "c"),
            customOrder = listOf("c", "missing", "a", "c")
        )

        assertEquals(listOf("c", "a"), ordered)
    }

    @Test
    fun videoLoopPreferenceIsDisabledDuringShuffleAndPresentation() {
        assertEquals(true, ViewerStateRules.shouldLoopVideo(false, false, true))
        assertEquals(false, ViewerStateRules.shouldLoopVideo(true, false, true))
        assertEquals(false, ViewerStateRules.shouldLoopVideo(false, true, true))
        assertEquals(false, ViewerStateRules.shouldLoopVideo(false, false, false))
    }
}
