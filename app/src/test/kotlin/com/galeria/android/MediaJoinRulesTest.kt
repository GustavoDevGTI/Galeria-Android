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
        assertEquals(JoinCanvas(1080, 1920), MediaJoinRules.canvas(listOf(portraitVideo, landscapeVideo), JoinQuality(1080)))
        assertEquals(JoinCanvas(720, 1280), MediaJoinRules.canvas(listOf(landscapeVideo, portraitVideo), JoinQuality(720)))
    }

    @Test fun qualityChoicesOnlyOfferSizesBelowTheNativeOne() {
        val native = JoinQuality.NATIVE
        // A 1080p video: 1080p and up would give the same video, so they are not offered.
        assertEquals(listOf(native, JoinQuality(720), JoinQuality(540), JoinQuality(480), JoinQuality(360)),
            MediaJoinRules.qualityOptions(listOf(portraitVideo)))
        assertEquals(listOf(native) + listOf(1440, 1080, 720, 540, 480, 360).map { JoinQuality(it) },
            MediaJoinRules.qualityOptions(listOf(landscapeVideo)))
        val small = portraitVideo.copy(width = 480, height = 854)
        assertEquals(listOf(native, JoinQuality(360)), MediaJoinRules.qualityOptions(listOf(small)))
        assertEquals(listOf(native), MediaJoinRules.qualityOptions(listOf(small.copy(width = 360, height = 640))))
        // A choice that would not lower the size counts as Native.
        assertEquals(native, MediaJoinRules.effectiveQuality(listOf(small), JoinQuality(720)))
        assertEquals(JoinQuality(360), MediaJoinRules.effectiveQuality(listOf(small), JoinQuality(360)))
        // Lowering keeps the shape: 1080×1920 at 540p is 540×960.
        assertEquals(JoinCanvas(540, 960), MediaJoinRules.canvas(listOf(portraitVideo), JoinQuality(540)))
    }

    @Test fun aSmallerSizeAsksForFewerBitsSoTheFileShrinks() {
        val full = JoinCanvas(1080, 1920)
        assertEquals(17_000_000, MediaJoinRules.videoBitrate(17_000_000, full, full))
        val at720 = MediaJoinRules.videoBitrate(17_000_000, full, JoinCanvas(720, 1280))
        assertTrue("720p fica com cerca de 54% dos bits: $at720", at720 in 9_000_000..9_500_000)
        // Never more than the source, never below the floor, nothing to go by gives 0.
        assertEquals(1_000_000, MediaJoinRules.videoBitrate(1_000_000, full, JoinCanvas(2160, 3840)))
        assertEquals(MediaJoinRules.MIN_BITRATE, MediaJoinRules.videoBitrate(300_000, JoinCanvas(2160, 3840), JoinCanvas(360, 640)))
        assertEquals(0, MediaJoinRules.videoBitrate(0, full, JoinCanvas(720, 1280)))
    }

    @Test fun theSizeEstimateFollowsTheVideosBitrateAndTheChosenSize() {
        // 12 s and 24 MB: 16 Mbit/s.
        val video = portraitVideo.copy(size = 24_000_000L)
        assertEquals(16_000_000, MediaJoinRules.fileBitrate(video))
        val clip = JoinClip(video)
        assertEquals(24_000_000L, MediaJoinRules.estimatedBytes(JoinRequest(listOf(clip), 5_000L, JoinQuality.NATIVE)))
        val smaller = MediaJoinRules.estimatedBytes(JoinRequest(listOf(clip), 5_000L, JoinQuality(720)))!!
        assertTrue("720p ≈ 13 MB: $smaller", smaller in 12_500_000L..14_000_000L)
        // Cutting half of it halves the estimate.
        val half = MediaJoinRules.estimatedBytes(JoinRequest(listOf(JoinClip(video, listOf(0L..6_000L))), 5_000L, JoinQuality.NATIVE))
        assertEquals(12_000_000L, half)
        assertNull(MediaJoinRules.estimatedBytes(JoinRequest(listOf(JoinClip(photo)), 5_000L, JoinQuality.NATIVE)))
    }

    @Test fun theIndexReserveGrowsWithTheVideoInsteadOfAFixed400KB() {
        assertEquals(18_000, MediaJoinRules.indexReserveBytes(1_000L))
        assertEquals(19_000, MediaJoinRules.indexReserveBytes(1_000L, 1_000))
        assertEquals(136_000, MediaJoinRules.indexReserveBytes(60_000L))
        assertEquals(400_000, MediaJoinRules.indexReserveBytes(3_600_000L))
    }

    @Test fun aMergeIsNamedAfterItsFirstMediaWithMergedAtTheEnd() {
        assertEquals("Gustavo Merged.mp4", MediaJoinRules.outputName("Gustavo.mp4"))
        assertEquals("Praia 2024 Merged.mp4", MediaJoinRules.outputName("Praia 2024.jpg"))
        assertEquals("video Merged.mp4", MediaJoinRules.outputName(".mp4"))
        val merge = JoinRequest(listOf(JoinClip(portraitVideo), JoinClip(photo)), 5_000L, JoinQuality.NATIVE)
        assertEquals("a Merged.mp4", MediaJoinExporter.defaultName(merge, 42L))
    }

    @Test fun wholeMatchingVideosAtNativeAreMergedByCopying() {
        val second = portraitVideo.copy(uri = "content://v/9")
        val whole = JoinRequest(listOf(JoinClip(portraitVideo), JoinClip(second)), 5_000L, JoinQuality.NATIVE)
        assertTrue(VideoConcatRules.eligible(whole))
        // A photo, a cut, a transition, a smaller size or a single video: encoded (or copied by the trimmer).
        assertFalse(VideoConcatRules.eligible(whole.copy(clips = whole.clips + JoinClip(photo))))
        assertFalse(VideoConcatRules.eligible(whole.copy(clips = listOf(JoinClip(portraitVideo, listOf(0L..4_000L)), JoinClip(second)))))
        assertFalse(VideoConcatRules.eligible(whole.copy(transitions = listOf(JoinTransition.FADE))))
        assertFalse(VideoConcatRules.eligible(whole.copy(quality = JoinQuality(720))))
        assertFalse(VideoConcatRules.eligible(whole.copy(clips = whole.clips.take(1))))
        assertFalse(VideoConcatRules.eligible(whole.copy(clips = listOf(JoinClip(portraitVideo.copy(name = "a.webm", mime = "video/webm")), JoinClip(second)))))
    }

    @Test fun onlyVideosWithTheSameTracksAndSetupAreCopiedTogether() {
        val video = TrackSignature("video/avc", 1920, 1080, setup = listOf("0001", "0002"))
        val audio = TrackSignature("audio/mp4a-latm", sampleRate = 48_000, channels = 2, setup = listOf("1190"))
        assertTrue(VideoConcatRules.compatible(listOf(listOf(video, audio), listOf(video, audio)), listOf(90, 90)))
        assertFalse("Outra resolução", VideoConcatRules.compatible(listOf(listOf(video, audio), listOf(video.copy(width = 1280, height = 720), audio)), listOf(0, 0)))
        assertFalse("Outro ajuste do codificador", VideoConcatRules.compatible(listOf(listOf(video), listOf(video.copy(setup = listOf("0003", "0002")))), listOf(0, 0)))
        assertFalse("Outra rotação", VideoConcatRules.compatible(listOf(listOf(video), listOf(video)), listOf(0, 90)))
        assertFalse("Um sem som", VideoConcatRules.compatible(listOf(listOf(video, audio), listOf(video)), listOf(0, 0)))
        assertFalse("Legenda", VideoConcatRules.compatible(listOf(listOf(video, TrackSignature("text/vtt")), listOf(video, TrackSignature("text/vtt"))), listOf(0, 0)))
        assertEquals(listOf(0L, 12_000_000L, 20_000_000L), VideoConcatRules.offsetsUs(listOf(12_000L, 8_000L, 5_000L)))
    }

    @Test fun aVideoOnlyMadeSmallerIsNamedByItsNewSize() {
        val whole = JoinRequest(listOf(JoinClip(portraitVideo)), 5_000L, JoinQuality(720))
        assertEquals("a-720p-42.mp4", MediaJoinExporter.defaultName(whole, 42L))
        val cut = whole.copy(clips = listOf(JoinClip(portraitVideo, listOf(0L..4_000L))))
        assertEquals("a-cortado-42.mp4", MediaJoinExporter.defaultName(cut, 42L))
        assertEquals("a-cortado-42.mp4", MediaJoinExporter.defaultName(cut.copy(quality = JoinQuality.NATIVE), 42L))
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
        assertFalse(MediaJoinRules.copiesWithoutEncoding(JoinRequest(listOf(cut), 5_000L, JoinQuality(720))))
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
