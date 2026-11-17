package com.galeria.android

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** Dragging albums on the main screen: pinned ones only among themselves, the others among themselves. */
internal object AlbumReorderRules {
    fun sameGroup(fromKey: String, toKey: String, pinnedKeys: Set<String>): Boolean =
        (fromKey in pinnedKeys) == (toKey in pinnedKeys)

    fun savedOrder(value: String?): List<String> = BrowseLayoutRules.parse(value).orEmpty()
}

/**
 * Holding an album selects it; moving the finger without letting go drags it to a new place.
 * The first drag switches the sort to "Ordem personalizada" and the order is saved on release.
 */
internal fun MainActivity.installAlbumReorder() {
    var moved = false
    val helper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = current.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from !in 0 until adapter.getCount() || to !in 0 until adapter.getCount()) return false
            return AlbumReorderRules.sameGroup(adapter.getItem(from).key, adapter.getItem(to).key, catalogController.pinnedKeys)
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            if (!canDropOver(recyclerView, viewHolder, target)) return false
            val changed = adapter.moveAlbum(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            if (changed) {
                moved = true
                Haptics.perform(viewHolder.itemView, Haptics.Effect.TICK)
            }
            return changed
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (!moved) return
            moved = false
            saveCustomAlbumOrder()
        }
    })
    helper.attachToRecyclerView(albumGrid)
    albumReorderHelper = helper
}

/** Starts dragging the album under [view]; nothing while a search filters the list. */
internal fun MainActivity.startAlbumDrag(view: View) {
    if (searchInput.text.isNotBlank()) return
    val holder = albumGrid.findContainingViewHolder(view) ?: return
    albumReorderHelper?.startDrag(holder)
}

internal fun MainActivity.saveCustomAlbumOrder() {
    prefs.edit().putString(GalleryPreferences.ALBUM_CUSTOM_ORDER,
        BrowseLayoutRules.serialize(adapter.allAlbumsSnapshot().map { it.key })).apply()
    if (sortMode != AlbumRules.SORT_CUSTOM) {
        sortMode = AlbumRules.SORT_CUSTOM
        saveSorting()
        Ui.toast(this, getString(R.string.main_custom_order_enabled))
    }
}
