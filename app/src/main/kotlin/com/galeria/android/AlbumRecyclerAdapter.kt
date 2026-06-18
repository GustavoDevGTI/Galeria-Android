package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
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

class AlbumRecyclerAdapter(
    private val context: Context,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<AlbumRecyclerAdapter.Holder>() {
    interface Callbacks {
        fun onAlbumClick(position: Int)
        fun onAlbumLongClick(view: View, position: Int): Boolean
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val allAlbums = ArrayList<AlbumItem>()
    private val visibleAlbums = ArrayList<AlbumItem>()
    private val selectedKeys = HashSet<String>()
    private var filter = ""
    private var selectionMode = false

    fun submit(albums: List<AlbumItem>, query: String? = filter) {
        allAlbums.clear()
        allAlbums.addAll(albums)
        selectedKeys.retainAll(albums.mapTo(HashSet()) { it.key })
        applyFilter(query)
    }

    fun applyFilter(query: String?) {
        filter = query?.trim()?.lowercase(Locale.US).orEmpty()
        visibleAlbums.clear()
        for (album in allAlbums) {
            if (filter.isEmpty() || album.name.lowercase(Locale.US).contains(filter)) {
                visibleAlbums.add(album)
            }
        }
        notifyDataSetChanged()
    }

    fun getCount(): Int = visibleAlbums.size

    fun getItem(position: Int): AlbumItem = visibleAlbums[position]

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) {
            selectedKeys.clear()
        }
        notifyDataSetChanged()
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun toggleSelection(position: Int) {
        if (position !in visibleAlbums.indices) return
        val key = visibleAlbums[position].key
        if (!selectedKeys.add(key)) {
            selectedKeys.remove(key)
        }
        notifyDataSetChanged()
    }

    fun selectPosition(position: Int) {
        if (position in visibleAlbums.indices) {
            selectedKeys.add(visibleAlbums[position].key)
            notifyDataSetChanged()
        }
    }

    fun selectAllVisible() {
        for (album in visibleAlbums) {
            selectedKeys.add(album.key)
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedKeys.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun allVisibleSelected(): Boolean = visibleAlbums.isNotEmpty() && selectedKeys.size >= visibleAlbums.size

    fun selectedCount(): Int = selectedKeys.size

    fun selectedAlbums(): List<AlbumItem> = visibleAlbums.filter { selectedKeys.contains(it.key) }

    fun visibleAlbumsSnapshot(): List<AlbumItem> = ArrayList(visibleAlbums)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 12))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val thumb = SquareFrameLayout(context)
        val cover = SquareImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(Ui.surface(context), folderRadius(), context)
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
        val nameParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 48)).apply {
            topMargin = Ui.dp(context, 8)
        }
        item.addView(name, nameParams)
        return Holder(item, cover, name, check)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val album = visibleAlbums[position]
        val selected = selectedKeys.contains(album.key)
        holder.itemView.alpha = if (selected) 0.78f else 1f
        holder.itemView.scaleX = if (selected) 0.94f else 1f
        holder.itemView.scaleY = if (selected) 0.94f else 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            holder.itemView.translationZ = if (selected) -Ui.dp(context, 2).toFloat() else 0f
        }
        holder.check.visibility = if (selectionMode || selected) View.VISIBLE else View.GONE
        holder.check.text = if (selected) "\u2713" else ""
        holder.name.text = "${album.name} (${album.count})"
        holder.name.setTextColor(Ui.text(context))
        holder.cover.background = Ui.rounded(Ui.surface(context), folderRadius(), context)
        holder.cover.setImageBitmap(null)
        album.cover?.let { cover ->
            holder.cover.tag = cover.uri
            val key = cover.uri.toString()
            val cached = coverCache.get(key)
            if (cached != null) {
                holder.cover.setImageBitmap(cached)
            } else {
                loadThumbnail(cover.uri, holder.cover)
            }
        }
        holder.itemView.setOnClickListener { callbacks.onAlbumClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { callbacks.onAlbumLongClick(it, holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = visibleAlbums.size

    private fun loadThumbnail(uri: Uri, target: SquareImageView) {
        val key = uri.toString()
        if (!loadingKeys.add(key)) {
            return
        }
        executor.execute {
            try {
                val bitmap = readThumbnail(uri)
                if (bitmap != null) {
                    coverCache.put(key, bitmap)
                }
                target.post {
                    if (uri == target.tag) {
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
                    ThumbnailUtils.createVideoThumbnail(File(uri.path!!), Size(420, 420), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(uri.path ?: return null, MediaStoreCompat.MINI_KIND)
                }
            } catch (_: Exception) {
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.contentResolver.loadThumbnail(uri, Size(420, 420), null)
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

    private fun folderRadius(): Int {
        val style = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .getString("folder_thumb_style", "Cantos arredondados")
        return when (style) {
            "Quadrado" -> 0
            "Circular" -> 40
            else -> 8
        }
    }

    class Holder(
        itemView: View,
        val cover: SquareImageView,
        val name: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private object MediaStoreCompat {
        @Suppress("DEPRECATION")
        const val MINI_KIND: Int = android.provider.MediaStore.Video.Thumbnails.MINI_KIND
    }

    companion object {
        private val coverCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val loadingKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    }
}
