package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class MediaTransitionControllerTest {
    private class Scheduler : MediaTransitionController.Scheduler {
        val callbacks = linkedMapOf<Runnable, Long>()
        override fun schedule(completion: Runnable, delayMs: Long) { callbacks[completion] = delayMs }
        override fun cancel(completion: Runnable) { callbacks.remove(completion) }
        fun fire() { callbacks.keys.first().run() }
    }

    @Test fun reservationBlocksAnotherMediaChangeUntilSettled() {
        val controller = MediaTransitionController(Scheduler())
        assertEquals(MediaTransitionController.State.IDLE, controller.state)
        assertFalse(controller.isBusy)
        assertTrue(controller.begin())
        assertEquals(MediaTransitionController.State.RESERVED, controller.state)
        assertTrue(controller.isBusy)
        assertFalse(controller.begin())
        controller.register(165, cleanup = {})
        assertEquals(MediaTransitionController.State.RUNNING, controller.state)
        assertFalse(controller.begin())
        controller.finish()
        assertFalse(controller.isBusy)
        assertTrue(controller.begin())
    }

    @Test fun animationCompletionReleasesResourcesExactlyOnce() {
        val scheduler = Scheduler()
        val controller = MediaTransitionController(scheduler)
        var releases = 0
        var updates = 0
        controller.begin()
        val animationEnd = controller.register(165, cleanup = { update -> releases++; if (update) updates++ })
        assertEquals(265L, scheduler.callbacks[animationEnd])
        animationEnd.run()
        animationEnd.run()
        controller.finish()
        assertEquals(1, releases)
        assertEquals(1, updates)
        assertTrue(scheduler.callbacks.isEmpty())
        assertNull(controller.pendingCompletion)
        assertFalse(controller.isBusy)
    }

    @Test fun interruptedAnimationIsSettledByBoundedFallback() {
        val scheduler = Scheduler()
        val controller = MediaTransitionController(scheduler)
        var updates = 0
        controller.begin()
        controller.register(245, cleanup = { if (it) updates++ })
        assertEquals(345L, scheduler.callbacks.values.single())
        // No animation callback: advancing the fake scheduler is sufficient.
        scheduler.fire()
        assertEquals(1, updates)
        assertFalse(controller.isBusy)
    }

    @Test fun pauseSettlesDestinationEvenWithoutAnimationOrFallback() {
        val scheduler = Scheduler()
        val controller = MediaTransitionController(scheduler)
        val results = arrayListOf<Boolean>()
        controller.begin()
        val callback = controller.register(165, cleanup = results::add)
        scheduler.cancel(callback)
        controller.finish()
        callback.run()
        assertEquals(listOf(true), results)
        assertFalse(controller.isBusy)
    }

    @Test fun reloadOrDestructionReleasesWithoutRebuildingUi() {
        val scheduler = Scheduler()
        val controller = MediaTransitionController(scheduler)
        val results = arrayListOf<Boolean>()
        controller.begin()
        val callback = controller.register(165, cleanup = results::add)
        controller.finish(updateUi = false)
        controller.finish()
        callback.run()
        assertEquals(listOf(false), results)
        assertTrue(scheduler.callbacks.isEmpty())
    }

    @Test fun staleCallbackCannotFinishANewerMediaChange() {
        val controller = MediaTransitionController(Scheduler())
        var first = 0
        var second = 0
        controller.begin()
        val old = controller.register(165, cleanup = { first++ })
        controller.finish()
        controller.begin()
        val current = controller.register(245, cleanup = { second++ })
        old.run()
        assertTrue(controller.isBusy)
        assertSame(current, controller.pendingCompletion)
        assertEquals(0, second)
        current.run()
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test fun cleanupCannotStartAnotherChangeOrReenterItself() {
        val controller = MediaTransitionController(Scheduler())
        var releases = 0
        controller.begin()
        lateinit var animationEnd: Runnable
        animationEnd = controller.register(165, cleanup = {
            releases++
            assertEquals(MediaTransitionController.State.COMPLETING, controller.state)
            assertTrue(controller.isBusy)
            assertFalse(controller.begin())
            controller.finish()
            animationEnd.run()
        })
        animationEnd.run()
        assertEquals(1, releases)
        assertFalse(controller.isBusy)
    }

    @Test fun settledCallbackSeesUnlockedStateAndCanScheduleAutomaticAdvance() {
        val controller = MediaTransitionController(Scheduler())
        var scheduled = false
        controller.begin()
        controller.register(165, cleanup = { assertTrue(controller.isBusy) }, onSettled = {
            assertFalse(controller.isBusy)
            scheduled = it
            assertTrue(controller.begin())
        }).run()
        assertTrue(scheduled)
        assertEquals(MediaTransitionController.State.RESERVED, controller.state)
    }

    @Test fun cleanupFailureStillUnlocksAndDoesNotRunSettledEffects() {
        val controller = MediaTransitionController(Scheduler())
        var settled = false
        controller.begin()
        val failure = IllegalStateException("cleanup failed")
        val callback = controller.register(165, cleanup = { throw failure }, onSettled = { settled = true })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { callback.run() })
        assertFalse(controller.isBusy)
        assertNull(controller.pendingCompletion)
        assertFalse(settled)
        callback.run()
        assertTrue(controller.begin())
    }

    @Test fun finishBeforeRegistrationReleasesReservation() {
        val controller = MediaTransitionController(Scheduler())
        controller.finish()
        controller.begin()
        controller.finish(updateUi = false)
        assertFalse(controller.isBusy)
        assertTrue(controller.begin())
    }

    @Test fun registrationRequiresReservationAndRejectsReplacement() {
        val controller = MediaTransitionController(Scheduler())
        assertThrows(IllegalStateException::class.java) { controller.register(165, cleanup = {}) }
        controller.begin()
        val original = controller.register(165, cleanup = {})
        assertThrows(IllegalStateException::class.java) { controller.register(165, cleanup = {}) }
        assertSame(original, controller.pendingCompletion)
        original.run()
        assertFalse(controller.isBusy)
    }
}
