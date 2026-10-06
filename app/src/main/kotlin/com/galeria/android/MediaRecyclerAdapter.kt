package com.galeria.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.LruCache
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
import coil3.size.Precision
import coil3.video.videoFrameMillis
import coil3.video.videoFrameOption
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MediaRecyclerAdapter(
    private val context: Context,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<MediaRecyclerAdapter.Holder>() {
    interface Callbacks {
        fun onMediaClick(position: Int)
        fun onMediaPreview(position: Int)
        fun onMediaLongClick(view: View, position: Int): Boolean
    }

    private val allItems = ArrayList<MediaItem>()
    private val visibleItems = ArrayList<MediaItem>()
    private val selectedUris = HashSet<String>()
    private var filter = ""
    private var listMode = false
    private var selectionMode = false
    private var pagingMode = false
    private var fastScrollPreview = false
    private var gridThumbnailSizePx = 360
    private var contentGeneration = MediaContentRevision.generation()
    @Volatile private var listVersion = 0
    private var pendingItems: List<MediaItem>? = null

    fun refreshChangedThumbnails() {
        val generation = MediaContentRevision.generation()
        if (contentGeneration == generation) return
        contentGeneration = generation
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_THUMBNAIL_SIZE)
    }

    init {
        setHasStableIds(true)
    }

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
        invalidateListRequest()
        val previous = visibleItems.toList()
        val previousCount = itemCount
        val wasPaging = pagingMode
        val source = nextItems.toList()
        pagingMode = false
        allItems.clear()
        allItems.addAll(source)
        selectedUris.retainAll(source.mapTo(HashSet()) { it.uri.toString() })
        filter = normalizedQuery(query)
        val next = filtered(source, filter)
        val diff = if (wasPaging) null else mediaDiff(previous, next)
        visibleItems.clear(); visibleItems.addAll(next)
        if (diff != null) diff.dispatchUpdatesTo(this) else {
            if (previousCount > 0) notifyItemRangeRemoved(0, previousCount)
            if (next.isNotEmpty()) notifyItemRangeInserted(0, next.size)
        }
    }

    /** Keeps complete selection/custom order, while filtering and diffing refreshes off the UI thread. */
    fun submitAsync(nextItems: List<MediaItem>, query: String? = filter, applied: () -> Unit = {}) {
        val version = ++listVersion
        val source = nextItems.toList()
        pendingItems = source
        filter = normalizedQuery(query)
        val requestedFilter = filter
        val previous = visibleItems.toList()
        val previousCount = itemCount
        val wasPaging = pagingMode
        listWorker.execute {
            if (version != listVersion) return@execute
            val next = filtered(source, requestedFilter)
            val diff = if (wasPaging) null else mediaDiff(previous, next)
            listHandler.post {
                if (version != listVersion) return@post
                pendingItems = null
                pagingMode = false
                allItems.clear(); allItems.addAll(source)
                visibleItems.clear(); visibleItems.addAll(next)
                selectedUris.retainAll(source.mapTo(HashSet()) { it.uri.toString() })
                if (diff != null) diff.dispatchUpdatesTo(this) else {
                    if (previousCount > 0) notifyItemRangeRemoved(0, previousCount)
                    if (next.isNotEmpty()) notifyItemRangeInserted(0, next.size)
                }
                applied()
            }
        }
    }

    fun applyFilterAsync(query: String?, applied: () -> Unit = {}) = submitAsync(pendingItems ?: allItems, query, applied)
    fun cancelPendingUpdates() = invalidateListRequest()
    private fun invalidateListRequest() { listVersion++; pendingItems = null }
    private fun normalizedQuery(query: String?) = query?.trim()?.lowercase(Locale.US).orEmpty()
    private fun filtered(items: List<MediaItem>, query: String) = items.filter {
        query.isEmpty() || it.name.lowercase(Locale.US).contains(query) || it.relativePath.lowercase(Locale.US).contains(query)
    }
    private fun mediaDiff(old: List<MediaItem>, next: List<MediaItem>) = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = next.size
        override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition].uri == next[newPosition].uri
        override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
            val first = old[oldPosition]; val second = next[newPosition]
            return first.id == second.id && first.name == second.name && first.mimeType == second.mimeType &&
                first.dateAdded == second.dateAdded && first.size == second.size && first.duration == second.duration &&
                first.relativePath == second.relativePath && first.albumKey == second.albumKey
        }
    })

    suspend fun submitPagingData(data: PagingData<MediaItem>) {
        invalidateListRequest()
        if (!pagingMode) {
            val previousCount = visibleItems.size
            pagingMode = true
            allItems.clear()
            visibleItems.clear()
            selectedUris.clear()
            if (previousCount > 0) notifyItemRangeRemoved(0, previousCount)
            // The differ may retain its previous page while selection used a
            // complete list. Restore that base before it dispatches the new diff.
            if (pagingDiffer.itemCount > 0) notifyItemRangeInserted(0, pagingDiffer.itemCount)
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
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_LAYOUT)
        }
    }

    fun applyFilter(query: String?) {
        if (pagingMode) return
        submit(pendingItems ?: allItems, query)
    }

    fun moveVisible(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in visibleItems.indices || toPosition !in visibleItems.indices || fromPosition == toPosition) {
            return false
        }
        val moved = visibleItems.removeAt(fromPosition)
        invalidateListRequest()
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
        invalidateListRequest()
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
        val changedStart = minOf(targetPosition, visibleItems.indexOfFirst { selectedUris.contains(it.uri.toString()) })
            .coerceAtLeast(0)
        val changedEnd = maxOf(targetPosition, visibleItems.indexOfLast { selectedUris.contains(it.uri.toString()) })
            .coerceAtLeast(changedStart)
        visibleItems.clear()
        visibleItems.addAll(remaining)
        syncAllItemsFromVisible()
        notifyItemRangeChanged(changedStart, changedEnd - changedStart + 1, PAYLOAD_POSITION)
        return true
    }

    private fun syncAllItemsFromVisible() {
        val visibleKeys = visibleItems.mapTo(HashSet()) { it.uri }
        val hidden = allItems.filter { it.uri !in visibleKeys }
        allItems.clear()
        allItems.addAll(visibleItems)
        allItems.addAll(hidden)
    }

    fun setSelectionMode(selectionMode: Boolean) {
        if (this.selectionMode == selectionMode && (selectionMode || selectedUris.isEmpty())) return
        this.selectionMode = selectionMode
        if (!selectionMode) {
            selectedUris.clear()
        }
        notifySelectionRangeChanged()
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun toggleSelection(position: Int) {
        val item = itemOrNull(position) ?: return
        val key = item.uri.toString()
        if (!selectedUris.add(key)) {
            selectedUris.remove(key)
        }
        notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    fun selectPosition(position: Int): Boolean {
        val item = itemOrNull(position) ?: return false
        if (!selectedUris.add(item.uri.toString())) return false
        notifyItemChanged(position, PAYLOAD_SELECTION)
        return true
    }

    fun setGridThumbnailSize(sizePx: Int) {
        val bounded = maxOf(96, sizePx)
        if (gridThumbnailSizePx == bounded) return
        gridThumbnailSizePx = bounded
        if (!listMode && itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_THUMBNAIL_SIZE)
    }

    fun setFastScrollPreview(enabled: Boolean) {
        if (fastScrollPreview == enabled) return
        fastScrollPreview = enabled
        if (!enabled && itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_THUMBNAIL_SIZE)
        }
    }

    fun isSelected(position: Int): Boolean =
        itemOrNull(position)?.let { selectedUris.contains(it.uri.toString()) } == true

    fun selectAllVisible() {
        for (item in currentVisibleItems()) {
            selectedUris.add(item.uri.toString())
        }
        notifySelectionRangeChanged()
    }

    fun clearSelection() {
        selectedUris.clear()
        selectionMode = false
        notifySelectionRangeChanged()
    }

    fun refreshSelectionVisuals() {
        notifySelectionRangeChanged()
    }

    private fun notifySelectionRangeChanged() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun allVisibleSelected(): Boolean {
        val items = currentVisibleItems()
        return items.isNotEmpty() && items.all { selectedUris.contains(it.uri.toString()) }
    }

    fun selectedCount(): Int = selectedUris.size

    fun selectedItems(): List<MediaItem> = currentVisibleItems().filter { selectedUris.contains(it.uri.toString()) }

    fun currentOrder(): List<MediaItem> =
        if (pagingMode) ArrayList(pagingDiffer.snapshot().items) else ArrayList(allItems)

    fun removeCompletedItems(uris: Collection<String>) {
        if (uris.isEmpty() || pagingMode) return
        invalidateListRequest()
        val keys = uris.mapTo(HashSet(), MediaIdentityRules::canonicalKey)
        fun removed(item: MediaItem) = MediaIdentityRules.canonicalKey(item.uri.toString()) in keys
        allItems.removeAll(::removed)
        for (index in visibleItems.lastIndex downTo 0) {
            if (removed(visibleItems[index])) {
                selectedUris.remove(visibleItems[index].uri.toString())
                visibleItems.removeAt(index)
                notifyItemRemoved(index)
            }
        }
    }

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

    override fun getItemId(position: Int): Long = itemOrNull(position)?.uri?.toString()?.hashCode()?.toLong()
        ?: RecyclerView.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val asList = viewType == 1
        val item = LinearLayout(context).apply {
            orientation = if (asList) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (asList) Gravity.CENTER_VERTICAL else Gravity.START
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
        thumb.background = Ui.rounded(Color.BLACK, MEDIA_CORNER_RADIUS_DP, context)
        thumb.clipToOutline = true
        val image = ImageView(context)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setBackgroundColor(Color.BLACK)
        thumb.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val mediaNameOverlay = TextView(context).apply {
            tag = TAG_MEDIA_NAME
            setTextColor(Color.WHITE)
            textSize = 10f
            setShadowLayer(Ui.dp(context, 2).toFloat(), 0f, Ui.dp(context, 1).toFloat(), 0xE6000000.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        val mediaDurationOverlay = TextView(context).apply {
            tag = TAG_MEDIA_DURATION
            setTextColor(Color.WHITE)
            textSize = 10f
            setShadowLayer(Ui.dp(context, 2).toFloat(), 0f, Ui.dp(context, 1).toFloat(), 0xE6000000.toInt())
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(Ui.dp(context, 7), 0, 0, 0)
        }
        if (!asList) {
            val metadataRow = LinearLayout(context).apply {
                tag = TAG_MEDIA_METADATA_ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
                addView(mediaNameOverlay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(mediaDurationOverlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            thumb.addView(
                metadataRow,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 24), Gravity.BOTTOM)
            )
        }

        val check = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = Ui.rounded(0x99000000.toInt(), 4, context)
        }
        val checkParams = FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = Ui.dp(context, 6)
            topMargin = Ui.dp(context, 6)
        }
        thumb.addView(check, checkParams)
        val preview = ImageView(context).apply {
            setImageResource(R.drawable.ic_view_media)
            setColorFilter(Color.WHITE)
            background = Ui.rounded(0x99000000.toInt(), 8, context)
            setPadding(Ui.dp(context, 5), Ui.dp(context, 5), Ui.dp(context, 5), Ui.dp(context, 5))
            isClickable = true
            isFocusable = true
        }
        thumb.addView(preview, FrameLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = Ui.dp(context, 5)
            bottomMargin = Ui.dp(context, 5)
        })

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
            gravity = Gravity.START
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 2), 0)
        }
        if (asList) {
            item.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return Holder(item, image, name, check, preview, mediaNameOverlay, mediaDurationOverlay)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = if (pagingMode) pagingDiffer.getItem(position) else visibleItems[position]
        if (item == null) {
            holder.thumbnailRequest?.cancel()
            holder.thumbnailRequest = null
            holder.thumbnailLoad?.dispose()
            holder.thumbnailLoad = null
            holder.image.setImageDrawable(null)
            holder.boundUri = null
            holder.name.text = ""
            holder.mediaNameOverlay.text = ""
            holder.mediaDurationOverlay.text = ""
            holder.mediaDurationOverlay.visibility = View.GONE
            holder.itemView.contentDescription = null
            holder.check.visibility = View.GONE
            holder.preview.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            return
        }
        bindItem(holder, item)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.contains(PAYLOAD_POSITION) || payloads.contains(PAYLOAD_LAYOUT)) {
            onBindViewHolder(holder, position)
            return
        }
        val item = itemOrNull(position) ?: return
        if (payloads.contains(PAYLOAD_SELECTION)) bindSelection(holder, item)
        if (payloads.contains(PAYLOAD_THUMBNAIL_SIZE)) {
            bindThumbnail(holder, item)
            bindVideoDuration(holder, item)
        }
    }

    private fun bindItem(holder: Holder, item: MediaItem) {
        bindSelection(holder, item)
        holder.name.text = item.name
        holder.mediaNameOverlay.text = item.name
        holder.itemView.contentDescription = item.name
        bindThumbnail(holder, item)
        bindVideoDuration(holder, item)
        holder.itemView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) callbacks.onMediaClick(currentPosition)
        }
        holder.itemView.setOnLongClickListener {
            val currentPosition = holder.bindingAdapterPosition
            currentPosition != RecyclerView.NO_POSITION && callbacks.onMediaLongClick(it, currentPosition)
        }
        holder.preview.contentDescription = context.getString(R.string.action_view_selected_media, item.name)
        holder.preview.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) callbacks.onMediaPreview(currentPosition)
        }
    }

    private fun bindSelection(holder: Holder, item: MediaItem) {
        val selected = selectedUris.contains(item.uri.toString())
        holder.itemView.alpha = if (selected) 0.78f else 1f
        holder.itemView.scaleX = if (selected) 0.94f else 1f
        holder.itemView.scaleY = if (selected) 0.94f else 1f
        holder.itemView.translationZ = if (selected) -Ui.dp(context, 2).toFloat() else 0f
        holder.check.visibility = if (selectionMode || selected) View.VISIBLE else View.GONE
        holder.preview.visibility = if (selectionMode && selected) View.VISIBLE else View.GONE
        holder.check.text = if (selected) "\u2713" else ""
        holder.check.setTextColor(if (selected) Ui.bg(context) else Color.WHITE)
        holder.check.background = GradientDrawable().apply {
            setColor(if (selected) Ui.accent(context) else 0x99000000.toInt())
            setStroke(Ui.dp(context, if (selected) 0 else 1), if (selected) Color.TRANSPARENT else 0xCCFFFFFF.toInt())
            cornerRadius = Ui.dp(context, 6).toFloat()
        }
    }

    private fun bindThumbnail(holder: Holder, item: MediaItem) {
        holder.thumbnailRequest?.cancel()
        holder.thumbnailRequest = null
        val normalSize = if (listMode) Ui.dp(context, 82) else gridThumbnailSizePx
        val requestSize = if (fastScrollPreview) minOf(normalSize, FAST_SCROLL_PREVIEW_SIZE_PX) else normalSize
        val uriKey = item.uri.toString()
        if (holder.boundUri != uriKey) {
            holder.thumbnailLoad?.dispose()
            holder.thumbnailLoad = null
            holder.image.setImageDrawable(null)
            holder.boundUri = uriKey
        }
        if (!item.isVideo()) {
            loadThumbnail(holder, item, requestSize, null)
            return
        }
        val saved = VideoThumbnailFrames.cachedThumbnail(context, item)
        if (saved != null) loadThumbnail(holder, item, requestSize, saved)
        else if (!fastScrollPreview) {
            holder.thumbnailRequest = VideoThumbnailFrames.request(context, item) { uri ->
                if (holder.boundUri == uriKey) loadThumbnail(holder, item, requestSize, uri)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.thumbnailRequest?.cancel()
        holder.thumbnailRequest = null
        holder.thumbnailLoad?.dispose()
        holder.thumbnailLoad = null
        holder.boundUri = null
        super.onViewRecycled(holder)
    }

    private fun loadThumbnail(holder: Holder, item: MediaItem, requestSize: Int, saved: android.net.Uri?) {
        val diskKey = "media:${MediaContentRevision.key(context, item.uri)}:${item.size}:${item.dateAdded}:${if (saved == null) "opening" else "saved"}"
        holder.thumbnailLoad = holder.image.load(saved ?: item.uri) {
            ImageRotation.configureRequest(context, item, this)
            size(requestSize, requestSize)
            precision(Precision.INEXACT)
            memoryCacheKey("$diskKey:$requestSize")
            diskCacheKey(diskKey)
            if (item.isVideo() && saved == null) {
                videoFrameMillis(0L)
                videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            allowHardware(true)
            crossfade(180)
        }
    }

    private fun bindVideoDuration(holder: Holder, item: MediaItem) {
        if (!item.isVideo()) {
            holder.mediaDurationOverlay.text = ""
            holder.mediaDurationOverlay.visibility = View.GONE
            return
        }
        holder.mediaDurationOverlay.visibility = View.VISIBLE
        val uriKey = item.uri.toString()
        val key = "${MediaContentRevision.key(context, item.uri)}:${item.size}:${item.dateAdded}"
        val knownDuration = item.duration.takeIf { it > 0L }
            ?: resolvedDurationCache.get(key)?.takeIf { it > 0L }
        if (knownDuration != null) {
            resolvedDurationCache.put(key, knownDuration)
            holder.mediaDurationOverlay.text = formatDuration(knownDuration)
            return
        }
        holder.mediaDurationOverlay.text = ""
        if (
            fastScrollPreview ||
            resolvedDurationCache.get(key) != null || pendingDurationRequests.size >= 48 ||
            pendingDurationRequests.putIfAbsent(key, true) != null
        ) return
        val appContext = context.applicationContext
        durationExecutor.execute {
            val resolved = resolveVideoDuration(appContext, item)
            resolvedDurationCache.put(key, resolved)
            if (resolved > 0L) {
                runCatching { GalleryCatalogStore.saveResolvedDuration(appContext, uriKey, resolved) }
            }
            pendingDurationRequests.remove(key)
            holder.itemView.post {
                if (holder.boundUri == uriKey) {
                    holder.mediaDurationOverlay.text = if (resolved > 0L) formatDuration(resolved) else ""
                }
            }
        }
    }

    private fun resolveVideoDuration(context: Context, item: MediaItem): Long {
        val queried = runCatching {
            context.contentResolver.query(
                item.uri,
                arrayOf(MediaStore.Video.VideoColumns.DURATION),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (queried > 0L) return queried
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    override fun getItemCount(): Int = if (pagingMode) pagingDiffer.itemCount else visibleItems.size

    class Holder(
        itemView: View,
        val image: ImageView,
        val name: TextView,
        val check: TextView,
        val preview: ImageView,
        val mediaNameOverlay: TextView,
        val mediaDurationOverlay: TextView
    ) : RecyclerView.ViewHolder(itemView) {
        var boundUri: String? = null
        var thumbnailRequest: VideoThumbnailFrames.Request? = null
        var thumbnailLoad: coil3.request.Disposable? = null
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_THUMBNAIL_SIZE = "thumbnail_size"
        const val PAYLOAD_POSITION = "position"
        const val PAYLOAD_LAYOUT = "layout"
        const val FAST_SCROLL_PREVIEW_SIZE_PX = 128
        const val MEDIA_CORNER_RADIUS_DP = 5
        const val TAG_MEDIA_NAME = "media_overlay_name"
        const val TAG_MEDIA_DURATION = "media_overlay_duration"
        const val TAG_MEDIA_METADATA_ROW = "media_metadata_row"
        val resolvedDurationCache = LruCache<String, Long>(2048)
        val listWorker = Executors.newSingleThreadExecutor()
        val listHandler = Handler(Looper.getMainLooper())
        val pendingDurationRequests = ConcurrentHashMap<String, Boolean>()
        val durationExecutor = Executors.newFixedThreadPool(2)
    }

}
