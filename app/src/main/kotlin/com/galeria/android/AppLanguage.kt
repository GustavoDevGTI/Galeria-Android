package com.galeria.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import java.util.Locale

/**
 * "Idioma": Portuguese (the default), English or Spanish, chosen inside the app regardless of the
 * phone language. Every screen wraps its context with [wrap], so the strings follow the choice.
 */
internal object AppLanguage {
    const val PORTUGUESE = "pt"
    const val ENGLISH = "en"
    const val SPANISH = "es"
    val codes = listOf(PORTUGUESE, ENGLISH, SPANISH)

    /** A stored code, or Portuguese for anything unknown. */
    fun normalized(code: String?): String = if (code in codes) code!! else PORTUGUESE

    fun current(context: Context): String = normalized(
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE).getString(GalleryPreferences.LANGUAGE, PORTUGUESE)
    )

    fun locale(code: String): Locale = when (normalized(code)) {
        ENGLISH -> Locale.ENGLISH
        SPANISH -> Locale.forLanguageTag("es")
        else -> Locale.forLanguageTag("pt-BR")
    }

    fun wrap(base: Context): Context {
        val locale = locale(current(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (androidx.core.os.ConfigurationCompat.getLocales(config)[0] == locale) return base
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Background work (toasts, the organizer) uses the application context, which keeps its language until restarted. */
    @Suppress("DEPRECATION")
    private fun refreshApplication(app: Context, code: String) {
        val config = Configuration(app.resources.configuration)
        config.setLocale(locale(code))
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    /** Saves the new language and reopens the gallery so every screen shows it. */
    fun change(activity: Activity, code: String) {
        val next = normalized(code)
        if (next == current(activity)) return
        activity.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE).edit()
            .putString(GalleryPreferences.LANGUAGE, next).apply()
        Locale.setDefault(locale(next))
        refreshApplication(activity.applicationContext, next)
        val main = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val settings = Intent(activity, SettingsActivity::class.java)
        activity.startActivities(arrayOf(main, settings))
        activity.overridePendingTransition(0, 0)
    }
}

/** The information sheet is built with Portuguese labels; this shows them in the chosen language. */
internal object MetadataLabels {
    private val ids = mapOf(
        "Arquivo" to R.string.meta_file,
        "Nome" to R.string.meta_name,
        "Data" to R.string.meta_date,
        "Tamanho" to R.string.meta_size,
        "Formato" to R.string.meta_format,
        "Pasta" to R.string.meta_folder,
        "Resolução" to R.string.meta_resolution,
        "Câmera" to R.string.meta_camera,
        "Capturada em" to R.string.meta_captured_at,
        "ISO" to R.string.meta_iso,
        "Abertura" to R.string.meta_aperture,
        "Exposição" to R.string.meta_exposure,
        "Distância focal" to R.string.meta_focal_length,
        "Imagem e câmera" to R.string.meta_image_camera,
        "Localização" to R.string.meta_location,
        "Coordenadas" to R.string.meta_coordinates,
        "Duração" to R.string.meta_duration,
        "Rotação" to R.string.meta_rotation,
        "Codec" to R.string.meta_codec,
        "Taxa de bits" to R.string.meta_bit_rate,
        "Quadros por segundo" to R.string.meta_frame_rate,
        "Vídeo" to R.string.meta_video,
        "Desconhecida" to R.string.meta_unknown_date,
        "Desconhecido" to R.string.meta_unknown
    )

    fun translate(context: Context, label: String): String = ids[label]?.let(context::getString) ?: label
}
