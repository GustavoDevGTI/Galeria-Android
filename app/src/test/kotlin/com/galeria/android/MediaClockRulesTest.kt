package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockRulesTest {
    private val video = JoinMedia("content://media/external/video/media/11", "video/mp4", "a.mp4", 1080, 1920, 5_000L)
    private val otherVideo = JoinMedia("content://media/external/video/media/12", "video/mp4", "b.mp4", 1080, 1920, 5_000L)
    private val photo = JoinMedia("content://media/external/images/media/13", "image/jpeg", "c.jpg", 3000, 4000)
    private val hour = 60L * 60L * 1000L

    @Test fun onlyVideosGetTheClockUnlessPhotosAreAsked() {
        val clips = listOf(JoinClip(photo), JoinClip(video), JoinClip(otherVideo))
        assertEquals(listOf(video.uri, otherVideo.uri), MediaClockRules.targets(clips, clockPhotos = false))
        assertEquals(listOf(photo.uri, video.uri, otherVideo.uri), MediaClockRules.targets(clips, clockPhotos = true))
    }

    @Test fun aSingleEditGetsNoClockAndARepeatedMediaGetsOne() {
        assertTrue(MediaClockRules.targets(listOf(JoinClip(video)), clockPhotos = true).isEmpty())
        // The same video twice in a join, once through the photo picker's own id form.
        val again = video.copy(uri = "content://media/external_primary/video/media/11")
        assertEquals(listOf(video.uri), MediaClockRules.targets(listOf(JoinClip(video), JoinClip(again)), clockPhotos = false))
    }

    @Test fun theClockLastsFortyEightHoursAndANewJoinRestartsIt() {
        val first = MediaClockRules.started(emptyList(), listOf(video.uri), now = 1_000L)
        assertEquals(1_000L + 48 * hour, first.single().expiresAt)
        val later = MediaClockRules.started(first, listOf(video.uri, photo.uri), now = 5 * hour)
        assertEquals(listOf(5 * hour + 48 * hour, 5 * hour + 48 * hour), later.map { it.expiresAt })
        assertEquals(2, later.size)
        assertEquals(listOf(photo.uri), MediaClockRules.cancelled(later, video.uri).map { it.uri })
    }

    @Test fun theTrashKeepsWhatTheClockRemovesUnlessThereIsNoTrashAndPermanentIsOn() {
        assertEquals(ClockOutcome.TRASH, MediaClockRules.outcome(trashEnabled = true, deletePermanently = false))
        assertEquals(ClockOutcome.TRASH, MediaClockRules.outcome(trashEnabled = true, deletePermanently = true))
        assertEquals(ClockOutcome.TRASH, MediaClockRules.outcome(trashEnabled = false, deletePermanently = false))
        assertEquals(ClockOutcome.DELETE, MediaClockRules.outcome(trashEnabled = false, deletePermanently = true))
    }

    @Test fun theNextRunSkipsOverdueClocksOnlyWhenAsked() {
        val clocks = listOf(MediaClock(video.uri, 100L), MediaClock(photo.uri, 900L))
        assertEquals(listOf(clocks[0]), MediaClockRules.due(clocks, now = 500L))
        assertEquals(900L, MediaClockRules.nextRun(clocks, now = 500L, includeOverdue = false))
        assertEquals(100L, MediaClockRules.nextRun(clocks, now = 500L, includeOverdue = true))
        assertNull(MediaClockRules.nextRun(clocks.take(1), now = 500L, includeOverdue = false))
    }

    @Test fun timeLeftRoundsUpToHoursThenMinutes() {
        assertEquals(MediaClockRules.Remaining(48, true), MediaClockRules.remaining(48 * hour, 0L))
        assertEquals(MediaClockRules.Remaining(2, true), MediaClockRules.remaining(hour + 1, 0L))
        assertEquals(MediaClockRules.Remaining(1, true), MediaClockRules.remaining(hour, 0L))
        assertEquals(MediaClockRules.Remaining(35, false), MediaClockRules.remaining(35 * 60_000L, 0L))
        assertEquals(MediaClockRules.Remaining(1, false), MediaClockRules.remaining(1L, 0L))
        assertEquals(MediaClockRules.Remaining(0, false), MediaClockRules.remaining(0L, 10L))
    }

    @Test fun clocksSurviveStorage() {
        val clock = MediaClock("file:///storage/emulated/0/Pasta\tcom tab/x.mp4", 123L)
        assertEquals(clock, MediaClockRules.decode(MediaClockRules.encode(clock)))
        assertNull(MediaClockRules.decode("garbage"))
        assertNull(MediaClockRules.decode("12\t"))
    }
}
