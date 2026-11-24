package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSplitRulesTest {
    private val video = JoinMedia("content://media/external/video/media/1", "video/mp4", "a.mp4", 1080, 1920, 10_000L)
    private val otherVideo = JoinMedia("content://media/external/video/media/2", "video/mp4", "b.mp4", 1080, 1920, 8_000L)
    private val photo = JoinMedia("content://media/external/images/media/3", "image/heic", "c.heic", 3000, 4000)

    @Test fun onlyJoinsOfTwoOrMoreMediaCanBeSeparated() {
        assertFalse(MediaSplitRules.separable(null))
        assertFalse(MediaSplitRules.separable(JoinRecord(5_000L, listOf(JoinClip(video)))))
        assertTrue(MediaSplitRules.separable(JoinRecord(5_000L, listOf(JoinClip(video), JoinClip(photo)))))
    }

    @Test fun originalsStillAroundAreUsedAndTrashedOnesRestored() {
        val record = JoinRecord(5_000L, listOf(JoinClip(video), JoinClip(photo), JoinClip(otherVideo)))
        val steps = MediaSplitRules.plan(record, listOf(OriginState.PRESENT, OriginState.TRASHED, OriginState.PRESENT), emptySet())
        assertEquals(listOf(
            SplitStep.Keep(0, video.uri), SplitStep.Restore(1, photo.uri), SplitStep.Keep(2, otherVideo.uri)
        ), steps)
    }

    @Test fun deletedMediaComeBackFromTheJoinedVideoAtTheirPlace() {
        val record = JoinRecord(5_000L, listOf(JoinClip(video), JoinClip(photo), JoinClip(otherVideo)))
        val gone = List(3) { OriginState.GONE }
        assertEquals(listOf(
            SplitStep.CutJoined(0, 0L, 10_000L, newVideo = false),
            SplitStep.PhotoFrame(1, 12_500L),
            SplitStep.CutJoined(2, 15_000L, 23_000L, newVideo = false)
        ), MediaSplitRules.plan(record, gone, emptySet()))
        // With the photo clock on, the photo itself was kept inside the joined video.
        assertEquals(SplitStep.PhotoEmbedded(1), MediaSplitRules.plan(record, gone, setOf(1))[1])
    }

    @Test fun aVideoCutInTheMontageAlwaysComesBackAsANewVideo() {
        val cut = JoinClip(video, listOf(0L..3_000L, 6_000L..10_000L))
        val record = JoinRecord(5_000L, listOf(cut, JoinClip(video), JoinClip(photo)))
        val present = MediaSplitRules.plan(record, List(3) { OriginState.PRESENT }, emptySet())
        assertEquals(SplitStep.CutOriginal(0, video.uri, cut.ranges), present[0])
        // The whole video used again is the same original, kept once.
        assertEquals(SplitStep.Keep(1, video.uri), present[1])
        val trashed = MediaSplitRules.plan(record, listOf(OriginState.TRASHED, OriginState.TRASHED, OriginState.PRESENT), emptySet())
        assertEquals(SplitStep.CutJoined(0, 0L, 7_000L, newVideo = true), trashed[0])
        assertEquals(SplitStep.Restore(1, video.uri), trashed[1])
    }

    @Test fun theSameOriginalUsedTwiceComesBackOnce() {
        val record = JoinRecord(4_000L, listOf(JoinClip(photo), JoinClip(video), JoinClip(photo)))
        val steps = MediaSplitRules.plan(record, List(3) { OriginState.GONE }, emptySet())
        assertEquals(listOf(0, 1), steps.map { it.clipIndex })
    }

    @Test fun transitionsMoveTheTimesOfTheLaterMedia() {
        val record = JoinRecord(4_000L, listOf(JoinClip(photo), JoinClip(video)), listOf(JoinTransition.DISSOLVE))
        val step = MediaSplitRules.plan(record, List(2) { OriginState.GONE }, emptySet())[1] as SplitStep.CutJoined
        assertEquals(4_000L - MediaJoinRules.TRANSITION_MS, step.startMs)
        assertEquals(step.startMs + 10_000L, step.endMs)
    }

    @Test fun aPhotoFrameDropsTheBlackBordersOfTheJoinedVideo() {
        // A landscape photo in a portrait video: bars above and below.
        assertEquals(MediaSplitRules.Area(0, 656, 1080, 608), MediaSplitRules.fitted(1080, 1920, 4000, 2250))
        assertEquals(MediaSplitRules.Area(0, 0, 1080, 1920), MediaSplitRules.fitted(1080, 1920, 1080, 1920))
    }

    @Test fun aPhotoWrittenFromAFrameIsAJpeg() {
        assertEquals("c.jpg", MediaSplitRules.frameName("c.heic"))
        assertEquals("foto.jpg", MediaSplitRules.frameName(".heic"))
    }
}
