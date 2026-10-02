package com.galeria.android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ViewerTextRecognitionControllerTest {
    private val a = ViewerTextRecognitionController.Image("image:a", "a:revision1|100|20")
    private val b = ViewerTextRecognitionController.Image("image:b", "b:revision1|100|20")
    private val c = ViewerTextRecognitionController.Image("image:c", "c:revision1|100|20")

    private class Fixture(capacity: Int = 16) {
        val owner = Thread.currentThread()
        var image: ViewerTextRecognitionController.Image? = null
        var live = true
        var started = 0
        var onStart: (() -> Unit)? = null
        val availability = arrayListOf<Boolean>()
        val manualResults = arrayListOf<Pair<Result<String>, Boolean>>()
        val pending = linkedMapOf<Runnable, Long>()
        data class Call(val image: ViewerTextRecognitionController.Image, val detailed: Boolean,
            val relevant: () -> Boolean, val complete: (Result<String>) -> Unit)
        val calls = arrayListOf<Call>()
        val controller = ViewerTextRecognitionController(
            object : ViewerTextRecognitionController.Scheduler {
                override fun post(work: Runnable, delayMs: Long) { pending[work] = delayMs }
                override fun cancel(work: Runnable) { pending.remove(work) }
            },
            object : ViewerTextRecognitionController.Recognizer {
                override fun recognize(image: ViewerTextRecognitionController.Image, detailed: Boolean,
                    isRelevant: () -> Boolean, complete: (Result<String>) -> Unit) {
                    calls.add(Call(image, detailed, isRelevant, complete))
                }
            },
            currentImage = { check(Thread.currentThread() == owner) { "Queue read outside main thread" }; image },
            hostActive = { live },
            listener = object : ViewerTextRecognitionController.Listener {
                override fun availabilityChanged(available: Boolean) { availability.add(available) }
                override fun manualStarted() { started++; onStart?.invoke() }
                override fun manualCompleted(result: Result<String>, cached: Boolean) { manualResults.add(result to cached) }
            },
            cacheCapacity = capacity
        )
        fun fire() { val task = pending.keys.first(); pending.remove(task); task.run() }
        fun auto(source: ViewerTextRecognitionController.Image) { image = source; controller.detect(source); fire() }
        fun manual(source: ViewerTextRecognitionController.Image) { image = source; controller.requestManual(source) }
        // Deliberately ignore engine relevance: the controller must reject old
        // callbacks itself, rather than trusting cancellation in the backend.
        fun succeed(text: String, index: Int = calls.lastIndex) { calls[index].complete(Result.success(text)) }
    }

    @Test fun automaticDetectionKeeps650msDelayAndLightReading() {
        val f = Fixture()
        f.image = a
        f.controller.detect(a)
        assertEquals(listOf(false), f.availability)
        assertEquals(650L, f.pending.values.single())
        assertTrue(f.calls.isEmpty())
        f.fire()
        assertEquals(a, f.calls.single().image)
        assertFalse(f.calls.single().detailed)
        f.succeed("texto")
        assertEquals(listOf(false, true), f.availability)
        assertTrue(f.manualResults.isEmpty())
    }

    @Test fun cancelledTimerCannotStartOrClearANewerDetection() {
        val f = Fixture()
        f.image = a
        f.controller.detect(a)
        val oldTimer = f.pending.keys.single()
        f.image = b
        f.controller.detect(b)
        oldTimer.run()
        assertTrue(f.calls.isEmpty())
        assertEquals(1, f.pending.size)
        f.fire()
        assertEquals(b, f.calls.single().image)
    }

    @Test fun imageChangeIsCheckedAtDeliveryEvenBeforeNextDetectionIsScheduled() {
        val f = Fixture()
        f.auto(a)
        f.image = b
        f.succeed("texto da foto anterior")
        assertEquals(listOf(false), f.availability)
        f.auto(a)
        assertEquals(2, f.calls.size) // Rejected result did not poison the cache.
    }

    @Test fun manualSupersedesAutomaticAndUsesDetailedReading() {
        val f = Fixture()
        f.auto(a)
        f.manual(a)
        assertFalse(f.calls[0].relevant())
        assertTrue(f.calls[1].relevant())
        assertTrue(f.calls[1].detailed)
        f.succeed("automatic obsolete", 0)
        assertTrue(f.manualResults.isEmpty())
        f.succeed("documento completo", 1)
        assertEquals("documento completo", f.manualResults.single().first.getOrThrow())
        assertFalse(f.manualResults.single().second)
    }

    @Test fun staleManualCallbackCannotClearTheNewRequestForTheSameImage() {
        val f = Fixture()
        f.manual(a)
        f.controller.detect(null)
        f.manual(a)
        f.succeed("antigo", 0)
        assertTrue(f.calls[1].relevant())
        f.manual(a)
        assertEquals(2, f.calls.size) // The current manual request remains deduplicated.
        f.succeed("novo", 1)
        assertEquals("novo", f.manualResults.single().first.getOrThrow())
    }

    @Test fun returningToTheSameImageDoesNotReviveItsOldAutomaticRequest() {
        val f = Fixture()
        f.auto(a)
        f.auto(b)
        f.auto(a)
        f.succeed("obsolete A", 0)
        f.succeed("obsolete B", 1)
        assertEquals(listOf(false, false, false), f.availability)
        f.succeed("current A", 2)
        assertTrue(f.availability.last())
    }

    @Test fun duplicateCompletionIsDeliveredOnlyOnce() {
        val f = Fixture()
        f.manual(a)
        f.succeed("texto")
        f.succeed("duplicado")
        f.calls[0].complete(Result.failure(IllegalStateException("late failure")))
        assertEquals(1, f.manualResults.size)
        assertEquals("texto", f.manualResults[0].first.getOrThrow())
    }

    @Test fun pauseInvalidatesWorkAndResumeAllowsRetryWithoutStuckManualKey() {
        val f = Fixture()
        f.manual(a)
        f.controller.pause()
        assertFalse(f.calls[0].relevant())
        f.succeed("paused")
        f.manual(a)
        assertEquals(1, f.calls.size)
        assertTrue(f.manualResults.isEmpty())
        f.controller.resume()
        f.manual(a)
        assertEquals(2, f.calls.size)
        f.succeed("retomado")
        assertEquals("retomado", f.manualResults.single().first.getOrThrow())
    }

    @Test fun closeIsPermanentAndCancelsPendingAutomaticWork() {
        val f = Fixture()
        f.image = a
        f.controller.detect(a)
        val old = f.pending.keys.single()
        f.controller.close()
        assertTrue(f.pending.isEmpty())
        f.controller.resume()
        old.run()
        f.controller.detect(a)
        f.manual(a)
        assertTrue(f.calls.isEmpty())
        assertEquals(listOf(false), f.availability)
    }

    @Test fun closedOrInactiveHostCannotReceiveLateManualResults() {
        val f = Fixture()
        f.manual(a)
        f.live = false
        assertFalse(f.calls[0].relevant())
        f.succeed("finishing")
        assertTrue(f.manualResults.isEmpty())
        f.live = true
        f.controller.close()
        f.succeed("destroyed")
        assertTrue(f.manualResults.isEmpty())
    }

    @Test fun contentRevisionChangeRejectsSameUriResultAndInvalidatesCache() {
        val f = Fixture()
        f.manual(a)
        val edited = a.copy(key = "a:revision2|100|20")
        f.image = edited
        f.succeed("before edit")
        assertTrue(f.manualResults.isEmpty())
        f.manual(edited)
        f.succeed("after edit")
        f.manual(a)
        assertEquals(3, f.calls.size)
    }

    @Test fun pendingDetectionDoesNotDecodeAnImageThatIsNoLongerCurrent() {
        val f = Fixture()
        f.image = a
        f.controller.detect(a)
        f.image = b
        f.fire()
        assertTrue(f.calls.isEmpty())
        f.manual(b)
        assertEquals(b, f.calls.single().image)
    }

    @Test fun blankAutomaticResultIsCachedAndDoesNotShowTextIcon() {
        val f = Fixture()
        f.auto(a)
        f.succeed(" ")
        f.controller.detect(a)
        assertFalse(f.availability.last())
        assertTrue(f.pending.isEmpty())
        assertEquals(1, f.calls.size)
    }

    @Test fun detailedNonblankResultSeedsBothCachesWithoutNewProcessingToast() {
        val f = Fixture()
        f.manual(a)
        f.succeed("texto completo")
        f.controller.detect(a)
        assertTrue(f.availability.last())
        assertTrue(f.pending.isEmpty())
        f.manual(a)
        assertEquals(1, f.calls.size)
        assertEquals(1, f.started)
        assertTrue(f.manualResults.last().second)
    }

    @Test fun automaticTextDoesNotReplaceDetailedRecognition() {
        val f = Fixture()
        f.auto(a)
        f.succeed("prévia")
        f.manual(a)
        assertEquals(2, f.calls.size)
        assertTrue(f.calls[1].detailed)
    }

    @Test fun blankDetailedResultAllowsRetryButRemainsCachedForDetection() {
        val f = Fixture()
        f.manual(a)
        f.succeed("")
        f.controller.detect(a)
        assertTrue(f.pending.isEmpty())
        assertFalse(f.availability.last())
        f.manual(a)
        assertEquals(2, f.calls.size)
    }

    @Test fun failuresAreNotCachedAutomaticStaysSilentAndManualReportsError() {
        val f = Fixture()
        val error = IllegalStateException("decode failed")
        f.auto(a)
        f.calls[0].complete(Result.failure(error))
        assertTrue(f.manualResults.isEmpty())
        assertEquals(listOf(false), f.availability)
        f.manual(a)
        f.calls[1].complete(Result.failure(error))
        assertSame(error, f.manualResults.single().first.exceptionOrNull())
        f.manual(a)
        assertEquals(3, f.calls.size)
    }

    @Test fun bothCachesRemainBoundedAndHaveIndependentLruAccess() {
        val f = Fixture(capacity = 2)
        f.manual(a); f.succeed("A")
        f.manual(b); f.succeed("B")
        f.manual(a) // Detailed cache access refreshes A, not the detection cache.
        assertTrue(f.manualResults.last().second)
        f.manual(c); f.succeed("C")
        f.image = b
        f.controller.detect(b) // Detection cache still has B.
        assertTrue(f.pending.isEmpty())
        f.manual(b) // Detailed cache evicted B.
        assertEquals(4, f.calls.size)
        f.image = a
        f.controller.detect(a) // Detection cache evicted A.
        assertEquals(1, f.pending.size)
    }

    @Test fun workerRelevanceDoesNotReadQueueOrCachesOffMainThread() {
        val f = Fixture()
        f.manual(a)
        assertTrue(CompletableFuture.supplyAsync { f.calls[0].relevant() }.get(1, TimeUnit.SECONDS))
        f.controller.pause()
        assertFalse(CompletableFuture.supplyAsync { f.calls[0].relevant() }.get(1, TimeUnit.SECONDS))
    }

    @Test fun manualStartEffectMayPauseWithoutStartingCancelledEngineWork() {
        val f = Fixture()
        f.onStart = { f.controller.pause() }
        f.manual(a)
        assertEquals(1, f.started)
        assertTrue(f.calls.isEmpty())
    }
}
