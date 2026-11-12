package com.galeria.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

internal object LauncherIconManager {
    enum class Variant(val alias: String) {
        DEFAULT("LauncherDefault"),
        ROSE("LauncherRose"),
        AMBER("LauncherAmber"),
        GREEN("LauncherGreen"),
        BLUE("LauncherBlue"),
        VIOLET("LauncherViolet")
    }

    fun variantForSeed(seed: Int): Variant {
        val (hue, saturation) = ThemeToneRules.rgbToHsv(ThemeToneRules.normalizeSeed(seed))
        if (saturation < 0.18f) return Variant.DEFAULT
        return when {
            hue >= 315f || hue < 25f -> Variant.ROSE
            hue < 75f -> Variant.AMBER
            hue < 170f -> Variant.GREEN
            hue < 255f -> Variant.BLUE
            else -> Variant.VIOLET
        }
    }

    fun syncWithPreferences(context: Context) {
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val target = variantForSeed(prefs.getInt(Ui.THEME_COLOR_PREF, Ui.DEFAULT_THEME_SEED))
        val manager = context.packageManager
        val packageName = context.packageName
        // Older Android versions need to enable the new entry before disabling the old one.
        val ordered = listOf(target) + Variant.entries.filter { it != target }
        try {
            val changes = ordered.mapNotNull { variant ->
                val component = ComponentName(packageName, "$packageName.${variant.alias}")
                val state = when {
                    variant == target && variant != Variant.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    variant != target && variant == Variant.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                if (manager.getComponentEnabledSetting(component) == state) null else component to state
            }
            if (changes.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33) {
                manager.setComponentEnabledSettings(changes.map { (component, state) ->
                    PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                })
            } else {
                changes.forEach { (component, state) ->
                    manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }
        } catch (error: IllegalArgumentException) {
            Log.w("LauncherIconManager", "Falha ao atualizar os atalhos da Galeria", error)
        } catch (error: SecurityException) {
            Log.w("LauncherIconManager", "O Android recusou a atualização do ícone da Galeria", error)
        }
    }
}
