package com.galeria.android

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeCustomizationInstrumentedTest {
    @Test
    fun systemBarIconsKeepContrastForLightAndDarkThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hadTheme = prefs.contains("theme_color")
        val originalTheme = prefs.getInt("theme_color", Color.rgb(18, 18, 18))
        try {
            prefs.edit().putInt("theme_color", Color.rgb(232, 214, 166)).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    assertTrue(controller.isAppearanceLightStatusBars)
                    assertTrue(controller.isAppearanceLightNavigationBars)
                }
            }

            prefs.edit().putInt("theme_color", Color.rgb(24, 28, 32)).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    assertFalse(controller.isAppearanceLightNavigationBars)
                }
            }
        } finally {
            Ui.clearThemePreview()
            prefs.edit().apply {
                if (hadTheme) putInt("theme_color", originalTheme) else remove("theme_color")
            }.commit()
            LauncherIconManager.syncWithPreferences(context)
        }
    }

    @Test
    fun colorWheelPreviewsMainAndSubmenuColorsAndKeepsSaveAndCancelBehavior() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hadTheme = prefs.contains("theme_color")
        val originalTheme = prefs.getInt("theme_color", Color.rgb(18, 18, 18))
        val savedTheme = Color.rgb(70, 78, 82)
        prefs.edit().putInt("theme_color", savedTheme).commit()
        LauncherIconManager.syncWithPreferences(context)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                onView(withText(context.getString(R.string.settings_theme_color))).perform(click())
                for (color in listOf(Color.RED, Color.rgb(232, 118, 164), Color.WHITE, Color.rgb(24, 28, 32), Color.rgb(232, 214, 166))) {
                    onView(withTagValue(equalTo(ThemeColorWheelView.TAG))).check(matches(isDisplayed()))
                        .perform(selectWheelColor(color))
                    onView(withTagValue(equalTo("theme_color_panel"))).check { view, error ->
                        if (error != null) throw error
                        assertEquals("O cartão deve mostrar o fundo principal da galeria", Ui.bg(view.context),
                            (view.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
                        val title = findText(view, context.getString(R.string.settings_choose_theme_color))
                        assertEquals(Ui.text(view.context), title.currentTextColor)
                        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(title.currentTextColor, Ui.bg(view.context)) >= 4.5)
                        val bg = Ui.bg(view.context)
                        listOf(R.string.action_cancel, R.string.settings_theme_apply).forEach { label ->
                            val action = findText(view, context.getString(label))
                            val ripple = action.background as RippleDrawable
                            assertTrue("As ações do diálogo devem ser texto, sem caixa preenchida.",
                                (0 until ripple.numberOfLayers).all { ripple.getId(it) == android.R.id.mask })
                            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(action.currentTextColor, bg) >= 4.5)
                        }
                        assertEquals("A roda de cores substitui o ajuste separado de luminosidade.", null,
                            findSeekBar(view))
                        for (hue in listOf(0f, 120f, 240f)) {
                            assertEquals("O centro da roda deve ser branco.", Color.WHITE, ThemeColorWheelView.colorAt(hue, 0f))
                            assertEquals("A borda da roda deve ser preta.", Color.BLACK, ThemeColorWheelView.colorAt(hue, 1f))
                            assertTrue("A cor deve escurecer em direção à borda.",
                                Ui.luminance(ThemeColorWheelView.colorAt(hue, 0.25f)) >
                                    Ui.luminance(ThemeColorWheelView.colorAt(hue, 0.75f)))
                        }
                        assertEquals(4, Ui.menuTones(view.context).toSet().size)
                        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                            Ui.menuText(view.context), Ui.menuSurface(view.context)) >= 4.5)
                    }
                    scenario.onActivity { activity ->
                        val hsv = FloatArray(3)
                        Color.colorToHSV(Ui.themeSeed(activity), hsv)
                        assertTrue(hsv[1] <= ThemeColorWheelView.MAX_SATURATION + 0.001f)
                        assertTrue(Ui.themeSeed(activity) != Ui.normalizeThemeSeed(savedTheme))
                    }
                }

                onView(withText(context.getString(R.string.action_cancel))).perform(click())
                assertLauncher(context, "LauncherDefault", R.drawable.ic_launcher)
                scenario.onActivity { activity ->
                    assertEquals(Ui.normalizeThemeSeed(savedTheme), Ui.themeSeed(activity))
                    val topInset = WindowInsetsCompat.toWindowInsetsCompat(
                        activity.window.decorView.rootWindowInsets,
                        activity.window.decorView
                    ).getInsets(WindowInsetsCompat.Type.statusBars()).top
                    val settingsTitle = findText(activity.window.decorView, context.getString(R.string.action_settings))
                    val location = IntArray(2)
                    settingsTitle.getLocationOnScreen(location)
                    assertTrue(location[1] >= topInset)
                }
                onView(withText(context.getString(R.string.settings_theme_color))).perform(click())
                onView(withTagValue(equalTo(ThemeColorWheelView.TAG))).perform(selectWheelColor(Color.rgb(106, 156, 224)))
                var selected = 0
                var secondary = 0
                scenario.onActivity { activity ->
                    selected = Ui.themeSeed(activity)
                    secondary = Ui.menuSurface(activity)
                }
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    try {
                        java.io.File(context.getExternalFilesDir(null), "theme-dual-preview.png").outputStream().use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { screenshot.recycle() }
                }
                onView(withText(context.getString(R.string.settings_theme_apply))).perform(click())
                scenario.onActivity { activity ->
                    assertEquals("Aplicar deve salvar a cor visualizada", selected, prefs.getInt("theme_color", 0))
                    assertEquals(selected, Ui.themeSeed(activity))
                    assertEquals("O submenu real deve manter a cor mostrada na prévia", secondary, Ui.menuSurface(activity))
                    val section = findText(activity.window.decorView, context.getString(R.string.settings_section_colors))
                    assertTrue("O título da seção precisa contrastar com o fundo.",
                        androidx.core.graphics.ColorUtils.calculateContrast(section.currentTextColor, Ui.bg(activity)) >= 4.5)
                }
                assertLauncher(context, "LauncherBlue", R.drawable.ic_launcher_blue)
                onView(withText(context.getString(R.string.settings_theme_color))).perform(click())
                onView(withText(context.getString(R.string.settings_theme_restore))).perform(click())
                scenario.onActivity { activity ->
                    assertFalse(prefs.contains(Ui.THEME_COLOR_PREF))
                    assertEquals(Ui.DEFAULT_THEME_SEED, Ui.themeSeed(activity))
                }
                assertLauncher(context, "LauncherDefault", R.drawable.ic_launcher)
            }
        } finally {
            Ui.clearThemePreview()
            prefs.edit().apply {
                if (hadTheme) putInt("theme_color", originalTheme) else remove("theme_color")
            }.commit()
            LauncherIconManager.syncWithPreferences(context)
        }
    }

    private fun assertLauncher(context: Context, alias: String, icon: Int) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val activity = context.packageManager.queryIntentActivities(intent, 0).single().activityInfo
        assertEquals("${context.packageName}.$alias", activity.name)
        assertEquals(MainActivity::class.java.name, activity.targetActivity)
        assertEquals(icon, activity.iconResource)
        if (Build.VERSION.SDK_INT >= 33) {
            val drawable = activity.loadIcon(context.packageManager) as AdaptiveIconDrawable
            val mask = requireNotNull(drawable.monochrome)
            val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            try {
                mask.setBounds(0, 0, bitmap.width, bitmap.height)
                mask.draw(Canvas(bitmap))
                assertEquals("O céu da lente deve ficar transparente no ícone temático.", 0,
                    Color.alpha(bitmap.getPixel(54, 42)))
                assertTrue("O ícone temático deve preservar o sol.",
                    Color.alpha(bitmap.getPixel(54, 50)) > 0)
                assertTrue("O ícone temático deve preservar as montanhas.",
                    Color.alpha(bitmap.getPixel(54, 66)) > 0)
                assertTrue("O ícone temático deve preservar a roda.",
                    Color.alpha(bitmap.getPixel(76, 44)) > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun findText(root: android.view.View, text: String): android.widget.TextView {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                runCatching { return findText(root.getChildAt(index), text) }
            }
        }
        error("Texto não encontrado: $text")
    }

    private fun findSeekBar(root: android.view.View): android.widget.SeekBar? {
        if (root is android.widget.SeekBar) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                findSeekBar(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun selectWheelColor(color: Int): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<android.view.View> =
            isAssignableFrom(ThemeColorWheelView::class.java)

        override fun getDescription(): String = "selecionar uma cor no círculo"

        override fun perform(uiController: UiController, view: android.view.View) {
            (view as ThemeColorWheelView).setColor(color, notify = true)
            uiController.loopMainThreadUntilIdle()
        }
    }
}
