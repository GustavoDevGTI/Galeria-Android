package com.galeria.android

import android.content.ClipData
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal data class OrganizerAlbumDrag(val album: AlbumItem? = null, val slot: Int = -1)

@Suppress("DEPRECATION")
internal fun View.startOrganizerAlbumDrag(data: OrganizerAlbumDrag): Boolean {
    val clip = ClipData.newPlainText("album", data.album?.key.orEmpty())
    val shadow = View.DragShadowBuilder(this)
    return if (android.os.Build.VERSION.SDK_INT >= 24) startDragAndDrop(clip, shadow, data, 0)
    else startDrag(clip, shadow, data, 0)
}

/** Own scrolling area; its parent reserves space before measuring the wheel. */
internal class OrganizerAlbumDrawer(context: Context, private val controller: OrganizerController,
    private val createFolder: () -> Unit, private val toggle: (Boolean) -> Unit) : LinearLayout(context) {
    data class DropTarget(val albumKey: String, val centerX: Float, val centerY: Float)

    private val handle = Ui.label(context, "")
    private val list = RecyclerView(context)
    private var rows = emptyList<AlbumItem>()
    private var hoveredKey: String? = null
    private val rowsAdapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size + 1
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(LinearLayout(context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.row.removeAllViews()
            val album = rows.getOrNull(position - 1)
            val content = if (album == null) LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(context, 60)
                setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
                addView(Ui.title(context, "+", 30).apply { gravity = Gravity.CENTER; setTextColor(Ui.menuText(context)) },
                    LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46)))
                addView(Ui.label(context, "Nova pasta").apply { setTextColor(Ui.menuText(context)) }, LayoutParams(0, -2, 1f))
                tag = "organizer_new_folder"
            } else Ui.albumTargetRow(context, album).apply { tag = "organizer_drawer_${album.key}" }
            holder.album = album
            holder.content = content
            holder.count = album?.let {
                Ui.title(context, "", 14).apply {
                    tag = "organizer_drawer_count_${album.key}"
                    gravity = Gravity.CENTER
                    setPadding(Ui.dp(context, 6), 0, 0, 0)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    content.addView(this, LayoutParams(-2, -2))
                }
            }
            holder.row.addView(content, LayoutParams(-1, -2))
            bindStatus(holder)
            content.isClickable = true; content.isFocusable = true
            content.setOnClickListener {
                if (album == null) createFolder()
                else {
                    val slots = controller.destinations.indices.filter { it != OrganizerController.TRASH_SLOT }
                    val names = slots.map { i -> "${i + 1}. ${controller.destinations[i].name}" }
                    Ui.showPopupOptions(content, names) { controller.place(album, slots[names.indexOf(it)]) }
                }
            }
            content.setOnLongClickListener {
                if (album == null || controller.saving) false else content.startOrganizerAlbumDrag(OrganizerAlbumDrag(album))
            }
        }
    }
    init {
        orientation = VERTICAL; tag = "organizer_album_drawer"
        background = Ui.rounded(Ui.menuSurface(context), 16, context); clipToOutline = true
        handle.apply {
            tag = "organizer_drawer_handle"; setTextColor(Ui.menuText(context)); isClickable = true; isFocusable = true
            setOnClickListener { toggle(!controller.drawerOpen) }
            var down = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { down = event.rawY; true }
                    MotionEvent.ACTION_UP -> { val dy = event.rawY - down
                        if (kotlin.math.abs(dy) > Ui.dp(context, 20)) toggle(dy < 0) else view.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        addView(handle, LayoutParams(-1, Ui.dp(context, 44)))
        list.apply { tag = "organizer_drawer_list"; layoutManager = GridLayoutManager(context, 2)
            adapter = rowsAdapter; itemAnimator = null; clipToPadding = false; setPadding(0, 0, 0, Ui.dp(context, 8)) }
        addView(list, LayoutParams(-1, 0, 1f)); render()
    }
    fun render() {
        val next = controller.availableAlbums
        if (rows != next) { rows = next; rowsAdapter.notifyDataSetChanged() }
        refreshVisibleStatus()
        handle.text = if (controller.drawerOpen) "⌄  Álbuns" else "⌃  Álbuns"
        handle.contentDescription = if (controller.drawerOpen) "Fechar gaveta de álbuns" else "Abrir gaveta de álbuns"
    }

    fun targetAt(x: Float, y: Float): DropTarget? {
        if (x < list.left || x >= list.right || y < list.top || y >= list.bottom) return null
        val row = list.findChildViewUnder(x - list.left, y - list.top) ?: return null
        val position = list.getChildAdapterPosition(row)
        val album = rows.getOrNull(position - 1) ?: return null
        val top = maxOf(row.top, 0)
        val bottom = minOf(row.bottom, list.height)
        return DropTarget(album.key, list.left + row.x + row.width / 2f, list.top + (top + bottom) / 2f)
    }

    fun hover(albumKey: String?) {
        if (hoveredKey == albumKey) return
        hoveredKey = albumKey
        refreshVisibleStatus()
    }

    private fun refreshVisibleStatus() {
        repeat(list.childCount) { index ->
            (list.getChildViewHolder(list.getChildAt(index)) as? Holder)?.let(::bindStatus)
        }
    }

    private fun bindStatus(holder: Holder) {
        val album = holder.album ?: return
        val row = holder.content ?: return
        val count = controller.sentCount(album.key)
        val highlighted = album.key == hoveredKey || (count > 0 && album.key == controller.lastAlbum)
        val color = if (highlighted) OrganizerColors.highlightText(context) else Ui.menuText(context)
        row.background = when {
            highlighted -> Ui.rounded(OrganizerColors.highlight(context), 10, context)
            count > 0 -> Ui.rounded(Ui.blend(Ui.menuSurface(context), Ui.menuText(context), .16f), 10, context)
            else -> null
        }
        (row.getChildAt(1) as TextView).setTextColor(color)
        holder.count?.apply {
            text = count.toString()
            setTextColor(color)
            visibility = if (count > 0) VISIBLE else GONE
        }
        row.contentDescription = if (count == 0) album.name else
            "${album.name}, " + resources.getQuantityString(R.plurals.organizer_sent, count, count)
    }

    private class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        var album: AlbumItem? = null
        var content: LinearLayout? = null
        var count: TextView? = null
    }
}
