package com.galeria.android

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure tonal derivation engine that expands a single user-selected theme seed color
 * into a 4-tone chromatic hierarchy for both main screens and submenus/options,
 * while guaranteeing WCAG 4.5:1 contrast for text legibility across all tones.
 */
object ThemeToneRules {
    const val MAX_THEME_SATURATION = 0.48f
    private const val MIN_CONTRAST = 4.65

    @JvmStatic
    fun normalizeSeed(color: Int): Int {
        val hsv = rgbToHsv(color)
        hsv[1] = hsv[1].coerceAtMost(MAX_THEME_SATURATION)
        return hsvToRgb(hsv[0], hsv[1], hsv[2])
    }

    @JvmStatic
    fun darkMode(seed: Int): Boolean = luminance(normalizeSeed(seed)) < DARK_THRESHOLD

    /** 0 = cor mais escura do lado escuro, 1 = mais próxima da virada para o tema claro. */
    private fun darkProgress(norm: Int): Float = (luminance(norm) / DARK_THRESHOLD).toFloat().coerceIn(0f, 1f)

    /** 0 = logo após a virada para o tema claro, 1 = branco. */
    private fun lightProgress(norm: Int): Float =
        ((luminance(norm) - DARK_THRESHOLD) / (1.0 - DARK_THRESHOLD)).toFloat().coerceIn(0f, 1f)

    /**
     * Derives 4 progressive screen-level tones from [seed]:
     * - Tone 0 (`bg`): Base canvas background
     * - Tone 1 (`surface`): Primary cards and bottom action docks
     * - Tone 2 (`elevatedSurface` / `search`): Search capsules, secondary badges, elevated controls
     * - Tone 3 (`highlightSurface`): Active cards, selected chips, and tonal highlights
     */
    @JvmStatic
    fun screenTones(seed: Int): IntArray {
        val norm = normalizeSeed(seed)
        val hsv = rgbToHsv(norm)
        val hue = if (hsv[1] < 0.04f) 215f else hsv[0]
        val baseSat = hsv[1]
        val isDark = luminance(norm) < DARK_THRESHOLD
        val textColor = text(norm)

        if (isDark) {
            val chromatic = baseSat >= 0.05f
            val s0 = if (chromatic) (baseSat * 0.92f).coerceIn(0.10f, 0.46f) else baseSat * 0.5f
            val s1 = if (chromatic) (baseSat * 1.06f).coerceIn(0.14f, 0.52f) else baseSat * 0.6f
            val s2 = if (chromatic) (baseSat * 1.18f).coerceIn(0.18f, 0.58f) else baseSat * 0.7f
            val s3 = if (chromatic) (baseSat * 1.30f).coerceIn(0.22f, 0.64f) else baseSat * 0.8f

            // The background follows the chosen position: black near the wheel border,
            // progressively lighter until the switch to the light theme.
            val contrastLimit = maxDarkValueForContrast(hue, s3, textColor, MIN_CONTRAST)
            var v0 = DARK_BG_MIN + darkProgress(norm) * (DARK_BG_MAX - DARK_BG_MIN)
            val v3 = min(v0 + 0.16f, contrastLimit)
            if (v3 - v0 < 0.09f) v0 = v3 - 0.09f
            val span = v3 - v0
            val v1 = v0 + span * 0.33f
            val v2 = v0 + span * 0.66f

            return intArrayOf(
                hsvToRgb(hue, s0, v0),
                hsvToRgb(hue, s1, v1),
                hsvToRgb(hue, s2, v2),
                hsvToRgb(hue, s3, v3)
            )
        } else {
            val anchor = if (luminance(norm) > 0.86) {
                blend(norm, rgb(164, 172, 186), 0.42f)
            } else {
                norm
            }
            // Right after the switch the background keeps a strong tint; near the center it reaches white.
            val white0 = LIGHT_BG_MIN_WHITE + lightProgress(norm) * (LIGHT_BG_MAX_WHITE - LIGHT_BG_MIN_WHITE)
            return IntArray(4) { index ->
                val raw = blend(anchor, WHITE, (white0 - index * 0.13f).coerceAtLeast(0f))
                ensureContrastWithDarkText(raw, textColor, MIN_CONTRAST)
            }
        }
    }

    /**
     * Derives 4 progressive submenu/option tones from [seed], each with a strict semantic role:
     * - Tone 0 (`menuSurface`): Submenu/dialog container backdrop
     * - Tone 1 (`menuOptionSurface`): Standard unselected option surface (uniform across all unselected options)
     * - Tone 2 (`menuElevatedSurface`): Distinct interactive controls (inputs, secondary action buttons, session-active items)
     * - Tone 3 (`menuHighlightSurface`): Active/selected option surface and primary confirmation buttons
     */
    @JvmStatic
    fun menuTones(seed: Int): IntArray {
        val norm = normalizeSeed(seed)
        val hsv = rgbToHsv(norm)
        val chromatic = hsv[1] >= 0.05f
        val hue = if (chromatic) hsv[0] else 215f
        val baseSat = hsv[1]
        val baseVal = hsv[2]
        val textColor = menuText(norm)

        val s0 = if (chromatic) (baseSat * 1.05f).coerceIn(0.14f, 0.52f) else baseSat * 0.5f
        val s1 = if (chromatic) (baseSat * 1.16f).coerceIn(0.18f, 0.57f) else baseSat * 0.6f
        val s2 = if (chromatic) (baseSat * 1.26f).coerceIn(0.22f, 0.62f) else baseSat * 0.7f
        val s3 = if (chromatic) (baseSat * 1.36f).coerceIn(0.26f, 0.68f) else baseSat * 0.8f

        val targetTopV = if (chromatic) (0.39f + baseVal * 0.06f).coerceIn(0.40f, 0.46f) else 0.35f
        val maxV3 = maxDarkValueForContrast(hue, s3, textColor, MIN_CONTRAST).coerceAtMost(targetTopV)
        val minV0 = (if (chromatic) 0.16f + baseVal * 0.04f else 0.13f).coerceIn(0.13f, min(0.21f, maxV3 - 0.15f))
        val span = (maxV3 - minV0).coerceAtLeast(0.15f)

        val v0 = minV0
        val v1 = minV0 + span * 0.33f
        val v2 = minV0 + span * 0.66f
        val v3 = minV0 + span

        return intArrayOf(
            hsvToRgb(hue, s0, v0),
            hsvToRgb(hue, s1, v1),
            hsvToRgb(hue, s2, v2),
            hsvToRgb(hue, s3, v3)
        )
    }

    @JvmStatic
    fun search(seed: Int): Int = screenTones(seed)[2]

    @JvmStatic
    fun text(seed: Int): Int = if (darkMode(seed)) TEXT_DARK else TEXT_LIGHT

    @JvmStatic
    fun menuText(seed: Int): Int {
        val norm = normalizeSeed(seed)
        return if (darkMode(norm)) TEXT_DARK else blend(WHITE, norm, 0.10f)
    }

    @JvmStatic
    fun muted(seed: Int): Int {
        val norm = normalizeSeed(seed)
        val base = text(norm)
        val bg = screenTones(norm)[0]
        return if (darkMode(norm)) blend(base, bg, 0.36f) else blend(base, bg, 0.46f)
    }

    @JvmStatic
    fun accent(seed: Int): Int {
        val norm = normalizeSeed(seed)
        if (darkMode(norm) && luminance(norm) < 0.35) {
            return blend(norm, WHITE, 0.45f)
        }
        if (!darkMode(norm) && luminance(norm) > 0.72) {
            return blend(norm, BLACK, 0.25f)
        }
        return norm
    }

    @JvmStatic
    fun border(seed: Int): Int {
        val tones = screenTones(seed)
        return if (darkMode(seed)) blend(tones[2], accent(seed), 0.22f) else blend(tones[2], accent(seed), 0.18f)
    }

    @JvmStatic
    fun menuBorder(seed: Int): Int {
        val tones = menuTones(seed)
        return blend(tones[2], accent(seed), 0.25f)
    }

    @JvmStatic
    fun blend(first: Int, second: Int, secondAmount: Float): Int {
        val amount = max(0f, min(1f, secondAmount))
        val inverse = 1f - amount
        return rgb(
            (red(first) * inverse + red(second) * amount).roundToInt(),
            (green(first) * inverse + green(second) * amount).roundToInt(),
            (blue(first) * inverse + blue(second) * amount).roundToInt()
        )
    }

    @JvmStatic
    fun luminance(color: Int): Double =
        (0.299 * red(color) + 0.587 * green(color) + 0.114 * blue(color)) / 255.0

    @JvmStatic
    fun wcagLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s < 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red(color)) + 0.7152 * channel(green(color)) + 0.0722 * channel(blue(color))
    }

    @JvmStatic
    fun contrastRatio(foreground: Int, background: Int): Double {
        val l1 = wcagLuminance(foreground)
        val l2 = wcagLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun maxDarkValueForContrast(hue: Float, saturation: Float, lightText: Int, minContrast: Double): Float {
        var low = 0.16f
        var high = 0.52f
        repeat(14) {
            val mid = (low + high) * 0.5f
            val candidate = hsvToRgb(hue, saturation, mid)
            if (contrastRatio(lightText, candidate) >= minContrast) {
                low = mid
            } else {
                high = mid
            }
        }
        return low
    }

    private fun ensureContrastWithDarkText(color: Int, darkText: Int, minContrast: Double): Int {
        if (contrastRatio(darkText, color) >= minContrast) return color
        var low = 0f
        var high = 1f
        var best = blend(color, WHITE, 0.5f)
        repeat(12) {
            val mid = (low + high) * 0.5f
            val candidate = blend(color, WHITE, mid)
            if (contrastRatio(darkText, candidate) >= minContrast) {
                best = candidate
                high = mid
            } else {
                low = mid
            }
        }
        return best
    }

    @JvmStatic
    fun rgbToHsv(color: Int): FloatArray {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        var hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        val sat = if (maxC == 0f) 0f else delta / maxC
        return floatArrayOf(hue, sat, maxC)
    }

    @JvmStatic
    fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(
            ((r1 + m) * 255f).roundToInt(),
            ((g1 + m) * 255f).roundToInt(),
            ((b1 + m) * 255f).roundToInt()
        )
    }

    @JvmStatic
    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    @JvmStatic
    fun red(color: Int): Int = (color shr 16) and 0xFF

    @JvmStatic
    fun green(color: Int): Int = (color shr 8) and 0xFF

    @JvmStatic
    fun blue(color: Int): Int = color and 0xFF

    private const val DARK_THRESHOLD = 0.56
    private const val DARK_BG_MIN = 0.07f
    private const val DARK_BG_MAX = 0.34f
    private const val LIGHT_BG_MIN_WHITE = 0.30f
    private const val LIGHT_BG_MAX_WHITE = 0.90f

    val BLACK = rgb(0, 0, 0)
    val WHITE = rgb(255, 255, 255)
    private val TEXT_DARK = rgb(245, 247, 250)
    private val TEXT_LIGHT = rgb(18, 18, 18)
}
