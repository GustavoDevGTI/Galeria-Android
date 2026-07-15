package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class FileDetailActivity : Activity() {
    private lateinit var file: File
    private lateinit var mimeType: String
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path").orEmpty())
        mimeType = mimeFor(file)
        buildLayout()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            Ui.setPadding(this, 10, 8, 10, 8)
        }
        val back = Ui.title(this, "Voltar", 16).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)))

        val title = Ui.title(this, file.name, 17)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (mimeType.startsWith("video/")) {
            val playerView = PlayerView(this).apply {
                setBackgroundColor(Color.BLACK)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            player = ExoPlayer.Builder(this).build().apply {
                setMediaItem(PlayerMediaItem.fromUri(Uri.fromFile(file)))
                playerView.player = this
                prepare()
                playWhenReady = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
                    .getBoolean("autoplay_videos", true)
            }
            content.addView(
                playerView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            }
            content.addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            Ui.setPadding(this, 10, 10, 10, 12)
        }
        val restore = Ui.button(this, "Restaurar").apply {
            setOnClickListener { restoreFile() }
        }
        actions.addView(restore, LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f))

        val delete = Ui.button(this, "Excluir").apply {
            setOnClickListener { confirmDelete() }
        }
        val deleteParams = LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f).apply {
            leftMargin = Ui.dp(this@FileDetailActivity, 8)
        }
        actions.addView(delete, deleteParams)
        root.addView(actions)
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun restoreFile() {
        val restored = MediaActions.restoreHiddenFile(this, file, mimeType)
        Ui.toast(this, if (restored) "Item restaurado." else "Não foi possível restaurar.")
        if (restored) finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Excluir oculto")
            .setMessage("Este arquivo oculto será apagado definitivamente.")
            .setPositiveButton("Excluir") { _, _ ->
                val deleted = file.delete()
                Ui.toast(this, if (deleted) "Item excluído." else "Não foi possível excluir.")
                if (deleted) finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        @JvmStatic
        fun mimeFor(file: File): String {
            val name = file.name
            val dot = name.lastIndexOf('.')
            val ext = if (dot >= 0 && dot + 1 < name.length) {
                name.substring(dot + 1).lowercase(Locale.US)
            } else {
                ""
            }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (mime != null) return mime
            if (ext == "mp4" || ext == "mkv" || ext == "webm" || ext == "3gp") {
                return "video/mp4"
            }
            return "image/jpeg"
        }
    }
}
