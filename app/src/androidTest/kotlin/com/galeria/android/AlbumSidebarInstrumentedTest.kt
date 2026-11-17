package com.galeria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AlbumSidebarInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    private val first = AlbumItem("Pictures/SidebarTestA-${System.nanoTime()}/", "Barra lateral A", 3, null, 20, 1, 1, "Pictures/SidebarTestA/")
    private val second = AlbumItem("Pictures/SidebarTestB-${System.nanoTime()}/", "Barra lateral B", 5, null, 10, 1, 1, "Pictures/SidebarTestB/")

    @Test
    fun swipingRightOpensTheAlbumsAndTappingOneOpensItInPlace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = AlbumSidebarSource.albums
        AlbumSidebarSource.albums = listOf(first, second)
        try {
            val intent = Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", first.key)
                putExtra("album_name", first.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<AlbumMediaActivity>(intent).use {
                onView(withHint("Pesquisar em ${first.name}")).check(matches(isDisplayed()))
                val grid = allOf(isAssignableFrom(RecyclerView::class.java), isDisplayed())

                // A vertical move scrolls the grid. (A drag from the left edge is the system back gesture,
                // which closes the screen before the app sees it; AlbumSidebarRulesTest covers that rule.)
                onView(grid).perform(drag(0.5f, 0.35f, 0.52f, 0.75f))
                onView(withTagValue(equalTo("album_sidebar"))).check(doesNotExist())

                onView(grid).perform(drag(0.25f, 0.5f, 0.85f, 0.52f))
                waitFor { onView(withTagValue(equalTo("album_sidebar"))).check(matches(isDisplayed())) }
                onView(withText(first.name)).check(matches(isDisplayed()))
                onView(withText("5")).check(matches(isDisplayed()))

                onView(withText(second.name)).perform(click())
                waitFor { onView(withHint("Pesquisar em ${second.name}")).check(matches(isDisplayed())) }
                onView(withTagValue(equalTo("album_sidebar"))).check(doesNotExist())
            }
        } finally {
            AlbumSidebarSource.albums = previous
        }
    }

    /** Finger drag between two points given as fractions of the view, slow enough to read as a swipe. */
    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription(): String = "arrastar de ($fromX, $fromY) até ($toX, $toY)"
        override fun perform(uiController: UiController, view: View) {
            val location = IntArray(2).also(view::getLocationOnScreen)
            fun x(f: Float) = location[0] + view.width * f
            fun y(f: Float) = location[1] + view.height * f
            val down = SystemClock.uptimeMillis()
            var event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x(fromX), y(fromY), 0)
            uiController.injectMotionEvent(event)
            event.recycle()
            for (step in 1..10) {
                uiController.loopMainThreadForAtLeast(16L)
                val time = SystemClock.uptimeMillis()
                event = MotionEvent.obtain(down, time, MotionEvent.ACTION_MOVE,
                    x(fromX + (toX - fromX) * step / 10f), y(fromY + (toY - fromY) * step / 10f), 0)
                uiController.injectMotionEvent(event)
                event.recycle()
            }
            event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x(toX), y(toY), 0)
            uiController.injectMotionEvent(event)
            event.recycle()
            uiController.loopMainThreadForAtLeast(300L)
        }
    }

    private fun waitFor(check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            try {
                check()
                return
            } catch (error: Throwable) {
                if (SystemClock.elapsedRealtime() > deadline) throw error
                Thread.sleep(100L)
            }
        }
    }
}
