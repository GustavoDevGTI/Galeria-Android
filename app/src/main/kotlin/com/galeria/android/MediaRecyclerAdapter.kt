package com.galeria.android

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.CombinedLoadStates
import androidx.paging.PagingData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import java.util.Locale

class MediaRecyclerAdapter(
    private val context: Context,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<MediaRecyclerAdapter.Holder>() {
    interface Callbacks {
        fun onMediaClick(position: Int)
        fun onMediaLongClick(view: View, position: Int): Boolean
    }

    private val allItems = ArrayList<MediaItem>()
    private val visibleItems = ArrayList<MediaItem>()
    private val selectedUris = HashSet<String>()
    private var filter = ""
    private var listMode = false
    private var selectionMode = false
    private var pagingMode = false
    private val pagingDiffer = AsyncPagingDataDiffer(
        diffCallback = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem.id == newItem.id &&
                    oldItem.name == newItem.name &&
                    oldItem.mimeType == newItem.mimeType &&
                    oldItem.dateAdded == newItem.dateAdded &&
                    oldItem.size == newItem.size &&
                    oldItem.relativePath == newItem.relativePath &&
                    oldItem.albumKey == newItem.albumKey
        },
        updateCallback = object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) {
                if (pagingMode) notifyItemRangeInserted(position, count)
            }

            override fun onRemoved(position: Int, count: Int) {
                if (pagingMode) notifyItemRangeRemoved(position, count)
            }

            override fun onMoved(fromPosition: Int, toPosition: Int) {
                if (pagingMode) notifyItemMoved(fromPosition, toPosition)
            }

            override fun onChanged(position: Int, count: Int, payload: Any?) {
                if (pagingMode) notifyItemRangeChanged(position, count, payload)
            }
        },
        mainDispatcher = Dispatchers.Main,
        workerDispatcher = Dispatchers.Default
    )

    fun submit(nextItems: List<MediaItem>, query: String? = filter) {
        pagingMode = false
        allItems.clear()
        allItems.addAll(nextItems)
        selectedUris.retainAll(nextItems.mapTo(HashSet()) { it.uri.toString() })
        applyFilter(query)
    }

    suspend fun submitPagingData(data: PagingData<MediaItem>) {
        if (!pagingMode) {
            pagingMode = true
            allItems.clear()
            visibleItems.clear()
            selectedUris.clear()
            notifyDataSetChanged()
        }
        pagingDiffer.submitData(data)
    }

    fun addLoadStateListener(listener: (CombinedLoadStates) -> Unit) {
        pagingDiffer.addLoadStateListener(listener)
    }

    fun isPagingMode(): Boolean = pagingMode

    fun setListMode(listMode: Boolean) {
        if (this.listMode != listMode) {
            this.listMode = listMode
            notifyDataSetChanged()
        }
    }

    fun applyFilter(query: String?) {
        filter = query?.trim()?.lowercase(Locale.US).orEmpty()
        if (pagingMode) return
        visibleItems.clear()
        for (item in allItems) {
            if (filter.isEmpty() ||
                item.name.lowercase(Locale.US).contains(filter) ||
                item.relativePath.lowercase(Locale.US).contains(filter)
            ) {
                visibleItems.add(item)
            }
        }
        notifyDataSetChanged()
    }

    fun moveVisible(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in visibleItems.indices || toPosition !in visibleItems.indices || fromPosition == toPosition) {
            return false
        }
        val moved = visibleItems.removeAt(fromPosition)
        visibleItems.add(toPosition, moved)
        syncAllItemsFromVisible()
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun moveSelectedBlock(targetPosition: Int): Boolean {
        if (targetPosition !in visibleItems.indices || selectedUris.isEmpty()) {
            return false
        }
        val target = visibleItems[targetPosition]
        if (selectedUris.contains(target.uri.toString())) {
            return false
        }
        val moving = ArrayList<MediaItem>()
        val remaining = ArrayList<MediaItem>()
        for (item in visibleItems) {
            if (selectedUris.contains(item.uri.toString())) {
                moving.add(item)
            } else {
                remaining.add(item)
            }
        }
        var insertAt = remaining.indexOf(target)
        if (insertAt < 0) {
            return false
        }
        insertAt = minOf(remaining.size, insertAt + 1)
        remaining.addAll(insertAt, moving)
        visibleItems.clear()
        visibleItems.addAll(remaining)
        syncAllItemsFromVisible()
        notifyDataSetChanged()
        return true
    }

    private fun syncAllItemsFromVisible() {
        val hidden = allItems.filter { !visibleItems.contains(it) }
        allItems.clear()
        allItems.addAll(visibleItems)
        allItems.addAll(hidden)
    }

    fun setSelectionMode(selectionMode: Boolean) {
        this.selectionMode = selectionMode
        if (!selectionMode) {
            selectedUris.clear()
        }
        notifyDataSetChanged()
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun toggleSelection(position: Int) {
        val item = itemOrNull(position) ?: return
        val key = item.uri.toString()
        if (!selectedUris.add(key)) {
            selectedUris.remove(key)
        }
        notifyDataSetChanged()
    }

    fun selectPosition(position: Int) {
        itemOrNull(position)?.let {
            selectedUris.add(it.uri.toString())
            notifyDataSetChanged()
        }
    }

    fun isSelected(position: Int): Boolean =
        itemOrNull(position)?.let { selectedUris.contains(it.uri.toString()) } == true

    fun selectAllVisible() {
        for (item in currentVisibleItems()) {
            selectedUris.add(item.uri.toString())
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedUris.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun allVisibleSelected(): Boolean {
        val items = currentVisibleItems()
        return items.isNotEmpty() && items.all { selectedUris.contains(it.uri.toString()) }
    }

    fun selectedCount(): Int = selectedUris.size

    fun selectedItems(): List<MediaItem> = currentVisibleItems().filter { selectedUris.contains(it.uri.toString()) }

    fun currentOrder(): List<MediaItem> =
        if (pagingMode) ArrayList(pagingDiffer.snapshot().items) else ArrayList(allItems)

    fun getCount(): Int = if (pagingMode) pagingDiffer.itemCount else visibleItems.size

    fun getItem(position: Int): MediaItem = itemOrNull(position)
        ?: throw IndexOutOfBoundsException("Mídia ainda não carregada na posição $position")

    fun positionOf(uri: String): Int = currentVisibleItems().indexOfFirst { it.uri.toString() == uri }

    private fun itemOrNull(position: Int): MediaItem? {
        if (position < 0) return null
        return if (pagingMode) {
            if (position < pagingDiffer.itemCount) pagingDiffer.peek(position) else null
        } else {
            visibleItems.getOrNull(position)
        }
    }

    private fun currentVisibleItems(): List<MediaItem> =
        if (pagingMode) pagingDiffer.snapshot().items else visibleItems

    override fun getItemViewType(position: Int): Int = if (listMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val asList = viewType == 1
        val item = LinearLayout(context).apply {
            orientation = if (asList) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (asList) Gravity.CENTER_VERTICAL else Gravity.LEFT
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val thumb: FrameLayout = if (asList) {
            FrameLayout(context)
        } else {
            SquareFrameLayout(context)
        }
        thumb.setBackgroundColor(Color.TRANSPARENT)
        val image = ImageView(context)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setBackgroundColor(Color.TRANSPARENT)
        thumb.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val check = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = Ui.rounded(0x99000000.toInt(), 4, context)
        }
        val checkParams = FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = Ui.dp(context, 6)
            topMargin = Ui.dp(context, 6)
        }
        thumb.addView(check, checkParams)

        val thumbParams = if (asList) {
            LinearLayout.LayoutParams(Ui.dp(context, 82), Ui.dp(context, 82))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        item.addView(thumb, thumbParams)

        val name = TextView(context).apply {
            setTextColor(Ui.muted(context))
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.LEFT
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 2), 0)
        }
        if (asList) {
            item.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return Holder(item, image, name, check)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = if (pagingMode) pagingDiffer.getItem(position) else visibleItems[position]
        if (item == null) {
            holder.image.setImageDrawable(null)
            holder.name.text = ""
            holder.check.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            return
        }
        val selected = selectedUris.contains(item.uri.toString())
        holder.itemView.alpha = if (selected) 0.78f else 1f
        holder.itemView.scaleX = if (selected) 0.94f else 1f
        holder.itemView.scaleY = if (selected) 0.94f else 1f
        holder.itemView.translationZ = if (selected) -Ui.dp(context, 2).toFloat() else 0f
        holder.check.visibility = if (selectionMode || selected) View.VISIBLE else View.GONE
        holder.check.text = if (selected) "\u2713" else ""
        holder.name.text = item.name
        val key = "media:${item.uri}"
        holder.image.load(item.uri) {
            size(720, 720)
            memoryCacheKey(key)
            diskCacheKey(key)
            allowHardware(true)
            crossfade(false)
        }
        holder.itemView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) callbacks.onMediaClick(currentPosition)
        }
        holder.itemView.setOnLongClickListener {
            val currentPosition = holder.bindingAdapterPosition
            currentPosition != RecyclerView.NO_POSITION && callbacks.onMediaLongClick(it, currentPosition)
        }
    }

    override fun getItemCount(): Int = if (pagingMode) pagingDiffer.itemCount else visibleItems.size

    class Holder(
        itemView: View,
        val image: ImageView,
        val name: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(itemView)

}
