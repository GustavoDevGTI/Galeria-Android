package com.galeria.android

import android.content.Context
import java.text.DateFormat
import java.util.Date

/**
 * A setting picked from a list. The stored value is a fixed code, so the choice survives a change of
 * language; older installs stored the Portuguese label, which is still understood.
 */
internal class ChoiceSpec(
    val key: String,
    val codes: List<String>,
    private val legacyLabels: List<Set<String>>,
    val defaultIndex: Int = 0
) {
    /** Index of a stored value: a code, an old Portuguese label, or the default when unknown. */
    fun indexOf(stored: String?): Int {
        if (stored == null) return defaultIndex
        codes.indexOf(stored).takeIf { it >= 0 }?.let { return it }
        legacyLabels.indexOfFirst { stored in it }.takeIf { it >= 0 }?.let { return it }
        return defaultIndex
    }

    fun codeOf(stored: String?): String = codes[indexOf(stored)]
}

internal object ChoicePreferences {
    val DATE_TIME = ChoiceSpec(
        GalleryPreferences.DATE_TIME_FORMAT,
        listOf(DateTimeDisplay.SYSTEM, DateTimeDisplay.SHORT_DATE, DateTimeDisplay.DATE_AND_TIME),
        listOf(setOf("Padrão do sistema", "Padrao do sistema"), setOf("Data curta"), setOf("Data e hora"))
    )
    val LOADING_PRIORITY = ChoiceSpec(
        GalleryPreferences.LOADING_PRIORITY,
        listOf(LoadingPriority.SPEED, LoadingPriority.QUALITY, LoadingPriority.BALANCED),
        listOf(setOf("Velocidade"), setOf("Qualidade"), setOf("Equilibrado"))
    )
    val FILE_THUMBNAIL = ChoiceSpec(
        GalleryPreferences.FILE_THUMB_STYLE,
        listOf(ThumbnailStyle.DEFAULT, ThumbnailStyle.SQUARE, ThumbnailStyle.ROUNDED),
        listOf(setOf("Padrão", "Padrao"), setOf("Quadrado"), setOf("Cantos arredondados"))
    )
    val FOLDER_THUMBNAIL = ChoiceSpec(
        GalleryPreferences.FOLDER_THUMB_STYLE,
        listOf(ThumbnailStyle.SQUARE, ThumbnailStyle.ROUNDED, ThumbnailStyle.CIRCULAR),
        listOf(setOf("Quadrado"), setOf("Cantos arredondados"), setOf("Circular")),
        defaultIndex = 1
    )
    val ROTATION = ChoiceSpec(
        GalleryPreferences.ROTATION_CRITERION,
        listOf("system", "portrait", "landscape", "sensor"),
        listOf(setOf("Padrão do sistema", "Padrao do sistema"), setOf("Retrato"), setOf("Paisagem"), setOf("Sensor"))
    )

    fun index(context: Context, spec: ChoiceSpec): Int =
        spec.indexOf(GalleryPreferences.prefs(context).getString(spec.key, null))

    fun code(context: Context, spec: ChoiceSpec): String = spec.codes[index(context, spec)]

    fun set(context: Context, spec: ChoiceSpec, index: Int) {
        GalleryPreferences.prefs(context).edit().putString(spec.key, spec.codes[index.coerceIn(spec.codes.indices)]).apply()
    }
}

/** "Formato de data e hora" for every date the app shows. */
internal object DateTimeDisplay {
    const val SYSTEM = "system"
    const val SHORT_DATE = "short"
    const val DATE_AND_TIME = "date_time"

    fun format(context: Context, epochMs: Long): String = formatter(ChoicePreferences.code(context, ChoicePreferences.DATE_TIME)).format(Date(epochMs))

    fun formatter(code: String): DateFormat = when (code) {
        SHORT_DATE -> DateFormat.getDateInstance(DateFormat.SHORT)
        DATE_AND_TIME -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
}

/**
 * "Prioridade de carregamento": speed keeps thumbnails at the size they are shown, quality decodes
 * them sharper, and balanced sits in between.
 */
internal object LoadingPriority {
    const val SPEED = "speed"
    const val QUALITY = "quality"
    const val BALANCED = "balanced"

    fun thumbnailScale(code: String): Float = when (code) {
        QUALITY -> 1.5f
        BALANCED -> 1.25f
        else -> 1f
    }

    fun thumbnailScale(context: Context): Float = thumbnailScale(ChoicePreferences.code(context, ChoicePreferences.LOADING_PRIORITY))

    /** Thumbnails decode at most at this size, whatever the priority. */
    const val MAX_THUMBNAIL_PX = 1024

    fun thumbnailSize(basePx: Int, scale: Float): Int = (basePx * scale).toInt().coerceIn(1, maxOf(basePx, MAX_THUMBNAIL_PX))
}

/** Corner style for media thumbnails ("Estilo da miniatura de arquivos") and album covers. */
internal object ThumbnailStyle {
    const val DEFAULT = "default"
    const val SQUARE = "square"
    const val ROUNDED = "rounded"
    const val CIRCULAR = "circular"

    /** Corner radius in dp; [defaultDp] is the look the app had before the option existed. */
    fun fileCornerDp(code: String, defaultDp: Int): Int = when (code) {
        SQUARE -> 0
        ROUNDED -> 14
        else -> defaultDp
    }

    fun folderCornerDp(code: String): Int = when (code) {
        SQUARE -> 0
        CIRCULAR -> 40
        else -> 8
    }
}

/** The thumbnail options read together, so a grid can tell when one of them changed. */
internal data class ThumbnailDisplay(
    val cropSquare: Boolean,
    val cornerDp: Int,
    val animateGif: Boolean,
    val sizeScale: Float
) {
    companion object {
        fun read(context: Context, defaultCornerDp: Int): ThumbnailDisplay = ThumbnailDisplay(
            cropSquare = GalleryPreferences.flag(context, GalleryPreferences.CROP_SQUARE_THUMBNAILS),
            cornerDp = ThumbnailStyle.fileCornerDp(ChoicePreferences.code(context, ChoicePreferences.FILE_THUMBNAIL), defaultCornerDp),
            animateGif = GalleryPreferences.flag(context, GalleryPreferences.ANIMATE_GIF_THUMBNAILS),
            sizeScale = LoadingPriority.thumbnailScale(context)
        )
    }
}
