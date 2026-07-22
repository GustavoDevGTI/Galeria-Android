package com.galeria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AlbumGridPinchInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun horizontalPinchDecreasesAndIncreasesGridColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hadSavedColumns = prefs.contains(PREF_GRID_COLUMNS)
        val originalColumns = prefs.getInt(PREF_GRID_COLUMNS, 0)
        prefs.edit().putInt(PREF_GRID_COLUMNS, 4).commit()
        MediaStoreRepository.invalidateCache()
        val intent = Intent(context, AlbumMediaActivity::class.java).apply {
            putExtra("album_key", "Pictures/GridPinchTest/")
            putExtra("album_name", "Pinça")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            ActivityScenario.launch<AlbumMediaActivity>(intent).use { scenario ->
                onView(withContentDescription("Grade de mídias, 4 colunas"))
                    .check(matches(withContentDescription("Grade de mídias, 4 colunas")))

                scenario.onActivity { activity ->
                    val grid = findViewWithDescription(
                        activity.findViewById(android.R.id.content),
                        "Grade de mídias, 4 colunas"
                    )
                    assertNotNull(grid)
                    dispatchHorizontalPinch(requireNotNull(grid), startSpanRatio = 0.30f, endSpanRatio = 0.42f)
                }
                onView(withContentDescription("Grade de mídias, 3 colunas"))
                    .check(matches(withContentDescription("Grade de mídias, 3 colunas")))

                scenario.onActivity { activity ->
                    val grid = findViewWithDescription(
                        activity.findViewById(android.R.id.content),
                        "Grade de mídias, 3 colunas"
                    )
                    assertNotNull(grid)
                    dispatchHorizontalPinch(requireNotNull(grid), startSpanRatio = 0.42f, endSpanRatio = 0.30f)
                }
                onView(withContentDescription("Grade de mídias, 4 colunas"))
                    .check(matches(withContentDescription("Grade de mídias, 4 colunas")))
            }
        } finally {
            val editor = prefs.edit()
            if (hadSavedColumns) editor.putInt(PREF_GRID_COLUMNS, originalColumns) else editor.remove(PREF_GRID_COLUMNS)
            editor.commit()
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun findViewWithDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findViewWithDescription(view.getChildAt(index), description)?.let { return it }
        }
        return null
    }

    private fun dispatchHorizontalPinch(view: View, startSpanRatio: Float, endSpanRatio: Float) {
        val width = view.width.toFloat().coerceAtLeast(1f)
        val centerX = width / 2f
        val centerY = view.height.toFloat().coerceAtLeast(1f) / 2f
        val startSpan = width * startSpanRatio
        val intermediateSpan = startSpan + (width * endSpanRatio - startSpan) * 0.08f
        val endSpan = width * endSpanRatio
        val downTime = SystemClock.uptimeMillis()
        dispatch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(centerX - startSpan / 2f), centerY)
        dispatch(
            view,
            downTime,
            downTime + 16L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            floatArrayOf(centerX - startSpan / 2f, centerX + startSpan / 2f),
            centerY
        )
        dispatch(
            view,
            downTime,
            downTime + 32L,
            MotionEvent.ACTION_MOVE,
            floatArrayOf(centerX - intermediateSpan / 2f, centerX + intermediateSpan / 2f),
            centerY
        )
        dispatch(
            view,
            downTime,
            downTime + 48L,
            MotionEvent.ACTION_MOVE,
            floatArrayOf(centerX - endSpan / 2f, centerX + endSpan / 2f),
            centerY
        )
        dispatch(
            view,
            downTime,
            downTime + 64L,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            floatArrayOf(centerX - endSpan / 2f, centerX + endSpan / 2f),
            centerY
        )
        dispatch(view, downTime, downTime + 80L, MotionEvent.ACTION_UP, floatArrayOf(centerX - endSpan / 2f), centerY)
    }

    private fun dispatch(
        view: View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        xCoordinates: FloatArray,
        y: Float
    ) {
        val properties = Array(xCoordinates.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(xCoordinates.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xCoordinates[index]
                this.y = y
                pressure = 1f
                size = 1f
            }
        }
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            xCoordinates.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        ).let { event ->
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private companion object {
        const val PREF_GRID_COLUMNS = "media_grid_columns"
    }
}
