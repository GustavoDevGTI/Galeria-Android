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
    @Test fun fileOperationShowsImmediateFeedbackAndNeverQueuesDuplicateClicks() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executed = AtomicInteger()
        lateinit var runner: ActivityOperationRunner
        try {
            ActivityScenario.launch(TestViewHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    runner = ActivityOperationRunner(activity)
                    runner.run({ executed.incrementAndGet(); release.await(10, TimeUnit.SECONDS) }) { finished.countDown() }
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
                    worker.execute { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
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
