package com.galeria.android

import android.content.SharedPreferences

/** One option of the bottom bar / options panel: a unique icon, its label and what it does. */
internal data class BrowseAction(
    val id: String,
    val icon: Int,
    val label: String,
    val description: String = label,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val run: () -> Unit
)

/**
 * The options are split between the bottom bar (shortcuts the user picked) and the panel that
 * replaced the three-dot menu (everything else). Dragging moves an option between them; inside the
 * panel only the order changes, so no option ever disappears.
 */
internal object BrowseLayoutRules {
    const val MAX_BAR = 5
    const val MIN_BAR = 1

    enum class Result { DONE, BAR_FULL, BAR_MIN, UNCHANGED }
    data class Layout(val bar: List<String>, val order: List<String>)

    fun parse(value: String?): List<String>? =
        value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()

    fun serialize(ids: List<String>): String = ids.joinToString(",")

    /** Saved order first (known ids only), then options added by newer versions in their default place. */
    fun order(saved: List<String>?, defaults: List<String>): List<String> {
        val known = defaults.toSet()
        val kept = saved.orEmpty().filter { it in known }
        return kept + defaults.filterNot { it in kept }
    }

    fun bar(saved: List<String>?, defaults: List<String>, known: Collection<String>): List<String> {
        val ids = (saved ?: defaults).filter { it in known }.take(MAX_BAR)
        return ids.ifEmpty { defaults.filter { it in known }.take(MAX_BAR) }
    }

    /** Options shown in the panel: every available one that is not on the bar, in the saved order. */
    fun panel(layout: Layout, available: Set<String>): List<String> =
        layout.order.filter { it in available && it !in layout.bar }

    /** Panel to bar, at [index] among the bar's options. */
    fun addToBar(layout: Layout, id: String, index: Int): Pair<Layout, Result> {
        if (id in layout.bar) return moveInBar(layout, id, index)
        if (layout.bar.size >= MAX_BAR) return layout to Result.BAR_FULL
        val bar = layout.bar.toMutableList().apply { add(index.coerceIn(0, size), id) }
        return layout.copy(bar = bar) to Result.DONE
    }

    /** Bar to panel, placed before [beforeId] in the panel (or at the end). */
    fun removeFromBar(layout: Layout, id: String, beforeId: String?): Pair<Layout, Result> {
        if (id !in layout.bar) return movePanel(layout, id, beforeId)
        if (layout.bar.size <= MIN_BAR) return layout to Result.BAR_MIN
        val moved = movePanel(layout.copy(bar = layout.bar - id), id, beforeId).first
        return moved to Result.DONE
    }

    fun moveInBar(layout: Layout, id: String, index: Int): Pair<Layout, Result> {
        val from = layout.bar.indexOf(id)
        if (from < 0) return layout to Result.UNCHANGED
        val bar = layout.bar.toMutableList().apply { removeAt(from) }
        val target = (if (index > from) index - 1 else index).coerceIn(0, bar.size)
        bar.add(target, id)
        return if (bar == layout.bar) layout to Result.UNCHANGED else layout.copy(bar = bar) to Result.DONE
    }

    /** Reorders the panel: [id] goes right before [beforeId]; null puts it at the end. */
    fun movePanel(layout: Layout, id: String, beforeId: String?): Pair<Layout, Result> {
        if (id == beforeId) return layout to Result.UNCHANGED
        val order = layout.order.toMutableList().apply { remove(id) }
        val target = beforeId?.let(order::indexOf)?.takeIf { it >= 0 } ?: order.size
        order.add(target, id)
        return if (order == layout.order) layout to Result.UNCHANGED else layout.copy(order = order) to Result.DONE
    }
}

/** Saves each screen's bar and panel order separately ("main" and "album"). */
internal class BrowseLayoutStore(
    private val prefs: SharedPreferences,
    private val screen: String,
    private val defaultBar: List<String>,
    private val defaultOrder: List<String>
) {
    fun load(): BrowseLayoutRules.Layout {
        val order = BrowseLayoutRules.order(BrowseLayoutRules.parse(prefs.getString(orderKey, null)), defaultOrder)
        val bar = BrowseLayoutRules.bar(BrowseLayoutRules.parse(prefs.getString(barKey, null)), defaultBar, order)
        return BrowseLayoutRules.Layout(bar, order)
    }

    fun save(layout: BrowseLayoutRules.Layout) {
        prefs.edit()
            .putString(barKey, BrowseLayoutRules.serialize(layout.bar))
            .putString(orderKey, BrowseLayoutRules.serialize(layout.order))
            .apply()
    }

    private val barKey get() = "browse_bar_$screen"
    private val orderKey get() = "browse_order_$screen"
}
