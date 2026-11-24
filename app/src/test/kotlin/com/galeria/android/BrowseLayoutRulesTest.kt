package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLayoutRulesTest {
    private val defaults = listOf("sort", "organize", "hidden", "settings", "filter", "spacing", "refresh", "theme")
    private val bar = listOf("sort", "organize", "hidden", "settings")
    private val layout = BrowseLayoutRules.Layout(bar, defaults)
    private val all = defaults.toSet()

    @Test
    fun barAndPanelSplitTheOptionsWithoutLosingAny() {
        val panel = BrowseLayoutRules.panel(layout, all)
        assertEquals(listOf("filter", "spacing", "refresh", "theme"), panel)
        assertEquals(all, (layout.bar + panel).toSet())
    }

    @Test
    fun draggingToTheBarMovesTheOptionOutOfThePanel() {
        // Four shortcuts at most, the fixed "Menu" button comes after them.
        val three = BrowseLayoutRules.Layout(listOf("sort", "organize", "settings"), defaults)
        val (next, result) = BrowseLayoutRules.addToBar(three, "theme", 1)
        assertEquals(BrowseLayoutRules.Result.DONE, result)
        assertEquals(listOf("sort", "theme", "organize", "settings"), next.bar)
        assertEquals(listOf("hidden", "filter", "spacing", "refresh"), BrowseLayoutRules.panel(next, all))
        val (full, blocked) = BrowseLayoutRules.addToBar(next, "filter", 0)
        assertEquals(BrowseLayoutRules.Result.BAR_FULL, blocked)
        assertEquals(next, full)
    }

    @Test
    fun draggingBackToThePanelRemovesTheShortcutButKeepsAtLeastOne() {
        val (next, result) = BrowseLayoutRules.removeFromBar(layout, "hidden", "spacing")
        assertEquals(BrowseLayoutRules.Result.DONE, result)
        assertEquals(listOf("sort", "organize", "settings"), next.bar)
        assertEquals(listOf("filter", "hidden", "spacing", "refresh", "theme"), BrowseLayoutRules.panel(next, all))
        val single = BrowseLayoutRules.Layout(listOf("settings"), defaults)
        assertEquals(BrowseLayoutRules.Result.BAR_MIN, BrowseLayoutRules.removeFromBar(single, "settings", null).second)
    }

    @Test
    fun insideThePanelAndTheBarOnlyTheOrderChanges() {
        val (panelMoved, _) = BrowseLayoutRules.movePanel(layout, "theme", "filter")
        assertEquals(listOf("theme", "filter", "spacing", "refresh"), BrowseLayoutRules.panel(panelMoved, all))
        assertEquals(bar, panelMoved.bar)
        val (barMoved, _) = BrowseLayoutRules.moveInBar(layout, "settings", 0)
        assertEquals(listOf("settings", "sort", "organize", "hidden"), barMoved.bar)
        assertEquals(BrowseLayoutRules.Result.UNCHANGED, BrowseLayoutRules.moveInBar(layout, "sort", 0).second)
    }

    @Test
    fun holdingAnOptionOverTheBarPreviewsWhereItLands() {
        val three = BrowseLayoutRules.Layout(listOf("sort", "organize", "settings"), defaults)
        // From the panel: room opens at the finger.
        assertEquals(listOf("sort", "theme", "organize", "settings"), BrowseLayoutRules.previewBar(three, "theme", false, 1))
        // A full bar has no room to open.
        assertEquals(null, BrowseLayoutRules.previewBar(layout, "theme", false, 1))
        // From the bar itself: the others close behind it.
        assertEquals(listOf("organize", "hidden", "sort", "settings"), BrowseLayoutRules.previewBar(layout, "sort", true, 3))
        assertEquals(bar, BrowseLayoutRules.previewBar(layout, "sort", true, 0))
        // Four buttons plus Menu across 500 px: centres every 100 px.
        assertEquals(listOf(50f, 150f, 250f, 350f, 450f), BrowseLayoutRules.slotCenters(5, 0f, 500f))
        assertEquals(emptyList<Float>(), BrowseLayoutRules.slotCenters(0, 0f, 500f))
    }

    @Test
    fun savedLayoutsSurviveNewAndRemovedOptions() {
        // Saved order first, an option that no longer exists is dropped, new options join in their default place.
        assertEquals(listOf("theme", "sort", "organize", "hidden", "settings", "filter", "spacing", "refresh"),
            BrowseLayoutRules.order(listOf("theme", "gone", "sort"), defaults))
        assertEquals(listOf("sort", "settings"), BrowseLayoutRules.bar(listOf("sort", "gone", "settings"), bar, all))
        assertEquals("A barra salva vazia volta ao padrão", bar, BrowseLayoutRules.bar(listOf("gone"), bar, all))
        assertEquals(listOf("a", "b"), BrowseLayoutRules.parse(" a, b ,a,"))
    }
}
