package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaJoinRulesTest {
    private val portraitVideo = JoinMedia("content://v/1", "video/mp4", "a.mp4", 1080, 1920, 12_000L, 10L)
    private val landscapeVideo = JoinMedia("content://v/2", "video/mp4", "b.mp4", 3840, 2160, 8_000L, 20L)
    private val photo = JoinMedia("content://i/3", "image/jpeg", "c.jpg", 3000, 4000, size = 30L)
    private val landscapePhoto = JoinMedia("content://i/4", "image/jpeg", "d.jpg", 4000, 3000, size = 40L)

    @Test fun photoTimeStaysBetweenFourAndTenSeconds() {
        assertEquals(4, MediaJoinRules.photoSeconds(1))
        assertEquals(7, MediaJoinRules.photoSeconds(7))
        assertEquals(10, MediaJoinRules.photoSeconds(30))
    }

    @Test fun segmentsFollowTheOrderAndPhotoTime() {
        val clips = listOf(JoinClip(portraitVideo, listOf(0L..5_000L, 7_000L..12_000L)), JoinClip(photo), JoinClip(landscapeVideo))
        val segments = MediaJoinRules.segments(clips, 5_000L)
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L), segments.map { it.startMs })
        assertEquals(23_000L, MediaJoinRules.totalMs(clips, 5_000L))
        assertEquals(listOf(0, 0, 1, 2), segments.map { it.clipIndex })
        assertEquals(7_000L, segments[1].sourceStartMs)
    }

    @Test fun movingAClipTakesAllItsPartsAlong() {
        val clips = listOf(JoinClip(portraitVideo, listOf(0L..5_000L, 7_000L..12_000L)), JoinClip(photo), JoinClip(landscapeVideo))
        val moved = MediaJoinRules.moved(clips, 0, 2)
        assertEquals(listOf(photo, landscapeVideo, portraitVideo), moved.map { it.media })
        assertEquals(listOf(0, 1, 2, 2), MediaJoinRules.segments(moved, 5_000L).map { it.clipIndex })
        assertSame(clips, MediaJoinRules.moved(clips, 1, 1))
    }

    @Test fun cuttingTheMiddleKeepsTwoPartsOfTheSameMedia() {
        val whole = JoinClip(portraitVideo)
        assertFalse(whole.trimmed)
        val split = MediaJoinRules.split(whole, 5_000L)
        assertEquals(listOf(0L..5_000L, 5_000L..12_000L), split.ranges)
        // Dragging the inner edges opens the gap that is removed.
        val cut = MediaJoinRules.trimmed(MediaJoinRules.trimmed(split, 1, true, 7_000L), 0, false, 4_000L)
        assertEquals(listOf(0L..4_000L, 7_000L..12_000L), cut.ranges)
        assertTrue(cut.trimmed)
        // A part never crosses its neighbour or shrinks below the minimum.
        assertEquals(4_000L..12_000L, MediaJoinRules.trimmed(cut, 1, true, 1_000L).ranges[1])
        assertEquals(0L..500L, MediaJoinRules.trimmed(cut, 0, false, 0L).ranges[0])
        // Too close to an edge: nothing to split.
        assertSame(whole, MediaJoinRules.split(whole, 200L))
    }

    @Test fun canvasIsLandscapeOnlyWhenEverythingIsLandscape() {
        assertEquals(JoinCanvas(3840, 2160), MediaJoinRules.canvas(listOf(landscapeVideo, landscapePhoto), JoinQuality.NATIVE))
        // Mixed: portrait, keeping the largest video's short side, so the 4K video is not reduced.
        assertEquals(JoinCanvas(2160, 3840), MediaJoinRules.canvas(listOf(portraitVideo, landscapeVideo, photo), JoinQuality.NATIVE))
        assertEquals(JoinCanvas(1080, 1920), MediaJoinRules.canvas(listOf(portraitVideo, landscapeVideo), JoinQuality.P1080))
        assertEquals(JoinCanvas(720, 1280), MediaJoinRules.canvas(listOf(landscapeVideo, portraitVideo), JoinQuality.P720))
    }

    @Test fun photosAloneKeepTheirShapeUpTo4K() {
        assertEquals(JoinCanvas(2160, 2880), MediaJoinRules.canvas(listOf(photo, landscapePhoto), JoinQuality.NATIVE))
        assertEquals(JoinCanvas(2880, 2160), MediaJoinRules.canvas(listOf(landscapePhoto), JoinQuality.NATIVE))
        val small = JoinMedia("content://i/5", "image/png", "e.png", 641, 401)
        // Odd sizes round down to even numbers, which every encoder accepts.
        assertEquals(JoinCanvas(638, 400), MediaJoinRules.canvas(listOf(small), JoinQuality.NATIVE))
    }

    @Test fun oneCutVideoIsCopiedAndEverythingElseIsEncoded() {
        val cut = JoinClip(portraitVideo, listOf(0L..4_000L, 7_000L..12_000L))
        assertTrue(MediaJoinRules.copiesWithoutEncoding(JoinRequest(listOf(cut), 5_000L, JoinQuality.NATIVE)))
        assertFalse(MediaJoinRules.copiesWithoutEncoding(JoinRequest(listOf(cut), 5_000L, JoinQuality.P720)))
        assertFalse(MediaJoinRules.copiesWithoutEncoding(JoinRequest(listOf(cut, JoinClip(photo)), 5_000L, JoinQuality.NATIVE)))
        assertFalse(MediaJoinRules.copiesWithoutEncoding(JoinRequest(listOf(JoinClip(photo)), 5_000L, JoinQuality.NATIVE)))
        val webm = portraitVideo.copy(name = "a.webm", mime = "video/webm")
        assertFalse(MediaJoinRules.copiesWithoutEncoding(JoinRequest(listOf(JoinClip(webm)), 5_000L, JoinQuality.NATIVE)))
    }

    @Test fun aTransitionOverlapsTheTwoMediaAndShortensTheVideo() {
        val red = photo.copy(uri = "content://i/r")
        val blue = photo.copy(uri = "content://i/b")
        val clips = listOf(JoinClip(red), JoinClip(blue), JoinClip(portraitVideo, listOf(0L..800L)))
        val transitions = listOf(JoinTransition.DISSOLVE, JoinTransition.SLIDE)
        // 600 ms between the photos; the 0.8 s video limits the second one to half its length.
        assertEquals(600L, MediaJoinRules.overlapMs(clips, 4_000L, transitions, 0))
        assertEquals(400L, MediaJoinRules.overlapMs(clips, 4_000L, transitions, 1))
        assertEquals(listOf(0L, 3_400L, 7_000L), MediaJoinRules.clipStarts(clips, 4_000L, transitions))
        assertEquals(7_800L, MediaJoinRules.totalMs(clips, 4_000L, transitions))
        assertEquals(listOf(JoinWindow(0, JoinTransition.DISSOLVE, 3_400L, 4_000L), JoinWindow(1, JoinTransition.SLIDE, 7_000L, 7_400L)),
            MediaJoinRules.windows(clips, 4_000L, transitions))
        assertEquals(listOf(0L, 3_400L, 7_000L), MediaJoinRules.segments(clips, 4_000L, transitions).map { it.startMs })
        // Without transitions nothing overlaps.
        assertEquals(8_800L, MediaJoinRules.totalMs(clips, 4_000L))
    }

    @Test fun layersShowOneMediaOutsideATransitionAndBothInside() {
        val clips = listOf(JoinClip(photo), JoinClip(landscapePhoto), JoinClip(photo.copy(uri = "content://i/9")))
        fun look(type: JoinTransition, layer: Int, t: Long) =
            MediaJoinRules.look(clips, 4_000L, listOf(type, JoinTransition.NONE), layer, t)
        // Before the junction only the first clip (layer 0) shows; after it, only the second (layer 1).
        assertEquals(1f, look(JoinTransition.DISSOLVE, 0, 1_000L).alpha)
        assertEquals(0f, look(JoinTransition.DISSOLVE, 1, 1_000L).alpha)
        assertEquals(1f, look(JoinTransition.DISSOLVE, 1, 5_000L).alpha)
        assertEquals(0f, look(JoinTransition.DISSOLVE, 0, 5_000L).alpha)
        // Halfway through (3.4 s to 4.0 s): the top layer is half transparent, the one below solid.
        assertEquals(0.5f, look(JoinTransition.DISSOLVE, 0, 3_700L).alpha, 0.01f)
        assertEquals(1f, look(JoinTransition.DISSOLVE, 1, 3_700L).alpha)
        // Fading through black: both are dark at the middle.
        assertEquals(0f, look(JoinTransition.FADE, 0, 3_700L).alpha, 0.01f)
        assertEquals(0f, look(JoinTransition.FADE, 1, 3_700L).alpha, 0.01f)
        // Sliding: the outgoing frame leaves to the left as the next one enters from the right.
        assertEquals(-0.5f, look(JoinTransition.SLIDE, 0, 3_700L).shift, 0.01f)
        assertEquals(0.5f, look(JoinTransition.SLIDE, 1, 3_700L).shift, 0.01f)
        assertTrue(look(JoinTransition.ZOOM, 0, 3_700L).scale > 1f)
        // The third clip goes back to layer 0, after a plain cut.
        assertEquals(1f, look(JoinTransition.DISSOLVE, 0, 7_500L).alpha)
    }

    @Test fun recordSurvivesTheRoundTrip() {
        val odd = JoinMedia("content://i/6?x=1", "image/jpeg", "férias\t2026 - 1.jpg", 10, 20, size = 7L)
        val record = JoinRecord(6_000L, listOf(JoinClip(portraitVideo, listOf(0L..4_000L, 7_000L..12_000L)), JoinClip(odd)), listOf(JoinTransition.ZOOM))
        assertEquals(record, MediaJoinRules.decode(MediaJoinRules.encode(record)))
        assertNull(MediaJoinRules.decode("outro formato"))
    }
}
