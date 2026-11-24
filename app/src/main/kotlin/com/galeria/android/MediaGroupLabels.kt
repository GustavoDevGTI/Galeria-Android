package com.galeria.android

import android.content.Context
import androidx.core.os.ConfigurationCompat
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The titles shown above each group of an album grouped by type, extension, date or size. */
object MediaGroupLabels {
    private const val MB = 1_000_000.0
    private const val GB = 1_000_000_000.0

    /** "3 GB", "2,5 GB", "900 MB", "1 MB": the floor of the size group, as listed in the group menu. */
    fun sizeLabel(groupIndex: Int, locale: Locale): String {
        val floor = AlbumMediaRules.SIZE_GROUP_FLOORS[groupIndex.coerceIn(0, AlbumMediaRules.SIZE_GROUP_FLOORS.lastIndex)]
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        return if (floor >= GB) "${number.format(floor / GB)} GB" else "${number.format(floor / MB)} MB"
    }

    fun label(context: Context, groupMode: String, key: String): String {
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        return when (groupMode) {
            AlbumMediaRules.GROUP_TYPE -> context.getString(if (key == "2_video") R.string.media_videos else R.string.media_images)
            AlbumMediaRules.GROUP_EXTENSION ->
                if (key.isEmpty()) context.getString(R.string.album_group_no_extension) else key.uppercase(Locale.ROOT)
            AlbumMediaRules.GROUP_DAY -> dateLabel(key, locale, includeDay = true)
            AlbumMediaRules.GROUP_MONTH -> dateLabel(key, locale, includeDay = false)
            AlbumMediaRules.GROUP_SIZE -> sizeLabel(key.toIntOrNull() ?: 0, locale)
            else -> key
        }
    }

    /** Keys are "yyyy-MM-dd", with day 00 when grouped by month. */
    private fun dateLabel(key: String, locale: Locale, includeDay: Boolean): String {
        val parts = key.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return key
        val date = Calendar.getInstance().apply {
            clear()
            set(parts[0], parts[1] - 1, if (includeDay) parts[2] else 1)
        }.time
        val text = if (includeDay) {
            DateFormat.getDateInstance(DateFormat.LONG, locale).format(date)
        } else {
            SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale).format(date)
        }
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
