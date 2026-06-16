package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MediaRecyclerAdapter(
    private val context: Context,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<MediaRecyclerAdapter.Holder>() {
    interface Callbacks {
        fun onMediaClick(position: Int)
        fun onMediaLongClick(view: View, position: Int): Boolean
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val allItems = ArrayList<MediaItem>()
    private val visibleItems = ArrayList<MediaItem>()
    private val selectedUris = HashSet<String>()
    private var filter = ""
    private var listMode = false
    private var selectionMode = false

    fun submit(nextItems: List<MediaItem>) {
        allItems.clear()
        allItems.addAll(nextItems)
        selectedUris.retainAll(nextItems.mapTo(HashSet()) { it.uri.toString() })
        applyFilter(filter)
    }

    fun setListMode(listMode: Boolean) {
        if (this.listMode != listMode) {
            this.listMode = listMode
            notifyDataSetChanged()
        }
    }

    fun applyFilter(query: String?) {
        filter = query?.trim()?.lowercase(Locale.US).orEmpty()
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
        if (position !in visibleItems.indices) return
        val key = visibleItems[position].uri.toString()
        if (!selectedUris.add(key)) {
            selectedUris.remove(key)
        }
        notifyDataSetChanged()
    }

    fun selectPosition(position: Int) {
        if (position in visibleItems.indices) {
            selectedUris.add(visibleItems[position].uri.toString())
            notifyDataSetChanged()
        }
    }

    fun isSelected(position: Int): Boolean =
        position in visibleItems.indices && selectedUris.contains(visibleItems[position].uri.toString())

    fun selectAllVisible() {
        for (item in visibleItems) {
            selectedUris.add(item.uri.toString())
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedUris.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun allVisibleSelected(): Boolean = visibleItems.isNotEmpty() && selectedUris.size >= visibleItems.size

    fun selectedCount(): Int = selectedUris.size

    fun selectedItems(): List<MediaItem> = visibleItems.filter { selectedUris.contains(it.uri.toString()) }

    fun currentOrder(): List<MediaItem> = ArrayList(allItems)

    fun getCount(): Int = visibleItems.size

    fun getItem(position: Int): MediaItem = visibleItems[position]

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

        val badge = TextView(context).apply {
            text = "VIDEO"
            setTextColor(Ui.text(context))
            textSize = 10f
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
        }
        val badgeParams = FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 22)).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT
        }
        thumb.addView(badge, badgeParams)
        val thumbParams = if (asList) {
            LinearLayout.LayoutParams(Ui.dp(context, 82), Ui.dp(context, 82))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        item.addView(thumb, thumbParams)

        val name = TextView(context).apply {
            setTextColor(Ui.muted(context))
            textSize = if (asList) 14f else 11f
            isSingleLine = !asList
            maxLines = if (asList) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.LEFT
            setPadding(Ui.dp(context, if (asList) 12 else 2), Ui.dp(context, if (asList) 0 else 4), Ui.dp(context, 2), 0)
        }
        val nameParams = if (asList) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 24))
        }
        item.addView(name, nameParams)
        return Holder(item, image, badge, name, check)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = visibleItems[position]
        val selected = selectedUris.contains(item.uri.toString())
        holder.itemView.alpha = if (selected) 0.78f else 1f
        holder.itemView.scaleX = if (selected) 0.94f else 1f
        holder.itemView.scaleY = if (selected) 0.94f else 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            holder.itemView.translationZ = if (selected) -Ui.dp(context, 2).toFloat() else 0f
        }
        holder.check.visibility = if (selectionMode || selected) View.VISIBLE else View.GONE
        holder.check.text = if (selected) "\u2713" else ""
        holder.badge.visibility = if (item.isVideo()) View.VISIBLE else View.GONE
        holder.name.text = item.name
        holder.image.setImageBitmap(null)
        holder.image.tag = item.uri
        val cached = thumbCache.get(item.uri.toString())
        if (cached != null) {
            holder.image.setImageBitmap(cached)
        } else {
            loadThumbnail(item, holder.image)
        }
        holder.itemView.setOnClickListener { callbacks.onMediaClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { callbacks.onMediaLongClick(it, holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = visibleItems.size

    private fun loadThumbnail(item: MediaItem, target: ImageView) {
        val key = item.uri.toString()
        if (!loadingKeys.add(key)) return
        executor.execute {
            try {
                val bitmap = readThumbnail(item.uri)
                if (bitmap != null) thumbCache.put(key, bitmap)
                target.post {
                    if (item.uri == target.tag) {
                        target.setImageBitmap(bitmap)
                    }
                }
            } finally {
                loadingKeys.remove(key)
            }
        }
    }

    private fun readThumbnail(uri: Uri): Bitmap? {
        if (uri.scheme == "file" && isVideoFile(uri.path)) {
            try {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(File(uri.path!!), Size(360, 360), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(uri.path ?: return null, MediaStoreCompat.MINI_KIND)
                }
            } catch (_: Exception) {
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.contentResolver.loadThumbnail(uri, Size(360, 360), null)
            }
        } catch (_: Exception) {
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isVideoFile(path: String?): Boolean {
        if (path == null) return false
        val name = path.lowercase(Locale.US)
        return name.endsWith(".mp4") ||
            name.endsWith(".mkv") ||
            name.endsWith(".webm") ||
            name.endsWith(".mov") ||
            name.endsWith(".avi") ||
            name.endsWith(".3gp") ||
            name.endsWith(".m4v") ||
            name.endsWith(".ts")
    }

    class Holder(
        itemView: View,
        val image: ImageView,
        val badge: TextView,
        val name: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private object MediaStoreCompat {
        @Suppress("DEPRECATION")
        const val MINI_KIND: Int = android.provider.MediaStore.Video.Thumbnails.MINI_KIND
    }

    companion object {
        private val thumbCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val loadingKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    }
}
