package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class ViewerCinemaControllerTest {
    private class Fixture {
        var orientation = 7
        val jobs = linkedMapOf<Runnable, Long>()
        val events = arrayListOf<String>()
        var onMode: (() -> Unit)? = null
        var onStart: (() -> Unit)? = null
        val controller = ViewerCinemaController(
            object : ViewerCinemaController.Scheduler {
                override fun post(work: Runnable, delayMs: Long) { jobs[work] = delayMs }
                override fun cancel(work: Runnable) { jobs.remove(work) }
            }, { orientation }, 6,
            object : ViewerCinemaController.Effects {
                override fun modeChanged(enabled: Boolean) { events.add("mode:$enabled"); onMode?.invoke() }
                override fun orientationChanged(orientation: Int) { events.add("orientation:$orientation"); this@Fixture.orientation = orientation }
                override fun barsChanged(enabled: Boolean) { events.add("bars:$enabled") }
                override fun transitionStarted(enabled: Boolean) { events.add("start:$enabled"); onStart?.invoke() }
                override fun transitionFinished() { events.add("finish") }
            }
        )
        fun fire(delay: Long) { val work = jobs.entries.first { it.value == delay }.key; jobs.remove(work); work.run() }
        fun toggle() = controller.toggle(isVideo = true, mediaTransitionBusy = false)
    }

    @Test fun albumModeKeepsOriginalOrientationAndDoesNotAnimateEntry() {
        val f = Fixture(); f.controller.open(true, true)
        assertTrue(f.controller.enabled); assertEquals(7, f.controller.previousOrientation)
        assertEquals(listOf("orientation:6", "bars:true"), f.events); assertTrue(f.jobs.isEmpty())
        f.controller.open(false)
        assertFalse(f.controller.enabled); assertNull(f.controller.previousOrientation)
        assertEquals(7, f.orientation)
    }
    @Test fun savedModeOverridesAlbumOnlyForFirstMedia() {
        val f = Fixture(); f.controller.restore(false, 7); f.controller.open(true, true)
        assertFalse(f.controller.enabled); assertEquals(7, f.orientation)
        f.controller.open(true, true); assertTrue(f.controller.enabled)
    }
    @Test fun savedActiveModeRestoresTheOrientationBeforeCinema() {
        val f = Fixture(); f.orientation = 6; f.controller.restore(true, 7); f.controller.open(true, false)
        assertTrue(f.controller.enabled); assertEquals(7, f.controller.previousOrientation)
        f.controller.open(false); assertEquals(7, f.orientation)
    }
    @Test fun imageDiscardsRestoredModeWithoutEnablingCinema() {
        val f = Fixture(); f.controller.restore(true, 7); f.controller.open(false)
        assertFalse(f.controller.enabled); f.controller.open(true, false); assertFalse(f.controller.enabled)
    }
    @Test fun toggleRetainsRotationDelayAndFallbackAndRejectsRepeatedClick() {
        val f = Fixture(); assertTrue(f.toggle()); assertTrue(f.controller.enabled)
        assertEquals(listOf("mode:true", "start:true"), f.events)
        assertEquals(listOf(170L, 520L), f.jobs.values.toList()); assertFalse(f.toggle())
        f.fire(170L); assertEquals(6, f.orientation); assertTrue(f.controller.transitioning)
        f.fire(520L); assertFalse(f.controller.transitioning)
        assertEquals(1, f.events.count { it == "finish" })
    }
    @Test fun unsupportedMediaAndBusyGalleryDoNotChangeStateOrEffects() {
        val f = Fixture()
        assertFalse(f.controller.toggle(false, false)); assertFalse(f.controller.toggle(true, true))
        assertFalse(f.controller.enabled); assertTrue(f.events.isEmpty()); assertTrue(f.jobs.isEmpty())
    }
    @Test fun settleBeforeRotationAppliesOrientationAndCompletesExactlyOnce() {
        val f = Fixture(); f.toggle(); val old = f.jobs.keys.toList()
        f.controller.settle(); f.controller.settle(); old.forEach { it.run() }
        assertEquals(6, f.orientation); assertTrue(f.jobs.isEmpty())
        assertEquals(1, f.events.count { it == "finish" }); assertFalse(f.controller.transitioning)
    }
    @Test fun obsoleteCallbacksCannotRotateOrFinishNewTransition() {
        val f = Fixture(); f.toggle(); val old = f.jobs.keys.toList(); f.controller.settle()
        f.toggle(); val before = f.events.toList(); old.forEach { it.run() }
        assertEquals(before, f.events); assertTrue(f.controller.transitioning)
        f.fire(170L); assertEquals(7, f.orientation); f.fire(520L)
        assertFalse(f.controller.enabled); assertNull(f.controller.previousOrientation)
    }
    @Test fun switchingToImageSettlesModeAndRestoresOrientation() {
        val f = Fixture(); f.toggle(); f.controller.open(false)
        assertFalse(f.controller.enabled); assertFalse(f.controller.transitioning)
        assertEquals(7, f.orientation); assertTrue(f.jobs.isEmpty())
    }
    @Test fun closeCancelsWithoutUiEffectsAndOldCallbacksCannotReviveSession() {
        val f = Fixture(); f.toggle(); val old = f.jobs.keys.toList(); val before = f.events.toList()
        f.controller.close(); f.controller.close(); old.forEach { it.run() }
        f.controller.open(true, true); f.controller.restore(false, 9); f.controller.refreshBars()
        assertFalse(f.toggle()); assertEquals(before, f.events); assertTrue(f.jobs.isEmpty())
    }
    @Test fun focusRefreshOnlyReassertsBarsWithoutChangingModeOrOrientation() {
        val f = Fixture(); f.controller.open(true, true); val before = f.events.toList()
        f.controller.refreshBars(); assertEquals(before + "bars:true", f.events)
        assertEquals(7, f.controller.previousOrientation); assertTrue(f.jobs.isEmpty())
    }
    @Test fun modeEffectCannotReenterToggleBeforeReservation() {
        val f = Fixture(); f.onMode = { assertFalse(f.toggle()) }
        assertTrue(f.toggle()); assertEquals(2, f.jobs.size)
    }
    @Test fun closingInAnimationEffectCannotLeaveNewTimers() {
        val f = Fixture(); f.onStart = { f.controller.close() }; f.toggle()
        assertTrue(f.jobs.isEmpty()); assertFalse(f.controller.transitioning)
    }
}
