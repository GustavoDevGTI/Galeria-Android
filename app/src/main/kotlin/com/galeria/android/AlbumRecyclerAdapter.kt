package com.galeria.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import java.util.Locale

class AlbumRecyclerAdapter(
    private val context: Context,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<AlbumRecyclerAdapter.Holder>() {
    interface Callbacks {
        fun onAlbumClick(position: Int)
        fun onAlbumLongClick(view: View, position: Int): Boolean
    }

    private val allAlbums = ArrayList<AlbumItem>()
    private val visibleAlbums = ArrayList<AlbumItem>()
    private val selectedKeys = HashSet<String>()
    private var filter = ""
    private var selectionMode = false
    private var coverSizePx = 320
    private var coverRevision = 0L

    init {
        setHasStableIds(true)
    }

    fun setCoverSize(sizePx: Int) {
        val bounded = maxOf(96, sizePx)
        if (coverSizePx == bounded) return
        coverSizePx = bounded
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_COVER)
    }

    fun refreshVisibleCovers() {
        coverRevision++
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_COVER)
    }

    fun submit(albums: List<AlbumItem>, query: String? = filter) {
        allAlbums.clear()
        allAlbums.addAll(albums)
        selectedKeys.retainAll(albums.mapTo(HashSet()) { it.key })
        applyFilter(query)
    }

    fun applyFilter(query: String?) {
        filter = query?.trim()?.lowercase(Locale.US).orEmpty()
        val previous = visibleAlbums.toList()
        val next = allAlbums.filter { album ->
            filter.isEmpty() || album.name.lowercase(Locale.US).contains(filter)
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].key == next[newItemPosition].key

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                sameContent(previous[oldItemPosition], next[newItemPosition])
        })
        visibleAlbums.clear()
        visibleAlbums.addAll(next)
        diff.dispatchUpdatesTo(this)
    }

    fun getCount(): Int = visibleAlbums.size

    fun getItem(position: Int): AlbumItem = visibleAlbums[position]

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) {
            selectedKeys.clear()
        }
        notifySelectionRange()
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun toggleSelection(position: Int) {
        if (position !in visibleAlbums.indices) return
        val key = visibleAlbums[position].key
        if (!selectedKeys.add(key)) {
            selectedKeys.remove(key)
        }
        notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    fun selectPosition(position: Int) {
        if (position in visibleAlbums.indices) {
            selectedKeys.add(visibleAlbums[position].key)
            notifyItemChanged(position, PAYLOAD_SELECTION)
        }
    }

    fun selectAllVisible() {
        for (album in visibleAlbums) {
            selectedKeys.add(album.key)
        }
        notifySelectionRange()
    }

    fun clearSelection() {
        selectedKeys.clear()
        selectionMode = false
        notifySelectionRange()
    }

    fun allVisibleSelected(): Boolean = visibleAlbums.isNotEmpty() && selectedKeys.size >= visibleAlbums.size

    fun selectedCount(): Int = selectedKeys.size

    fun selectedAlbums(): List<AlbumItem> = visibleAlbums.filter { selectedKeys.contains(it.key) }

    fun visibleAlbumsSnapshot(): List<AlbumItem> = ArrayList(visibleAlbums)

    fun allAlbumsSnapshot(): List<AlbumItem> = ArrayList(allAlbums)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 6))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val thumb = SquareFrameLayout(context)
        val cover = SquareImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(Color.BLACK, folderRadius(), context)
            clipToOutline = true
        }
        thumb.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        item.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val name = TextView(context).apply {
            setTextColor(Ui.text(context))
            textSize = 14f
            gravity = Gravity.LEFT
            maxLines = 2
            includeFontPadding = true
        }
        val nameParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)).apply {
            topMargin = Ui.dp(context, 6)
        }
        item.addView(name, nameParams)
        return Holder(item, cover, name, check)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val album = visibleAlbums[position]
        bindSelection(holder, album)
        holder.name.text = "${album.name} (${album.count})"
        holder.name.setTextColor(Ui.text(context))
        bindCover(holder, album)
        holder.itemView.setOnClickListener { callbacks.onAlbumClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { callbacks.onAlbumLongClick(it, holder.bindingAdapterPosition) }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val album = visibleAlbums[position]
        if (payloads.contains(PAYLOAD_SELECTION)) bindSelection(holder, album)
        if (payloads.contains(PAYLOAD_COVER)) bindCover(holder, album)
    }

    override fun getItemId(position: Int): Long = visibleAlbums[position].key.hashCode().toLong()

    private fun bindSelection(holder: Holder, album: AlbumItem) {
        val selected = selectedKeys.contains(album.key)
        holder.itemView.alpha = if (selected) 0.78f else 1f
        holder.itemView.scaleX = if (selected) 0.94f else 1f
        holder.itemView.scaleY = if (selected) 0.94f else 1f
        holder.itemView.translationZ = if (selected) -Ui.dp(context, 2).toFloat() else 0f
        holder.check.visibility = if (selectionMode || selected) View.VISIBLE else View.GONE
        holder.check.text = if (selected) "\u2713" else ""
        holder.check.setTextColor(if (selected) Ui.bg(context) else Color.WHITE)
        holder.check.background = GradientDrawable().apply {
            setColor(if (selected) Ui.accent(context) else 0x99000000.toInt())
            setStroke(Ui.dp(context, if (selected) 0 else 1), if (selected) Color.TRANSPARENT else 0xCCFFFFFF.toInt())
            cornerRadius = Ui.dp(context, 6).toFloat()
        }
    }

    private fun bindCover(holder: Holder, album: AlbumItem) {
        holder.cover.background = Ui.rounded(Color.BLACK, folderRadius(), context)
        val cover = album.cover
        if (cover == null) {
            holder.cover.setImageDrawable(null)
        } else {
            val diskKey = "album:${cover.uri}:$coverRevision"
            val memoryKey = "$diskKey:$coverSizePx"
            holder.cover.load(cover.uri) {
                size(coverSizePx, coverSizePx)
                precision(Precision.INEXACT)
                memoryCacheKey(memoryKey)
                diskCacheKey(diskKey)
                allowHardware(true)
                crossfade(180)
            }
        }
    }

    override fun getItemCount(): Int = visibleAlbums.size

    private fun folderRadius(): Int {
        val style = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .getString("folder_thumb_style", "Cantos arredondados")
        return when (style) {
            "Quadrado" -> 0
            "Circular" -> 40
            else -> 8
        }
    }

    private fun notifySelectionRange() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    private fun sameContent(first: AlbumItem, second: AlbumItem): Boolean =
        first.name == second.name &&
            first.count == second.count &&
            first.cover?.uri == second.cover?.uri &&
            first.latestDate == second.latestDate &&
            first.firstDate == second.firstDate &&
            first.totalSize == second.totalSize &&
            first.path == second.path

    class Holder(
        itemView: View,
        val cover: SquareImageView,
        val name: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_COVER = "cover"
    }

}
