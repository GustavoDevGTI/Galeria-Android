package com.galeria.android

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.hamcrest.Matchers.equalTo

@RunWith(AndroidJUnit4::class)
class ActivityLoadingFeedbackInstrumentedTest {
    @Test fun imageLoadingIndicatorRemainsVisibleOverLightAndDarkPhotos() {
        ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
            lateinit var indicator: LoadingIndicatorView
            lateinit var container: FrameLayout
            scenario.onActivity { activity ->
                indicator = LoadingIndicatorView.overImage(activity, activity.getString(R.string.ocr_processing))
                container = FrameLayout(activity).apply {
                    addView(indicator, FrameLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48), android.view.Gravity.CENTER))
                }
                activity.setContentView(container)
            }
            onView(withContentDescription(R.string.ocr_processing)).check(matches(isDisplayed()))
            scenario.onActivity {
                assertFalse("O indicador não deve capturar o toque na foto", indicator.isClickable)
                assertTrue(indicator.drawable is androidx.swiperefreshlayout.widget.CircularProgressDrawable)
                assertEquals(Ui.dp(indicator.context, 8), indicator.paddingLeft)
                for (photoColor in listOf(android.graphics.Color.WHITE, android.graphics.Color.BLACK)) {
                    container.setBackgroundColor(photoColor)
                    val rendered = android.graphics.Bitmap.createBitmap(indicator.width, indicator.height, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = android.graphics.Canvas(rendered)
                        canvas.drawColor(photoColor)
                        // Sample the empty center of the surface, independent of spinner phase.
                        indicator.background.setBounds(0, 0, indicator.width, indicator.height)
                        indicator.background.draw(canvas)
                        val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
                        assertTrue("O ícone branco precisa de fundo contrastante", androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, center) > 7.0)
                        assertEquals("A foto não deve ser coberta fora do círculo", photoColor, rendered.getPixel(0, 0))
                    } finally { rendered.recycle() }
                }
            }
            captureLoadingFeedback("ocr-loading-contrast-qa.png")
        }
    }

    @Test fun finishingAfterAndroidRemovesTheLoadingWindowIsSafeAndAllowsAnotherRequest() {
        ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
            lateinit var indicator: ActivityLoadingIndicator
            scenario.onActivity { activity ->
                indicator = ActivityLoadingIndicator(activity)
                assertTrue(indicator.begin(activity.getString(R.string.operation_in_progress)))
                assertTrue(indicator.busy)
            }
            assertIconOnlyFeedback()
            scenario.onActivity { activity ->
                val dialog = ActivityLoadingIndicator::class.java.getDeclaredField("dialog")
                    .apply { isAccessible = true }.get(indicator) as android.app.AlertDialog
                val decor = requireNotNull(dialog.window).decorView
                assertTrue(decor.isAttachedToWindow)
                // Reproduce the real failure: Android removes the window before
                // the asynchronous completion asks Dialog to dismiss it.
                activity.windowManager.removeViewImmediate(decor)
                assertFalse(decor.isAttachedToWindow)
                indicator.finish()
                assertFalse(indicator.busy)
                indicator.finish()
                assertTrue("Uma nova operação não pode ficar bloqueada", indicator.begin(activity.getString(R.string.operation_in_progress)))
                val replacement = ActivityLoadingIndicator::class.java.getDeclaredField("dialog")
                    .apply { isAccessible = true }.get(indicator) as android.app.AlertDialog
                // A fast completion may precede the first rendering traversal;
                // it must dismiss that newly added window too.
                indicator.finish()
                assertFalse(indicator.busy)
                assertFalse(replacement.isShowing)
            }
        }
    }

    @Test fun fileOperationShowsImmediateFeedbackAndNeverQueuesDuplicateClicks() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executed = AtomicInteger()
        lateinit var runner: ActivityOperationRunner
        try {
            ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    runner = ActivityOperationRunner(activity)
                    // Hold until the assertions release us; a slow emulator must
                    // not turn the simulated pending operation into a completed one.
                    // The finally block always releases the worker, even on failure.
                    runner.run({ executed.incrementAndGet(); release.await() }) { finished.countDown() }
                    repeat(5) { runner.run({ executed.incrementAndGet() }) { fail("Pedido duplicado executou") } }
                    assertTrue(runner.busy)
                }
                assertIconOnlyFeedback()
                onView(withContentDescription(R.string.operation_in_progress)).inRoot(isDialog()).check(matches(isDisplayed()))
                captureLoadingFeedback("loading-operation-qa.png")
                release.countDown()
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                scenario.onActivity { assertFalse(runner.busy); assertEquals(1, executed.get()); runner.close() }
            }
        } finally { release.countDown() }
    }

    @Test fun albumTargetsShowFeedbackAndDuplicateRequestsDeliverOnlyOnce() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val delivered = AtomicInteger()
        lateinit var actions: DetailMediaActions
        try {
            ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    actions = DetailMediaActions(activity, activity.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE))
                    val worker = DetailMediaActions::class.java.getDeclaredField("executor").apply { isAccessible = true }
                        .get(actions) as java.util.concurrent.ExecutorService
                    worker.execute { entered.countDown(); release.await() }
                    repeat(5) {
                        actions.loadTargets(null, emptySet(), emptySet(), false) { delivered.incrementAndGet(); finished.countDown() }
                    }
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertIconOnlyFeedback()
                onView(withContentDescription(R.string.loading_album_targets)).inRoot(isDialog()).check(matches(isDisplayed()))
                release.countDown()
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                scenario.onActivity { assertEquals(1, delivered.get()); actions.close() }
            }
        } finally { release.countDown() }
    }

    @Test fun refreshIndicatorStopsWhenHiddenOrDetachedAndRestartsWhenShown() {
        ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
            lateinit var indicator: LoadingIndicatorView
            lateinit var container: FrameLayout
            scenario.onActivity { activity ->
                indicator = LoadingIndicatorView(activity)
                container = FrameLayout(activity).apply { addView(indicator, FrameLayout.LayoutParams(48, 48)) }
                activity.setContentView(container)
            }
            scenario.onActivity {
                val animationsEnabled = android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
                assertEquals(animationsEnabled, indicator.isAnimating)
                assertTrue(indicator.drawable is androidx.swiperefreshlayout.widget.CircularProgressDrawable)
                container.visibility = View.GONE
                assertFalse(indicator.isAnimating)
                container.visibility = View.VISIBLE
                assertEquals(animationsEnabled, indicator.isAnimating)
                container.removeView(indicator)
                assertFalse(indicator.isAnimating)
            }
        }
    }

    private fun assertIconOnlyFeedback() {
        onView(withTagValue(equalTo("activity_loading_indicator" as Any))).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            val body = view as android.view.ViewGroup
            assertEquals(1, body.childCount)
            assertTrue(body.getChildAt(0) is LoadingIndicatorView)
            assertTrue(body.getChildAt(0).isShown)
            assertTrue("O indicador não deve ocupar um painel largo", body.rootView.width <= Ui.dp(body.context, 100))
        }
    }
}
