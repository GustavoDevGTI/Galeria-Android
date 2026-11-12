package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThemeToneRulesTest {

    @Test
    fun pinkThemeProducesFourDistinctScreenAndMenuTonesWithProgressiveDepth() {
        val pinkSeed = ThemeToneRules.rgb(232, 118, 164)
        val screenTones = ThemeToneRules.screenTones(pinkSeed)
        val menuTones = ThemeToneRules.menuTones(pinkSeed)

        assertEquals(4, screenTones.size)
        assertEquals(4, menuTones.size)
        assertEquals("Os 4 tons de tela devem ser distintos", 4, screenTones.toSet().size)
        assertEquals("Os 4 tons de submenu devem ser distintos", 4, menuTones.toSet().size)

        for (i in 0 until 3) {
            val currentMenuLum = ThemeToneRules.luminance(menuTones[i])
            val nextMenuLum = ThemeToneRules.luminance(menuTones[i + 1])
            assertTrue(
                "Os tons de submenu do tema rosa devem progredir em profundidade ($i -> ${i + 1}): $currentMenuLum vs $nextMenuLum",
                nextMenuLum > currentMenuLum + 0.015
            )
        }

        val seedHsv = ThemeToneRules.rgbToHsv(ThemeToneRules.normalizeSeed(pinkSeed))
        menuTones.forEachIndexed { index, tone ->
            val hsv = ThemeToneRules.rgbToHsv(tone)
            val hueDelta = hueDistance(seedHsv[0], hsv[0])
            assertTrue("O tom $index do submenu rosa deve preservar a família de matiz rosa (delta=$hueDelta)", hueDelta <= 18f)
            assertTrue("O tom $index do submenu rosa deve manter saturação cromática", hsv[1] >= 0.10f)
        }
    }

    @Test
    fun allFourSubmenuAndScreenTonesMaintainReadableContrastAcrossChromaticAndNeutralThemes() {
        val seeds = listOf(
            ThemeToneRules.rgb(232, 118, 164), // Rosa claro
            ThemeToneRules.rgb(138, 56, 92),   // Rosa escuro
            ThemeToneRules.rgb(232, 214, 166), // Areia claro
            ThemeToneRules.rgb(24, 28, 32),    // Grafite escuro
            ThemeToneRules.rgb(74, 134, 218),  // Azul
            ThemeToneRules.rgb(82, 168, 118),  // Verde
            ThemeToneRules.BLACK,
            ThemeToneRules.WHITE
        )

        for (seed in seeds) {
            val screenTones = ThemeToneRules.screenTones(seed)
            val menuTones = ThemeToneRules.menuTones(seed)
            val text = ThemeToneRules.text(seed)
            val menuText = ThemeToneRules.menuText(seed)

            assertEquals("Seed $seed deve gerar 4 tons de tela distintos", 4, screenTones.toSet().size)
            assertEquals("Seed $seed deve gerar 4 tons de submenu distintos", 4, menuTones.toSet().size)

            assertTrue(
                "Texto principal deve ter contraste >= 4.5 no fundo para seed $seed",
                ThemeToneRules.contrastRatio(text, screenTones[0]) >= 4.5
            )
            menuTones.forEachIndexed { index, tone ->
                val contrast = ThemeToneRules.contrastRatio(menuText, tone)
                assertTrue(
                    "Texto de submenu deve ter contraste >= 4.5 no tom $index para seed $seed (contraste=$contrast)",
                    contrast >= 4.5
                )
            }
        }
    }

    @Test
    fun launcherIconVariantFollowsThemeColorFamilyAndNeutralDefault() {
        assertEquals(LauncherIconManager.Variant.DEFAULT, LauncherIconManager.variantForSeed(ThemeToneRules.rgb(18, 18, 18)))
        assertEquals(LauncherIconManager.Variant.DEFAULT, LauncherIconManager.variantForSeed(ThemeToneRules.WHITE))
        assertEquals(LauncherIconManager.Variant.ROSE, LauncherIconManager.variantForSeed(ThemeToneRules.rgb(232, 118, 164)))
        assertEquals(LauncherIconManager.Variant.BLUE, LauncherIconManager.variantForSeed(ThemeToneRules.rgb(74, 134, 218)))
        assertEquals(LauncherIconManager.Variant.GREEN, LauncherIconManager.variantForSeed(ThemeToneRules.rgb(82, 168, 118)))
        assertEquals(LauncherIconManager.Variant.AMBER, LauncherIconManager.variantForSeed(ThemeToneRules.rgb(232, 184, 96)))
        assertEquals(LauncherIconManager.Variant.VIOLET, LauncherIconManager.variantForSeed(ThemeToneRules.rgb(148, 96, 224)))
    }

    @Test
    fun backgroundFollowsTheWheelGraduallyOnBothSidesOfTheLightDarkSwitch() {
        // Same geometry as the wheel: white at the center, vivid at half radius, black at the border.
        fun wheel(hue: Float, r: Float): Int =
            if (r <= 0.5f) ThemeToneRules.hsvToRgb(hue, ThemeToneRules.MAX_THEME_SATURATION * r / 0.5f, 1f)
            else ThemeToneRules.hsvToRgb(hue, ThemeToneRules.MAX_THEME_SATURATION, 1f - (r - 0.5f) / 0.5f)

        for (hue in listOf(0f, 60f, 120f, 220f, 300f)) {
            val samples = (0..40).map { it / 40f }.map { r ->
                val seed = wheel(hue, r)
                ThemeToneRules.darkMode(seed) to ThemeToneRules.luminance(ThemeToneRules.screenTones(seed)[0])
            }
            val light = samples.filter { !it.first }.map { it.second }
            val dark = samples.filter { it.first }.map { it.second }
            assertTrue("hue=$hue: o lado claro deve ter graduação real (${light.minOrNull()}..${light.maxOrNull()})",
                light.max() - light.min() >= 0.10)
            assertTrue("hue=$hue: o lado escuro deve ter graduação real (${dark.minOrNull()}..${dark.maxOrNull()})",
                dark.max() - dark.min() >= 0.10)
            for (i in 1 until samples.size) {
                if (samples[i].first == samples[i - 1].first) {
                    assertTrue("hue=$hue: o fundo deve escurecer em direção à borda (amostra $i)",
                        samples[i].second <= samples[i - 1].second + 0.02)
                }
            }
        }
    }

    private fun hueDistance(first: Float, second: Float): Float {
        val diff = abs(first - second) % 360f
        return if (diff > 180f) 360f - diff else diff
    }
}
