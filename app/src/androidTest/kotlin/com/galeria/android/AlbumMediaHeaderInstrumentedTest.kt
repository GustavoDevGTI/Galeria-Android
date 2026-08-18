package com.galeria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun sortAndNewFolderOpenAsRightSidePanels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, AlbumMediaActivity::class.java).apply {
            putExtra("album_key", "Pictures/AlbumMenuTest-${System.currentTimeMillis()}/")
            putExtra("album_name", "Álbum de menu")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<AlbumMediaActivity>(intent).use {
            onView(withContentDescription("Mais opções")).perform(click())
            onView(withText("Ordenar por")).perform(clickClickableAncestor())
            waitForView {
                onView(withText("Nome alfabético")).inRoot(isDialog()).check { view, exception ->
                    if (exception != null) throw exception
                    assertRightAligned(view)
                }
            }
            val cancelLocation = IntArray(2)
            onView(withText("Cancelar")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                view.getLocationOnScreen(cancelLocation)
            }
            onView(withText("OK")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                val okLocation = IntArray(2)
                view.getLocationOnScreen(okLocation)
                assertTrue("As ações do painel devem ficar empilhadas verticalmente.", okLocation[1] > cancelLocation[1])
            }
            onView(withText("Ordenar por")).inRoot(isDialog()).check { view, exception ->
                if (exception != null) throw exception
                val panel = view.parent as View
                val background = panel.background as GradientDrawable
                assertTrue(
                    "O painel deve ter cantos arredondados.",
                    background.cornerRadius >= Ui.dp(view.context, 12).toFloat()
                )
            }

            androidx.test.espresso.Espresso.pressBack()
            waitForView {
                onView(withContentDescription("Mais opções")).check(matches(isDisplayed()))
            }
            onView(withContentDescription("Mais opções")).perform(click())
            onView(withText("Criar nova pasta")).perform(clickClickableAncestor())
            waitForView {
                onView(withHint("Título")).inRoot(isDialog()).check { view, exception ->
                    if (exception != null) throw exception
                    assertRightAligned(view)
                }
            }
        }
    }

    private fun assertRightAligned(view: View) {
        val root = view.rootView
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val screenWidth = view.resources.displayMetrics.widthPixels
        val screenHeight = view.resources.displayMetrics.heightPixels
        assertTrue("O painel lateral deve começar afastado da borda esquerda.", location[0] > 0)
        assertTrue("O painel lateral deve ser estreito e vertical.", root.width <= screenWidth * 0.72f)
        assertTrue("O painel lateral deve nascer abaixo da barra superior.", location[1] >= Ui.dp(view.context, 56))
        assertTrue("O painel lateral não deve ocupar a tela inteira.", root.height < screenHeight * 0.90f)
        val rightGap = screenWidth - (location[0] + root.width)
        assertTrue(
            "O painel lateral deve ficar junto à borda direita com uma pequena margem.",
            rightGap in 0..Ui.dp(view.context, 16)
        )
    }

    private fun waitForView(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000L
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Painel esperado não exibido.", lastFailure)
    }
}
