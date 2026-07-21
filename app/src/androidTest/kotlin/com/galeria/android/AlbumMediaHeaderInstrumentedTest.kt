package com.galeria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AlbumMediaHeaderInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun albumTitleAndSearchShareTheSameToolbar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val albumName = "Álbum de teste"
        val intent = Intent(context, AlbumMediaActivity::class.java).apply {
            putExtra("album_key", "Pictures/AlbumHeaderTest-${System.currentTimeMillis()}/")
            putExtra("album_name", albumName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<AlbumMediaActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                val toolbar = content.findViewWithTag<View>("album_toolbar")
                val titleAndSearch = content.findViewWithTag<View>("album_search_title")
                assertNotNull(toolbar)
                assertNotNull(titleAndSearch)
                assertSame(toolbar, titleAndSearch.parent)
            }

            onView(withContentDescription("Pesquisar nesta pasta"))
                .check(matches(withHint("Pesquisar em $albumName")))
                .perform(click())
                .check(matches(withHint("Pesquisar nesta pasta")))
        }
    }
}
