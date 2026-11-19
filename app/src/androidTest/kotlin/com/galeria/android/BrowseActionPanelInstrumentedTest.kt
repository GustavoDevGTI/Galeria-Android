package com.galeria.android

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class BrowseActionPanelInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private var savedBar: String? = null
    private var savedOrder: String? = null

    @Before fun startFromTheDefaultLayout() {
        savedBar = prefs.getString("browse_bar_main", null)
        savedOrder = prefs.getString("browse_order_main", null)
        prefs.edit().remove("browse_bar_main").remove("browse_order_main")
            .putBoolean("initial_all_files_requested", true).putBoolean("all_files_prompted", true).commit()
    }

    @After fun restoreTheLayout() {
        prefs.edit().apply {
            if (savedBar == null) remove("browse_bar_main") else putString("browse_bar_main", savedBar)
            if (savedOrder == null) remove("browse_order_main") else putString("browse_order_main", savedOrder)
        }.commit()
    }

    @Test fun dragMovesAnOptionBetweenThePanelAndTheBarAndTheChoiceIsKept() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The panel replaced the three-dot menu: every option off the bar, each with its icon.
            onView(withContentDescription(R.string.action_menu)).perform(click())
            onView(withTagValue(`is`("browse_panel"))).check(matches(isDisplayed()))
            for (id in listOf("filter", "folder_organization", "spacing", "create_folder", "trash_hidden", "refresh", "trash", "theme")) {
                onView(withTagValue(`is`("browse_panel_$id"))).check(matches(isDisplayed()))
            }
            onView(withTagValue(`is`("browse_panel_sort"))).check(doesNotExist())

            // The bar holds four shortcuts plus the fixed "Menu" button: free a place first.
            onView(isRoot()).perform(dragBetween("browse_settings", "browse_panel_filter", toLeftHalf = true))
            waitFor { onView(withTagValue(`is`("browse_panel_settings"))).check(matches(isDisplayed())) }
            assertEquals("sort,organize,hidden", prefs.getString("browse_bar_main", null))

            // Panel -> bar: "Tema" becomes the first shortcut and leaves the panel.
            onView(isRoot()).perform(dragBetween("browse_panel_theme", "browse_sort", toLeftHalf = true))
            waitFor { onView(withTagValue(`is`("browse_theme"))).check(matches(isDisplayed())) }
            onView(withTagValue(`is`("browse_panel_theme"))).check(doesNotExist())
            scenario.onActivity { activity ->
                val row = activity.window.decorView.findViewWithTag<View>("gallery_browse_bar").let { it as android.view.ViewGroup }.getChildAt(0) as android.view.ViewGroup
                assertEquals("browse_theme", row.getChildAt(0).tag)
                assertEquals(5, row.childCount)
                assertEquals(GalleryBrowseBar.MENU_TAG, row.getChildAt(4).tag)
            }
            assertEquals("theme,sort,organize,hidden", prefs.getString("browse_bar_main", null))

            // Bar -> panel: the shortcut goes back to the panel and the bar keeps the others.
            onView(isRoot()).perform(dragBetween("browse_theme", "browse_panel_settings", toLeftHalf = true))
            waitFor { onView(withTagValue(`is`("browse_panel_theme"))).check(matches(isDisplayed())) }
            onView(withTagValue(`is`("browse_theme"))).check(doesNotExist())
            assertEquals("sort,organize,hidden", prefs.getString("browse_bar_main", null))
            assertTrue(prefs.getString("browse_order_main", null).orEmpty().indexOf("theme") <
                prefs.getString("browse_order_main", null).orEmpty().indexOf("filter"))
            pressBack()
            onView(withTagValue(`is`("browse_panel"))).check(doesNotExist())
        }
        // Reopening keeps the panel order the user chose.
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription(R.string.action_menu)).perform(click())
            it.onActivity { activity ->
                val grid = activity.window.decorView.findViewWithTag<android.view.ViewGroup>("browse_panel_grid")
                val firstRow = grid.getChildAt(0) as android.view.ViewGroup
                assertEquals("browse_panel_theme", firstRow.getChildAt(0).tag)
                assertFalse(activity.window.decorView.findViewWithTag<View>("browse_panel_sort") != null)
            }
        }
    }

    /** Holds the first view past the long-press timeout, drags it onto the second and lets go. */
    private fun dragBetween(fromTag: String, toTag: String, toLeftHalf: Boolean): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription(): String = "arrastar $fromTag até $toTag"
        override fun perform(uiController: UiController, root: View) {
            fun center(tag: String, leftHalf: Boolean = false): Pair<Float, Float> {
                val view = requireNotNull(root.findViewWithTag<View>(tag)) { "Sem $tag" }
                val location = IntArray(2).also(view::getLocationOnScreen)
                val x = location[0] + view.width * (if (leftHalf) 0.25f else 0.5f)
                return x to location[1] + view.height / 2f
            }
            val (fromX, fromY) = center(fromTag)
            val (toX, toY) = center(toTag, toLeftHalf)
            val down = SystemClock.uptimeMillis()
            var event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, fromY, 0)
            uiController.injectMotionEvent(event); event.recycle()
            uiController.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 300L)
            for (step in 1..16) {
                val time = SystemClock.uptimeMillis()
                event = MotionEvent.obtain(down, time, MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * step / 16f, fromY + (toY - fromY) * step / 16f, 0)
                uiController.injectMotionEvent(event); event.recycle()
                uiController.loopMainThreadForAtLeast(20L)
            }
            uiController.loopMainThreadForAtLeast(150L)
            event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, toX, toY, 0)
            uiController.injectMotionEvent(event); event.recycle()
            uiController.loopMainThreadForAtLeast(400L)
        }
    }

    private fun waitFor(check: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            try { check(); return } catch (error: Throwable) {
                if (SystemClock.elapsedRealtime() > deadline) throw error
                Thread.sleep(100L)
            }
        }
    }
}
