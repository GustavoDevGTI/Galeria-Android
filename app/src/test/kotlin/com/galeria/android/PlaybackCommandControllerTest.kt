package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class PlaybackCommandControllerTest {
    private class Port : PlaybackCommandController.Port {
        var released = false
        private fun checkAlive() { check(!released) { "Access to released player" } }
        var at = 2_000L
        var length = 10_000L
        var playing = true
        var completed = false
        var exact = false
        var restores = 0
        val events = arrayListOf<String>()
        override val position get() = at.also { checkAlive() }
        override val duration get() = length.also { checkAlive() }
        override val isPlaying get() = playing.also { checkAlive() }
        override val playWhenReady get() = playing.also { checkAlive() }
        override val ended get() = completed.also { checkAlive() }
        override fun play() { checkAlive(); playing = true; events.add("play") }
        override fun pause() { checkAlive(); playing = false; events.add("pause") }
        override fun seek(position: Long) { checkAlive(); at = position; events.add("seek:$position:$exact") }
        override fun exactSeek(): () -> Unit {
            checkAlive(); val previous = exact; exact = true
            return { checkAlive(); exact = previous; restores++; events.add("restore") }
        }
    }
    private class Fixture {
        val jobs = linkedMapOf<Runnable, Long>()
        val authority = PlaybackCommandController(object : PlaybackCommandController.Scheduler {
            override fun post(work: Runnable, delayMs: Long) { jobs[work] = delayMs }
            override fun cancel(work: Runnable) { jobs.remove(work) }
        })
        val port = Port()
        val channel = authority.bind(port)
        fun fire() { val task = jobs.keys.first(); jobs.remove(task); task.run() }
    }

    @Test fun dragPausesOnceAndDuplicateBeginKeepsIdentity() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        assertSame(gesture, f.channel.beginScrub()); assertFalse(f.port.playing)
        assertEquals(listOf("pause"), f.port.events); assertTrue(f.port.exact)
        assertTrue(f.channel.desiredPlayback)
    }
    @Test fun movesCoalesceAtSixtyMillisecondsWithoutCreatingMoreWork() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(3_000); gesture.move(4_000); gesture.move(5_000)
        assertEquals(listOf(60L), f.jobs.values.toList()); assertEquals(2_000L, f.port.at)
        f.fire(); assertEquals(5_000L, f.port.at); assertTrue(f.jobs.isEmpty())
        gesture.move(6_000); assertEquals(1, f.jobs.size)
    }
    @Test fun finishingFlushesExactPositionBeforeRestoringOriginalMode() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(3_000); val stale = f.jobs.keys.single()
        gesture.finish(6_789, false); stale.run(); gesture.finish(8_000, false)
        assertEquals(listOf("pause", "seek:6789:true", "restore", "play"), f.port.events)
        assertEquals(1, f.port.restores); assertFalse(f.port.exact); assertTrue(f.jobs.isEmpty())
    }
    @Test fun pausedPreviewDoesNotAutoplayAndEndIsClampedOneMillisecondBeforeCompletion() {
        val f = Fixture(); f.port.playing = false
        f.channel.beginScrub()!!.finish(10_000, false)
        assertEquals(9_999L, f.port.at); assertFalse(f.port.playing)
    }
    @Test fun cancelReturnsToOriginalPositionWhileSeekIsStillExact() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(5_000); f.fire(); gesture.finish(7_000, true)
        assertEquals(2_000L, f.port.at); assertTrue(f.port.playing)
        assertTrue(f.port.events.contains("seek:2000:true")); assertEquals(1, f.port.restores)
    }
    @Test fun cancellationCanExplicitlySuppressResume() {
        val f = Fixture(); f.channel.beginScrub()!!.finish(5_000, true, allowResume = false)
        assertFalse(f.port.playing); assertEquals(2_000L, f.port.at)
    }
    @Test fun lifecyclePauseRevokesGestureBeforeCallbacksAndPreservesPlaybackIntent() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(5_000); val stale = f.jobs.keys.single()
        f.authority.suspend(); gesture.finish(8_000, false); stale.run()
        assertFalse(f.port.playing); assertFalse(gesture.active); assertEquals(2_000L, f.port.at)
        assertTrue(f.channel.desiredPlayback); assertTrue(f.jobs.isEmpty()); assertEquals(1, f.port.restores)
        f.authority.resume(true); assertTrue(f.port.playing)
    }
    @Test fun repeatedLifecyclePauseDoesNotOverwriteIntentWithTemporaryPause() {
        val f = Fixture(); f.authority.suspend(); f.authority.suspend(); f.authority.resume(true)
        assertTrue(f.port.playing); assertEquals(listOf("pause", "play"), f.port.events)
    }
    @Test fun lifecyclePauseOfPausedVideoDoesNotAutoplayOnReturn() {
        val f = Fixture(); f.port.playing = false; f.authority.suspend(); f.authority.resume(true)
        assertFalse(f.port.playing); assertEquals(listOf("pause"), f.port.events)
    }
    @Test fun editorAndMotionPhotoCanResumeUpdatesWithoutAutoplay() {
        val f = Fixture(); f.authority.suspend(); f.authority.resume(false)
        assertFalse(f.port.playing); assertTrue(f.channel.active)
        f.channel.play(); assertTrue(f.port.playing)
    }
    @Test fun backgroundRejectsPlayToggleSeekAndNewGesture() {
        val f = Fixture(); f.authority.suspend(); val previous = f.port.events.toList()
        f.channel.play(); f.channel.toggle(); f.channel.seek(9_000); f.channel.seekBy(100); f.channel.seekProgress(900)
        assertNull(f.channel.beginScrub()); assertEquals(previous, f.port.events)
    }
    @Test fun explicitPauseWhileBackgroundCancelsResumeIntent() {
        val f = Fixture(); f.authority.suspend(); f.channel.pause(); f.authority.resume(true)
        assertFalse(f.port.playing); assertFalse(f.channel.desiredPlayback)
    }
    @Test fun explicitPauseInvalidatesDragSoReleaseCannotResume() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(3_000); val stale = f.jobs.keys.single(); f.channel.pause()
        gesture.finish(8_000, false); stale.run()
        assertFalse(f.port.playing); assertEquals(2_000L, f.port.at); assertEquals(1, f.port.restores)
    }
    @Test fun playButtonWhilePlayingDragActsAsPauseNotAccidentalPlay() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!; f.channel.toggle()
        gesture.finish(8_000, false); assertFalse(f.port.playing)
    }
    @Test fun playButtonWhilePausedDragPlaysAndInvalidatesOldRelease() {
        val f = Fixture(); f.port.playing = false; val gesture = f.channel.beginScrub()!!
        f.channel.toggle(); gesture.finish(8_000, true)
        assertTrue(f.port.playing); assertEquals(2_000L, f.port.at)
    }
    @Test fun explicitSeekOverridesPendingDragAndOldCompletion() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(5_000); val stale = f.jobs.keys.single(); f.channel.seek(7_000)
        stale.run(); gesture.finish(8_000, false)
        assertEquals(7_000L, f.port.at); assertFalse(f.port.playing); assertFalse(f.port.exact)
    }
    @Test fun rebindExpiresChannelAndAllCommandsAvoidReadingReleasedPlayer() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!
        gesture.move(5_000); val stale = f.jobs.keys.single(); val replacement = Port()
        f.authority.bind(replacement); f.port.released = true
        f.channel.play(); f.channel.pause(); f.channel.toggle(); f.channel.seek(100)
        f.channel.seekBy(100); f.channel.seekProgress(100); assertNull(f.channel.beginScrub())
        assertFalse(f.channel.desiredPlayback); gesture.move(100); gesture.finish(100, false); stale.run()
        assertTrue(replacement.events.isEmpty()); assertEquals(1, f.port.restores)
    }
    @Test fun detachedSessionCannotBeRestartedByStaleResumeOrSeek() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!; gesture.move(5_000)
        val stale = f.jobs.keys.single(); f.authority.detach(); f.port.released = true
        stale.run(); gesture.finish(8_000, false); f.authority.resume(true)
        assertTrue(f.jobs.isEmpty()); assertFalse(f.channel.active)
    }
    @Test fun replacementBoundInBackgroundStaysPausedAndDoesNotInheritOldResume() {
        val f = Fixture(); f.authority.suspend(); val replacement = Port()
        val channel = f.authority.bind(replacement); assertFalse(replacement.playing)
        channel.play(); assertFalse(replacement.playing); f.authority.resume(true)
        assertFalse(replacement.playing)
    }
    @Test fun closingCancelsWorkOnceAndPermanentlyPreventsBinding() {
        val f = Fixture(); val gesture = f.channel.beginScrub()!!; gesture.move(5_000)
        val stale = f.jobs.keys.single(); f.authority.close(); f.authority.close(); f.port.released = true
        gesture.finish(100, false); stale.run(); f.authority.suspend(); f.authority.resume(true)
        assertEquals(1, f.port.restores); assertTrue(f.jobs.isEmpty())
        try { f.authority.bind(Port()); fail("Closed authority accepted a player") } catch (_: IllegalStateException) { }
    }
    @Test fun finishingRestoresAnAlreadyExactSeekModeRatherThanADefault() {
        val f = Fixture(); f.port.exact = true; f.channel.beginScrub()!!.finish(6_000, false)
        assertTrue(f.port.exact); assertEquals(1, f.port.restores)
    }
    @Test fun explicitSeekClampsKnownDurationAndKeepsUnknownDurationUsable() {
        val f = Fixture(); f.channel.seek(-100); assertEquals(0L, f.port.at)
        f.channel.seek(20_000); assertEquals(10_000L, f.port.at)
        f.port.length = -1L; f.channel.seek(20_000); assertEquals(20_000L, f.port.at)
    }
    @Test fun skipAndProgressKeepExistingScaleAndBounds() {
        val f = Fixture(); f.channel.seekBy(-5_000); assertEquals(0L, f.port.at)
        f.channel.seekBy(20_000); assertEquals(10_000L, f.port.at)
        f.channel.seekProgress(250); assertEquals(2_500L, f.port.at)
        f.channel.seekProgress(2000); assertEquals(10_000L, f.port.at)
        f.port.length = -1; f.channel.seekProgress(100); assertEquals(10_000L, f.port.at)
    }
    @Test fun playRestartsEndedVideoButDoesNotSeekOrdinaryPlayback() {
        val f = Fixture(); f.port.completed = true; f.channel.play()
        assertEquals(listOf("seek:0:false", "play"), f.port.events)
        f.port.events.clear(); f.port.completed = false; f.channel.play()
        assertEquals(listOf("play"), f.port.events)
    }
}
