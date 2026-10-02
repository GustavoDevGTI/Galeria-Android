package com.galeria.android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ViewerMotionPhotoControllerTest {
    private val a = ViewerMotionPhotoController.Source("photo:a", "a:1|100")
    private val b = ViewerMotionPhotoController.Source("photo:b", "b:1|200")
    private val clip = MotionPhotoClip(100, 200)
    private class Fixture {
        val owner = Thread.currentThread()
        var source: ViewerMotionPhotoController.Source? = null
        var live = true
        data class Call(val relevant: () -> Boolean, val complete: (MotionPhotoClip?) -> Unit)
        val calls = arrayListOf<Call>()
        val changes = arrayListOf<MotionPhotoClip?>()
        val controller = ViewerMotionPhotoController(
            object : ViewerMotionPhotoController.Detector {
                override fun detect(source: ViewerMotionPhotoController.Source, isRelevant: () -> Boolean,
                    complete: (MotionPhotoClip?) -> Unit) { calls.add(Call(isRelevant, complete)) }
            },
            { check(Thread.currentThread() == owner); source }, { live }, { changes.add(it) }
        )
        fun select(value: ViewerMotionPhotoController.Source?) { source = value; controller.select(value) }
    }
    @Test fun detectionShowsCurrentClipAndReusesCache() {
        val f = Fixture(); f.select(a); f.calls[0].complete(clip)
        assertEquals(clip, f.controller.clipFor(a)); f.select(a)
        assertEquals(1, f.calls.size); assertEquals(clip, f.changes.last())
    }
    @Test fun noClipIsCachedRatherThanRepeatedlyScanningAnOrdinaryPhoto() {
        val f = Fixture(); f.select(a); f.calls[0].complete(null); f.select(a)
        assertEquals(1, f.calls.size); assertNull(f.controller.clipFor(a))
    }
    @Test fun navigationInvalidatesOldScanAndOldResultDoesNotPopulateCache() {
        val f = Fixture(); f.select(a); f.select(b); f.calls[0].complete(clip)
        assertNull(f.controller.clipFor(b)); assertNull(f.changes.last())
        f.select(a); assertEquals(3, f.calls.size); assertTrue(f.calls[2].relevant())
    }
    @Test fun returningToSamePhotoDoesNotReviveItsFirstRequest() {
        val f = Fixture(); f.select(a); f.select(b); f.select(a); f.calls[0].complete(clip)
        assertNull(f.controller.clipFor(a)); f.calls[2].complete(clip); assertEquals(clip, f.controller.clipFor(a))
    }
    @Test fun revisedContentRejectsOldResultEvenBeforeNextSelect() {
        val f = Fixture(); f.select(a); f.source = a.copy(key = "a:2|100"); f.calls[0].complete(clip)
        assertFalse(f.calls[0].relevant()); assertNull(f.controller.clipFor(f.source))
        f.select(f.source); assertEquals(2, f.calls.size)
    }
    @Test fun unsupportedMediaClearsActionAndCancelsPhotoWork() {
        val f = Fixture(); f.select(a); f.select(null); f.calls[0].complete(clip)
        assertFalse(f.calls[0].relevant()); assertNull(f.controller.clipFor(a)); assertNull(f.changes.last())
    }
    @Test fun pauseBlocksCompletionAndResumeCanRetry() {
        val f = Fixture(); f.select(a); f.controller.pause(); f.calls[0].complete(clip)
        assertNull(f.controller.clipFor(a)); f.select(a); assertEquals(1, f.calls.size)
        f.controller.resume(); f.select(a); f.calls[1].complete(clip); assertEquals(clip, f.controller.clipFor(a))
    }
    @Test fun closedControllerCannotRestartOrShowLateClip() {
        val f = Fixture(); f.select(a); f.controller.close(); f.controller.resume()
        f.calls[0].complete(clip); f.select(a); assertEquals(1, f.calls.size); assertNull(f.controller.clipFor(a))
    }
    @Test fun inactiveHostDoesNotReceiveResultsOrOpenClip() {
        val f = Fixture(); f.select(a); f.live = false; f.calls[0].complete(clip)
        assertNull(f.controller.clipFor(a)); assertNull(f.changes.last())
    }
    @Test fun duplicateCompletionCannotReplaceAcceptedClip() {
        val f = Fixture(); f.select(a); f.calls[0].complete(clip); f.calls[0].complete(null)
        assertEquals(clip, f.controller.clipFor(a)); assertEquals(listOf(null, clip), f.changes)
    }
    @Test fun workerRelevanceDoesNotReadQueueOutsideMainThread() {
        val f = Fixture(); f.select(a)
        assertTrue(CompletableFuture.supplyAsync { f.calls[0].relevant() }.get(1, TimeUnit.SECONDS))
        f.controller.pause()
        assertFalse(CompletableFuture.supplyAsync { f.calls[0].relevant() }.get(1, TimeUnit.SECONDS))
    }
}
