package com.galeria.android

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Wait for the actual request (including its scheduled automatic start), not
 * repeated attempts to click a dialog or a guess at the decoder's speed. No
 * test hook or scheduler change is added to the production controller. */
internal fun awaitViewerOcrIdle(scenario: ActivityScenario<DetailActivity>) {
    val getter = DetailActivity::class.java.getDeclaredMethod("getTextRecognitionController").apply { isAccessible = true }
    val active = ViewerTextRecognitionController::class.java.getDeclaredField("active").apply { isAccessible = true }
    val pending = ViewerTextRecognitionController::class.java.getDeclaredField("pending").apply { isAccessible = true }
    val deadline = SystemClock.uptimeMillis() + 60_000L
    do {
        var idle = false
        scenario.onActivity { activity ->
            val controller = getter.invoke(activity)
            idle = active.get(controller) == null && pending.get(controller) == null
        }
        if (idle) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return
        }
        SystemClock.sleep(50L)
    } while (SystemClock.uptimeMillis() < deadline)
    throw AssertionError("O pedido real de OCR não concluiu em 60 s; não é uma aprovação por timeout.")
}

/** Barrier behind queued stale requests; does not run another blank-image OCR. */
internal fun awaitOcrWorkerIdle() {
    val worker = ImageTextRecognition::class.java.getDeclaredField("decodeExecutor")
        .apply { isAccessible = true }.get(null) as ExecutorService
    val drained = CountDownLatch(1)
    worker.execute { drained.countDown() }
    check(drained.await(60, TimeUnit.SECONDS)) { "A fila de OCR não terminou em 60 s." }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}
