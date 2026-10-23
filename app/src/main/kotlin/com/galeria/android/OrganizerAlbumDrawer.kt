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
    private val handle = Ui.label(context, "")
    private val list = RecyclerView(context)
    private var rows = emptyList<AlbumItem>()
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
            holder.row.addView(content, LayoutParams(-1, -2))
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
        handle.text = if (controller.drawerOpen) "⌄  Álbuns" else "⌃  Álbuns"
        handle.contentDescription = if (controller.drawerOpen) "Fechar gaveta de álbuns" else "Abrir gaveta de álbuns"
    }
    private class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)
}
