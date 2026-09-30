package com.galeria.android

import android.app.Activity
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class VideoEditActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sourceUri: Uri
    private lateinit var player: ExoPlayer
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var saveButton: android.widget.ImageButton
    private lateinit var previewButton: android.widget.ImageButton
    private lateinit var timeline: VideoTimelineView
    private lateinit var timelineBinding: VideoTimelineBinding
    private var durationMs = 0L
    private var saving = false
    private val previewTick = object : Runnable {
        override fun run() {
            if (!::player.isInitialized || durationMs <= 0L) return
            if (player.isPlaying && player.currentPosition >= selectedRange().last) player.pause()
            if (player.isPlaying) handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        sourceUri = Uri.parse(intent.getStringExtra("uri").orEmpty())
        val retriever = MediaMetadataRetriever()
        durationMs = try {
            retriever.setDataSource(this, sourceUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
        if (durationMs <= 0L) {
            Ui.toast(this, "Não foi possível obter a duração deste vídeo.")
            finish()
            return
        }
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(PlayerMediaItem.fromUri(sourceUri))
            prepare()
        }
        buildLayout()
        updateRange()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@VideoEditActivity, 12), Ui.dp(this@VideoEditActivity, 6), Ui.dp(this@VideoEditActivity, 12), Ui.dp(this@VideoEditActivity, 8))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        header.addView(EditorUi.button(this, R.drawable.ic_back, "Voltar") { finish() }, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        header.addView(TextView(this).apply {
            text = "Cortar vídeo"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        saveButton = EditorUi.button(this, R.drawable.ic_check, "Salvar cópia") { saveTrimmedVideo() }
        header.addView(saveButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        root.addView(header)

        root.addView(PlayerView(this).apply {
            player = this@VideoEditActivity.player
            useController = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@VideoEditActivity, 20), Ui.dp(this@VideoEditActivity, 14), Ui.dp(this@VideoEditActivity, 20), Ui.dp(this@VideoEditActivity, 14))
        }
        controls.addView(TextView(this).apply {
            text = "Selecione o início e o fim. O corte preserva as trilhas suportadas e salva uma cópia."
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
        })
        val playbackTime = rangeLabel()
        timeline = VideoTimelineView(this)
        controls.addView(playbackTime)
        controls.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)))
        timelineBinding = VideoTimelineBinding(timeline).apply {
            onPosition = { position, duration -> playbackTime.text = "${formatTime(position)} / ${formatTime(duration)}" }
            onScrubbing = { active -> if (active) handler.removeCallbacks(previewTick) else handler.post(previewTick) }
            bind(player, sourceUri)
        }
        startLabel = rangeLabel()
        endLabel = rangeLabel()
        startSeek = SeekBar(this).apply { max = 1000; progress = 0; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); thumbTintList = progressTintList }
        endSeek = SeekBar(this).apply { max = 1000; progress = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); thumbTintList = progressTintList }
        controls.addView(startLabel)
        controls.addView(startSeek)
        controls.addView(endLabel)
        controls.addView(endSeek)
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val minimumProgress = ((VideoTrimRules.minimumDuration(durationMs) * 1000L + durationMs - 1L) / durationMs)
                    .toInt().coerceIn(1, 1000)
                if (seekBar === startSeek) {
                    startSeek.progress = progress.coerceAtMost(1000 - minimumProgress)
                    endSeek.progress = endSeek.progress.coerceAtLeast(startSeek.progress + minimumProgress)
                } else {
                    endSeek.progress = progress.coerceAtLeast(startSeek.progress + minimumProgress)
                }
                player.pause()
                player.seekTo(if (seekBar === startSeek) selectedRange().first else selectedRange().last)
                updateRange()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        startSeek.setOnSeekBarChangeListener(listener)
        endSeek.setOnSeekBarChangeListener(listener)
        previewButton = EditorUi.button(this, R.drawable.ic_play, "Prévia") { previewSelection() }
        controls.addView(previewButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)))
        root.addView(controls)
        setContentView(root)
    }

    private fun rangeLabel() = TextView(this).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(0, Ui.dp(this@VideoEditActivity, 10), 0, 0)
    }

    private fun selectedRange(): LongRange = VideoTrimRules.range(durationMs, startSeek.progress, endSeek.progress)

    private fun updateRange() {
        val range = selectedRange()
        startLabel.text = "Início  ${formatTime(range.first)}"
        endLabel.text = "Fim  ${formatTime(range.last)}"
        timeline.setSelectedRange(range)
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000L
        return "%02d:%02d:%02d".format(seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
    }

    private fun previewSelection() {
        val range = selectedRange()
        player.seekTo(range.first)
        player.play()
        handler.removeCallbacks(previewTick)
        handler.post(previewTick)
    }

    private fun saveTrimmedVideo() {
        if (saving) return
        saving = true
        saveButton.isEnabled = false
        saveButton.contentDescription = "Salvando…"
        player.pause()
        val range = selectedRange()
        val name = intent.getStringExtra("name").orEmpty()
        executor.execute {
            val result = runCatching { VideoTrimExporter.export(this, sourceUri, name, range.first, range.last) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                saving = false
                saveButton.isEnabled = true
                saveButton.contentDescription = "Salvar cópia"
                result.onSuccess {
                    Ui.toast(this, "Vídeo cortado salvo em Galeria Editada.")
                    finish()
                }.onFailure {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Não foi possível cortar")
                        .setMessage("${it.message ?: "Formato não suportado neste aparelho."}\n\nO arquivo original não foi alterado.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::timelineBinding.isInitialized) timelineBinding.suspend()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        if (::timelineBinding.isInitialized) timelineBinding.unbind()
        handler.removeCallbacks(previewTick)
        if (::player.isInitialized) player.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::timelineBinding.isInitialized) timelineBinding.resumeUpdates()
    }

}
