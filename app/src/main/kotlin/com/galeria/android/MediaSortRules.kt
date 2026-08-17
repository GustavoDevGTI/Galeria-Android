package com.galeria.android

import java.util.Locale

object MediaSortRules {
    const val SORT_CUSTOM = "custom"
    const val SORT_DATE = "date"
    const val SORT_NAME = "name"
    const val SORT_SIZE = "size"
    const val SORT_DURATION = "duration"
    const val SORT_TYPE = "type"

    data class Key(
        val uri: String,
        val name: String,
        val date: Long,
        val size: Long,
        val duration: Long,
        val mimeType: String
    )

    fun <T> comparator(
        mode: String,
        descending: Boolean,
        customOrder: List<String>,
        keyOf: (T) -> Key
    ): Comparator<T> {
        val customPositions = customOrder.withIndex().associate { it.value to it.index }
        return Comparator { first, second ->
            val firstKey = keyOf(first)
            val secondKey = keyOf(second)
            val primary = if (mode == SORT_CUSTOM) {
                compareCustom(firstKey, secondKey, customPositions)
            } else {
                val comparison = compareByMode(firstKey, secondKey, mode)
                if (descending) -comparison else comparison
            }
            if (primary != 0) primary else stableComparison(firstKey, secondKey)
        }
    }

    fun <T> sort(
        items: MutableList<T>,
        mode: String,
        descending: Boolean,
        customOrder: List<String>,
        keyOf: (T) -> Key
    ) {
        items.sortWith(comparator(mode, descending, customOrder, keyOf))
    }

    private fun compareCustom(first: Key, second: Key, customPositions: Map<String, Int>): Int {
        val firstPosition = customPositions[first.uri]
        val secondPosition = customPositions[second.uri]
        if (firstPosition != null || secondPosition != null) {
            if (firstPosition == null) return 1
            if (secondPosition == null) return -1
            val positionComparison = firstPosition.compareTo(secondPosition)
            if (positionComparison != 0) return positionComparison
        }
        return second.date.compareTo(first.date)
    }

    private fun compareByMode(first: Key, second: Key, mode: String): Int = when (mode) {
        SORT_NAME -> normalized(first.name).compareTo(normalized(second.name))
        SORT_SIZE -> first.size.compareTo(second.size)
        SORT_DURATION -> first.duration.compareTo(second.duration)
        SORT_TYPE -> typeKey(first).compareTo(typeKey(second))
        else -> first.date.compareTo(second.date)
    }

    private fun stableComparison(first: Key, second: Key): Int {
        val nameComparison = normalized(first.name).compareTo(normalized(second.name))
        return if (nameComparison != 0) nameComparison else first.uri.compareTo(second.uri)
    }

    private fun typeKey(key: Key): String {
        val extension = key.name.substringAfterLast('.', "")
        return "${normalized(key.mimeType)}|${normalized(extension)}"
    }

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
}
