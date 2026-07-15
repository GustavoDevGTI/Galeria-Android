package com.galeria.android

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import java.io.File

class HiddenFileAdapter(private val context: Context) : BaseAdapter() {
    private val files = ArrayList<File>()

    fun submit(nextFiles: Array<File>?) {
        files.clear()
        nextFiles
            ?.filter { it.isFile && it.name != ".nomedia" }
            ?.sortedByDescending { it.lastModified() }
            ?.let(files::addAll)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = files.size

    override fun getItem(position: Int): File = files[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val frame: FrameLayout
        val holder: Holder
        if (convertView == null) {
            frame = FrameLayout(context).apply {
                setBackgroundColor(Ui.BG)
                setPadding(Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2))
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Ui.SURFACE)
            }
            frame.addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val name = TextView(context).apply {
                setTextColor(Ui.TEXT)
                textSize = 11f
                gravity = Gravity.BOTTOM
                setBackgroundColor(0x99000000.toInt())
                Ui.setPadding(this, 5, 3, 5, 3)
            }
            frame.addView(
                name,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(context, 34),
                    Gravity.BOTTOM
                )
            )
            holder = Holder(image, name)
            frame.tag = holder
        } else {
            frame = convertView as FrameLayout
            holder = frame.tag as Holder
        }

        val file = getItem(position)
        val key = "${file.absolutePath}:${file.lastModified()}"
        holder.name.text = file.name
        holder.image.load(file) {
            size(720, 720)
            memoryCacheKey(key)
            diskCacheKey(key)
            allowHardware(true)
            crossfade(false)
        }
        return frame
    }

    private class Holder(val image: ImageView, val name: TextView)
}
