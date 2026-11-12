package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIconManagerTest {
    @Test
    fun selectsTheIconFamilyFromTheThemeHueAndSaturation() {
        assertEquals(LauncherIconManager.Variant.DEFAULT, LauncherIconManager.variantForSeed(Ui.DEFAULT_THEME_SEED))
        assertEquals(LauncherIconManager.Variant.DEFAULT, LauncherIconManager.variantForSeed(ThemeToneRules.hsvToRgb(320f, 0.14f, 0.8f)))

        val cases = listOf(
            1f to LauncherIconManager.Variant.ROSE,
            24f to LauncherIconManager.Variant.ROSE,
            26f to LauncherIconManager.Variant.AMBER,
            74f to LauncherIconManager.Variant.AMBER,
            76f to LauncherIconManager.Variant.GREEN,
            169f to LauncherIconManager.Variant.GREEN,
            171f to LauncherIconManager.Variant.BLUE,
            254f to LauncherIconManager.Variant.BLUE,
            256f to LauncherIconManager.Variant.VIOLET,
            314f to LauncherIconManager.Variant.VIOLET,
            316f to LauncherIconManager.Variant.ROSE
        )
        for ((hue, expected) in cases) {
            assertEquals("Matiz $hue", expected, LauncherIconManager.variantForSeed(ThemeToneRules.hsvToRgb(hue, 0.4f, 0.8f)))
        }
    }
}
