package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class HiddenFileAdapter(private val context: Context) : BaseAdapter() {
    private val executor = Executors.newFixedThreadPool(2)
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
        holder.image.tag = key
        val cached = thumbCache.get(key)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return frame
        }
        holder.image.setImageBitmap(null)
        executor.execute {
            val bitmap = thumbnail(file)
            if (bitmap != null) thumbCache.put(key, bitmap)
            holder.image.post {
                if (key == holder.image.tag) {
                    holder.image.setImageBitmap(bitmap)
                }
            }
        }
        return frame
    }

    private fun thumbnail(file: File): Bitmap? {
        val mime = FileDetailActivity.mimeFor(file)
        return try {
            if (mime.startsWith("video/")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(360, 360), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        file.absolutePath,
                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                    )
                }
            } else {
                val options = BitmapFactory.Options().apply { inSampleSize = 6 }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private class Holder(val image: ImageView, val name: TextView)

    companion object {
        private val thumbCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 24).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
}
