package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DetailActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mediaQueue = ArrayList<MediaItem>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var currentPlayer: ExoPlayer? = null
    private lateinit var content: FrameLayout
    private var activePage: FrameLayout? = null
    private lateinit var title: TextView
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private lateinit var speedButton: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var favoriteButton: ImageButton
    private lateinit var videoControls: LinearLayout
    private lateinit var timelineRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private var speedPopup: PopupWindow? = null
    private var currentVideoKey: String? = null
    private var pendingDeleteUri: Uri? = null
    private var pendingHiddenCopy: File? = null
    private var pendingMoveItem: MediaItem? = null
    private var pendingMoveFolder: String? = null
    private var pendingRotateItem: MediaItem? = null
    private var pendingPdfItem: MediaItem? = null
    private var videoPositionRestored = false
    private var userSeeking = false
    private var switchingItem = false
    private var shuffleMode = false
    private var currentIndex = 0
    private var downY = 0f
    private var downX = 0f
    private var playbackSpeed = 1f

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateTimeline()
            handler.postDelayed(this, 350)
        }
    }

    private val autoAdvanceRunnable = Runnable {
        if (shuffleMode && !switchingItem && mediaQueue.size > 1) {
            switchItem(1, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyOpenTransition(this)
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        val uri = Uri.parse(intent.getStringExtra("uri").orEmpty())
        val name = intent.getStringExtra("name")
        val mime = intent.getStringExtra("mime")
        val path = intent.getStringExtra("path")
        shuffleMode = intent.getBooleanExtra("shuffle_mode", false)
        prepareMediaQueue(uri, name, mime, path)
        buildLayout()
        loadCurrentItem()
    }

    private fun prepareMediaQueue(currentUri: Uri, name: String?, mime: String?, path: String?) {
        val albumKey = intent.getStringExtra("album_key")
        val includeHiddenFilesystem = intent.getBooleanExtra("include_hidden_filesystem", false)
        if (!albumKey.isNullOrEmpty()) {
            mediaQueue.addAll(applyCustomOrder(MediaStoreRepository.loadMediaForAlbum(this, albumKey, includeHiddenFilesystem), albumKey))
        }
        if (mediaQueue.isEmpty()) {
            mediaQueue.add(MediaItem(0, currentUri, name, mime, 0, 0, path, "media", "Mídia"))
        }
        if (shuffleMode) {
            shuffleQueueFrom(currentUri)
            return
        }
        currentIndex = mediaQueue.indexOfFirst { it.uri.toString() == currentUri.toString() }.takeIf { it >= 0 } ?: 0
    }

    private fun shuffleQueueFrom(currentUri: Uri) {
        val remaining = ArrayList<MediaItem>()
        var first: MediaItem? = null
        for (item in mediaQueue) {
            if (first == null && item.uri.toString() == currentUri.toString()) {
                first = item
            } else {
                remaining.add(item)
            }
        }
        remaining.shuffle(Random(System.nanoTime()))
        mediaQueue.clear()
        first?.let(mediaQueue::add)
        mediaQueue.addAll(remaining)
        currentIndex = 0
    }

    private fun applyCustomOrder(items: List<MediaItem>, albumKey: String): List<MediaItem> {
        val saved = prefs.getString("custom_order_$albumKey", "").orEmpty()
        if (saved.isEmpty()) return items
        val byUri = HashMap<String, MediaItem>()
        for (item in items) {
            byUri[item.uri.toString()] = item
        }
        val ordered = ArrayList<MediaItem>()
        val used = HashSet<String>()
        for (line in saved.split("\n")) {
            val item = byUri[line]
            if (item != null) {
                ordered.add(item)
                used.add(line)
            }
        }
        for (item in items) {
            if (!used.contains(item.uri.toString())) {
                ordered.add(item)
            }
        }
        return ordered
    }

    private fun buildLayout() {
        applyWindowSettings()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(Ui.dp(this@DetailActivity, 8), statusBarHeight() + Ui.dp(this@DetailActivity, 6), Ui.dp(this@DetailActivity, 10), Ui.dp(this@DetailActivity, 6))
        }
        val back = iconButton(R.drawable.ic_back, Ui.dp(this, 48)).apply {
            setOnClickListener { resetSpeedAndFinish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)))

        title = Ui.title(this, "", 17).apply {
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(this@DetailActivity, 4)
        }
        bar.addView(title, titleParams)

        val more = iconButton(R.drawable.ic_more_vertical, Ui.dp(this, 48)).apply {
            setOnClickListener { showMediaMenu(it) }
        }
        bar.addView(more, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, event -> handleSwipeOrTap(event) }
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(Ui.dp(this@DetailActivity, 14), Ui.dp(this@DetailActivity, 4), Ui.dp(this@DetailActivity, 14), navigationBarHeight() + Ui.dp(this@DetailActivity, 8))
        }
        videoControls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        playPauseButton = iconButton(R.drawable.ic_play, Ui.dp(this, 46)).apply {
            background = Ui.rounded(0x66000000, 24, this@DetailActivity)
            setOnClickListener { togglePlayback() }
        }
        videoControls.addView(playPauseButton, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)))
        bottom.addView(videoControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))

        timelineRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        currentTime = timeLabel("00:00")
        durationTime = timeLabel("00:00")
        seekBar = SeekBar(this).apply {
            max = 1000
            setPadding(Ui.dp(this@DetailActivity, 6), 0, Ui.dp(this@DetailActivity, 6), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val player = currentPlayer
                    if (fromUser && player != null && player.duration > 0) {
                        currentTime.text = formatTime(player.duration * progress / 1000L)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    userSeeking = true
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val player = currentPlayer
                    if (player != null && player.duration > 0 && bar != null) {
                        player.seekTo(player.duration * bar.progress / 1000L)
                    }
                    userSeeking = false
                }
            })
        }
        speedButton = timeLabel("1x").apply {
            gravity = Gravity.CENTER
            setOnClickListener { showSpeedPopup() }
        }
        timelineRow.addView(currentTime, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 34)))
        timelineRow.addView(seekBar, LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f))
        timelineRow.addView(durationTime, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 34)))
        timelineRow.addView(speedButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 34)))
        bottom.addView(timelineRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        favoriteButton = actionButton(R.drawable.ic_star).apply { setOnClickListener { toggleFavorite() } }
        val share = actionButton(R.drawable.ic_share).apply { setOnClickListener { shareCurrent() } }
        val trash = actionButton(R.drawable.ic_trash).apply { setOnClickListener { confirmDeleteCurrent() } }
        actions.addView(favoriteButton, actionParams())
        actions.addView(share, actionParams())
        actions.addView(trash, actionParams())
        bottom.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)))

        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun timeLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            gravity = Gravity.CENTER
        }

    private fun iconButton(icon: Int, touchSize: Int): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(Ui.dp(this@DetailActivity, 9), Ui.dp(this@DetailActivity, 9), Ui.dp(this@DetailActivity, 9), Ui.dp(this@DetailActivity, 9))
            minimumWidth = touchSize
            minimumHeight = touchSize
        }

    private fun actionButton(icon: Int): ImageButton =
        iconButton(icon, Ui.dp(this, 44)).apply {
            background = Ui.rounded(0x22000000, 22, this@DetailActivity)
        }

    private fun actionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 44)).apply {
            leftMargin = Ui.dp(this@DetailActivity, 16)
            rightMargin = Ui.dp(this@DetailActivity, 16)
        }

    private fun showMediaMenu(anchor: View) {
        Ui.showPopupOptions(
            anchor,
            listOf(
                "Ocultar",
                "Copiar",
                "Mover",
                "Definir como",
                "Alterar orientação",
                "Imprimir",
                "Redimensionar"
            )
        ) { selected ->
            when (selected) {
                "Ocultar" -> confirmHideCurrent()
                "Copiar" -> askFolderForCopyOrMove(true)
                "Mover" -> askFolderForCopyOrMove(false)
                "Definir como" -> setCurrentAsWallpaper()
                "Alterar orientação" -> rotateCurrentImage()
                "Imprimir" -> createPdfFromCurrentImage()
                "Redimensionar" -> openImageEditor()
            }
        }
    }

    private fun handleSwipeOrTap(event: MotionEvent): Boolean {
        if (switchingItem) return true
        if (event.action == MotionEvent.ACTION_DOWN) {
            downY = event.y
            downX = event.x
            return true
        }
        if (event.action != MotionEvent.ACTION_UP) return true
        val deltaY = event.y - downY
        val deltaX = event.x - downX
        val threshold = Ui.dp(this, 72)
        if (abs(deltaY) > threshold && abs(deltaY) > abs(deltaX)) {
            switchItem(if (deltaY > 0) -1 else 1, false)
            return true
        }
        if (abs(deltaX) > threshold && abs(deltaX) > abs(deltaY)) {
            switchItem(if (deltaX > 0) -1 else 1, true)
            return true
        }
        if (currentItem().isVideo()) {
            togglePlayback()
        }
        return true
    }

    private fun switchItem(direction: Int, horizontal: Boolean) {
        if (mediaQueue.size < 2) return
        switchingItem = true
        saveCurrentPosition()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
        val outgoingPlayer = currentPlayer
        currentPlayer = null
        currentVideoKey = null
        val outgoingPage = activePage
        currentIndex += direction
        if (currentIndex < 0) currentIndex = mediaQueue.size - 1
        if (currentIndex >= mediaQueue.size) currentIndex = 0
        val offset = if (horizontal) {
            if (content.width == 0) resources.displayMetrics.widthPixels else content.width
        } else {
            if (content.height == 0) resources.displayMetrics.heightPixels else content.height
        }
        val incomingPage = createCurrentPage()
        incomingPage.translationX = if (horizontal) {
            if (direction > 0) offset.toFloat() else -offset.toFloat()
        } else {
            0f
        }
        incomingPage.translationY = if (horizontal) {
            0f
        } else {
            if (direction > 0) offset.toFloat() else -offset.toFloat()
        }
        incomingPage.alpha = 0.98f
        content.addView(incomingPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activePage = incomingPage

        val interpolator = DecelerateInterpolator(1.4f)
        outgoingPage?.animate()
            ?.translationX(if (horizontal) if (direction > 0) -offset.toFloat() else offset.toFloat() else 0f)
            ?.translationY(if (horizontal) 0f else if (direction > 0) -offset.toFloat() else offset.toFloat())
            ?.alpha(0.92f)
            ?.setInterpolator(interpolator)
            ?.setDuration(245)
            ?.start()
        incomingPage.animate()
            .translationX(0f)
            .translationY(0f)
            .alpha(1f)
            .setInterpolator(interpolator)
            .setDuration(245)
            .withEndAction {
                outgoingPage?.let { content.removeView(it) }
                if (outgoingPlayer != null && outgoingPlayer !== currentPlayer) {
                    outgoingPlayer.release()
                }
                switchingItem = false
                scheduleShuffleAdvance()
            }
            .start()
    }

    private fun loadCurrentItem() {
        releasePlayer()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
        content.removeAllViews()
        activePage = createCurrentPage()
        content.addView(activePage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun createCurrentPage(): FrameLayout {
        val page = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val item = currentItem()
        title.text = item.name
        updateFavoriteButton()
        if (item.isVideo()) {
            showVideo(item, page)
        } else {
            showImage(item, page)
        }
        scheduleShuffleAdvance()
        return page
    }

    private fun showVideo(item: MediaItem, page: FrameLayout) {
        videoControls.visibility = View.VISIBLE
        timelineRow.visibility = View.VISIBLE
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        page.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadVideoPreview(item, preview)

        val playerView = PlayerView(this).apply {
            alpha = 0f
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        page.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val player = ExoPlayer.Builder(this).build()
        currentPlayer = player
        currentVideoKey = "video_pos_${item.uri.hashCode()}"
        videoPositionRestored = false
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
        player.repeatMode = if (!shuffleMode && prefs.getBoolean("loop_videos", false)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!shuffleMode && playbackState == Player.STATE_READY && !videoPositionRestored) {
                    restoreCurrentPosition()
                    videoPositionRestored = true
                }
                if (shuffleMode && player === currentPlayer && playbackState == Player.STATE_ENDED) {
                    handler.removeCallbacks(autoAdvanceRunnable)
                    handler.postDelayed(autoAdvanceRunnable, 250L)
                }
                if (!shuffleMode && player === currentPlayer && playbackState == Player.STATE_ENDED) {
                    saveCurrentPosition()
                }
                updatePlayPauseButton()
                updateTimeline()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
            }

            override fun onRenderedFirstFrame() {
                if (player !== currentPlayer) return
                playerView.animate().alpha(1f).setDuration(90).start()
                preview.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction { page.removeView(preview) }
                    .start()
            }
        })
        player.prepare()
        player.playWhenReady = shuffleMode || prefs.getBoolean("autoplay_videos", true)
        updateSpeedButton()
        updatePlayPauseButton()
        handler.post(progressUpdater)
    }

    private fun loadVideoPreview(item: MediaItem, target: ImageView) {
        executor.execute {
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(item.uri, Size(900, 900), null)
                } else {
                    val path = if (item.uri.scheme == "file") item.uri.path else null
                    if (path.isNullOrEmpty()) {
                        null
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                target.post { target.setImageBitmap(bitmap) }
            }
        }
    }

    private fun scheduleShuffleAdvance() {
        handler.removeCallbacks(autoAdvanceRunnable)
        if (!shuffleMode || switchingItem || mediaQueue.size < 2) return
        if (!currentItem().isVideo()) {
            handler.postDelayed(autoAdvanceRunnable, SHUFFLE_PHOTO_DELAY_MS)
        }
    }

    private fun showImage(item: MediaItem, page: FrameLayout) {
        videoControls.visibility = View.GONE
        timelineRow.visibility = View.GONE
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        page.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        executor.execute {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(item.uri, Size(1800, 1800), null)
                } else {
                    BitmapFactory.decodeStream(contentResolver.openInputStream(item.uri))
                }
                image.post { image.setImageBitmap(bitmap) }
            } catch (_: Exception) {
                image.post { Ui.toast(this, "Não foi possível abrir a imagem.") }
            }
        }
    }

    private fun togglePlayback() {
        val player = currentPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        val player = currentPlayer ?: return
        if (::playPauseButton.isInitialized) {
            playPauseButton.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun updateTimeline() {
        val player = currentPlayer ?: return
        if (!currentItem().isVideo()) return
        val duration = max(0L, player.duration)
        val position = max(0L, player.currentPosition)
        currentTime.text = formatTime(position)
        durationTime.text = if (duration > 0) formatTime(duration) else "00:00"
        if (!userSeeking && duration > 0) {
            seekBar.progress = min(1000L, position * 1000L / duration).toInt()
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = max(0L, millis / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    @Throws(Exception::class)
    private fun decodeBitmap(uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = max(1, sample)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalStateException("bitmap")
    }

    private fun compressFormat(item: MediaItem): Bitmap.CompressFormat {
        val mime = item.mimeType.lowercase(Locale.US)
        val name = item.name.lowercase(Locale.US)
        if (mime.contains("png") || name.endsWith(".png")) return Bitmap.CompressFormat.PNG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (mime.contains("webp") || name.endsWith(".webp"))) {
            return Bitmap.CompressFormat.WEBP_LOSSY
        }
        return Bitmap.CompressFormat.JPEG
    }

    private fun showSpeedPopup() {
        if (speedPopup?.isShowing == true) {
            speedPopup?.dismiss()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xDD111111.toInt(), 12, this@DetailActivity)
        }
        addSpeedOption(panel, "0,5x", 0.5f)
        addSpeedOption(panel, "1x", 1f)
        addSpeedOption(panel, "1,5x", 1.5f)
        addSpeedOption(panel, "2x", 2f)
        speedPopup = PopupWindow(panel, Ui.dp(this, 74), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            showAsDropDown(speedButton, -Ui.dp(this@DetailActivity, 10), -Ui.dp(this@DetailActivity, 184))
        }
    }

    private fun addSpeedOption(panel: LinearLayout, label: String, speed: Float) {
        val option = timeLabel(label).apply {
            textSize = 14f
            setBackgroundColor(if (abs(playbackSpeed - speed) < 0.01f) 0x55FFFFFF else Color.TRANSPARENT)
            setOnClickListener {
                playbackSpeed = speed
                currentPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
                updateSpeedButton()
                speedPopup?.dismiss()
            }
        }
        panel.addView(option, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)))
    }

    private fun updateSpeedButton() {
        if (!::speedButton.isInitialized) return
        speedButton.text = when {
            abs(playbackSpeed - 0.5f) < 0.01f -> "0,5x"
            abs(playbackSpeed - 1.5f) < 0.01f -> "1,5x"
            abs(playbackSpeed - 2f) < 0.01f -> "2x"
            else -> "1x"
        }
    }

    private fun toggleFavorite() {
        val item = currentItem()
        val favorites = HashSet(prefs.getStringSet("favorites", HashSet()) ?: HashSet())
        val key = item.uri.toString()
        if (favorites.contains(key)) {
            favorites.remove(key)
            Ui.toast(this, "Removido dos favoritos.")
        } else {
            favorites.add(key)
            Ui.toast(this, "Adicionado aos favoritos.")
        }
        prefs.edit().putStringSet("favorites", favorites).apply()
        updateFavoriteButton()
    }

    private fun updateFavoriteButton() {
        if (!::favoriteButton.isInitialized || mediaQueue.isEmpty()) return
        val favorites = prefs.getStringSet("favorites", HashSet()) ?: HashSet()
        favoriteButton.alpha = if (favorites.contains(currentItem().uri.toString())) 1f else 0.55f
    }

    private fun shareCurrent() {
        val item = currentItem()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Compartilhar"))
    }

    private fun confirmHideCurrent() {
        AlertDialog.Builder(this)
            .setTitle("Ocultar arquivo")
            .setMessage("O arquivo será copiado para a área oculta do app e removido da galeria pública.")
            .setPositiveButton("Ocultar") { _, _ -> hideCurrent() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun hideCurrent() {
        val item = currentItem()
        pendingHiddenCopy = MediaActions.copyToHidden(this, item)
        if (pendingHiddenCopy == null) {
            Ui.toast(this, "Não foi possível copiar para ocultos.")
            return
        }
        pendingDeleteUri = item.uri
        val result = MediaActions.requestPermanentDelete(this, item.uri, REQ_HIDE_DELETE)
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item ocultado.")
            removeDeletedItem()
        } else if (result == MediaActions.RESULT_FAILED) {
            pendingDeleteUri = null
            pendingHiddenCopy?.delete()
            pendingHiddenCopy = null
            requestFileManagementAccess()
        }
    }

    private fun askFolderForCopyOrMove(copy: Boolean) {
        val item = currentItem()
        val targets = availableAlbumNames(item)
        if (targets.isEmpty()) {
            Ui.toast(this, "Nenhum álbum disponível.")
            return
        }
        val labels = targets.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (copy) "Copiar para" else "Mover para")
            .setItems(labels) { _, which ->
                val folder = targets[which].second
                if (copy) {
                    copyCurrentToFolder(item, folder)
                } else {
                    moveCurrentToFolder(item, folder)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun copyCurrentToFolder(item: MediaItem, folder: String) {
        val result = MediaActions.copyToFolder(this, item, folder)
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item copiado.")
        } else {
            requestFileManagementAccess()
        }
    }

    private fun moveCurrentToFolder(item: MediaItem, folder: String) {
        pendingMoveItem = item
        pendingMoveFolder = folder
        val result = MediaActions.moveToFolder(this, item, folder)
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item movido.")
            removeDeletedItem()
        } else if (result == MediaActions.RESULT_NEEDS_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaActions.requestWrite(this, item.uri, REQ_MOVE_WRITE)
        } else {
            pendingMoveItem = null
            pendingMoveFolder = null
            requestFileManagementAccess()
        }
    }

    private fun availableAlbumNames(item: MediaItem): List<Pair<String, String>> {
        val names = LinkedHashMap<String, String>()
        val includeHiddenFilesystem = intent.getBooleanExtra("include_hidden_filesystem", false)
        for (album in MediaStoreRepository.loadAlbums(this, includeHiddenFilesystem)) {
            if (album.key == "all_media" || album.key == item.albumKey || album.name.isBlank()) continue
            val label = if (album.path.isNotBlank()) "${album.name} - ${album.path}" else album.name
            names.putIfAbsent(label, album.name)
        }
        return names.entries.map { it.key to it.value }
    }

    private fun setCurrentAsWallpaper() {
        if (currentItem().isVideo()) {
            Ui.toast(this, "Disponível apenas para imagens.")
            return
        }
        val item = currentItem()
        executor.execute {
            try {
                val bitmap = decodeBitmap(item.uri, 2600)
                WallpaperManager.getInstance(this).setBitmap(bitmap)
                runOnUiThread { Ui.toast(this, "Papel de parede atualizado.") }
            } catch (_: Exception) {
                runOnUiThread { Ui.toast(this, "Não foi possível definir como papel de parede.") }
            }
        }
    }

    private fun rotateCurrentImage() {
        if (currentItem().isVideo()) {
            Ui.toast(this, "Disponível apenas para imagens.")
            return
        }
        pendingRotateItem = currentItem()
        rotateImage(pendingRotateItem!!)
    }

    private fun rotateImage(item: MediaItem) {
        executor.execute {
            try {
                val original = decodeBitmap(item.uri, 2600)
                val matrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                contentResolver.openOutputStream(item.uri, "w").use { output ->
                    if (output == null) throw IllegalStateException("output")
                    rotated.compress(compressFormat(item), 94, output)
                }
                runOnUiThread {
                    Ui.toast(this, "Orientação alterada.")
                    loadCurrentItem()
                }
            } catch (_: SecurityException) {
                runOnUiThread { MediaActions.requestWrite(this, item.uri, REQ_ROTATE_WRITE) }
            } catch (_: Exception) {
                runOnUiThread { Ui.toast(this, "Não foi possível alterar a orientação.") }
            }
        }
    }

    private fun createPdfFromCurrentImage() {
        if (currentItem().isVideo()) {
            Ui.toast(this, "Disponível apenas para imagens.")
            return
        }
        pendingPdfItem = currentItem()
        var name = pendingPdfItem?.name.orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot > 0) name = name.substring(0, dot)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "$name.pdf")
        }
        startActivityForResult(intent, REQ_CREATE_PDF)
    }

    private fun writePdf(outputUri: Uri, item: MediaItem) {
        executor.execute {
            val document = PdfDocument()
            try {
                val bitmap = decodeBitmap(item.uri, 2400)
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                document.finishPage(page)
                contentResolver.openOutputStream(outputUri).use { output ->
                    if (output == null) throw IllegalStateException("output")
                    document.writeTo(output)
                }
                runOnUiThread { Ui.toast(this, "PDF criado.") }
            } catch (_: Exception) {
                runOnUiThread { Ui.toast(this, "Não foi possível criar o PDF.") }
            } finally {
                document.close()
            }
        }
    }

    private fun openImageEditor() {
        if (currentItem().isVideo()) {
            Ui.toast(this, "Disponível apenas para imagens.")
            return
        }
        val item = currentItem()
        val intent = Intent(this, ImageEditActivity::class.java).apply {
            putExtra("uri", item.uri.toString())
            putExtra("name", item.name)
            putExtra("mime", item.mimeType)
        }
        startActivity(intent)
    }

    private fun confirmDeleteCurrent() {
        AlertDialog.Builder(this)
            .setTitle("Excluir arquivo")
            .setMessage("Tem certeza que deseja excluir este arquivo?")
            .setPositiveButton("Excluir") { _, _ -> deleteCurrent() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteCurrent() {
        val item = currentItem()
        pendingDeleteUri = item.uri
        val result = MediaActions.requestPermanentDelete(this, item.uri, REQ_DELETE)
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item excluído.")
            removeDeletedItem()
        } else if (result == MediaActions.RESULT_FAILED) {
            pendingDeleteUri = null
            requestFileManagementAccess()
        }
    }

    private fun requestFileManagementAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permitir gerenciamento de arquivos")
                .setMessage("Para excluir, mover, copiar e criar arquivos no celular, ative o acesso total a arquivos para a Galeria.")
                .setPositiveButton("Permitir") { _, _ -> MediaActions.requestAllFilesAccess(this) }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            Ui.toast(this, "Não foi possível concluir a operação.")
        }
    }

    private fun removeDeletedItem() {
        if (mediaQueue.isEmpty()) {
            finish()
            return
        }
        mediaQueue.removeAt(currentIndex)
        if (mediaQueue.isEmpty()) {
            finish()
            return
        }
        if (currentIndex >= mediaQueue.size) currentIndex = mediaQueue.size - 1
        loadCurrentItem()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            val deleted = resultCode == RESULT_OK || (pendingDeleteUri != null && !MediaActions.mediaExists(this, pendingDeleteUri!!))
            pendingDeleteUri = null
            if (deleted) {
                Ui.toast(this, "Item excluído.")
                removeDeletedItem()
            } else {
                Ui.toast(this, "Exclusão cancelada.")
            }
        } else if (requestCode == REQ_HIDE_DELETE) {
            val deleted = resultCode == RESULT_OK || (pendingDeleteUri != null && !MediaActions.mediaExists(this, pendingDeleteUri!!))
            pendingDeleteUri = null
            if (deleted) {
                pendingHiddenCopy = null
                Ui.toast(this, "Item ocultado.")
                removeDeletedItem()
            } else {
                pendingHiddenCopy?.delete()
                pendingHiddenCopy = null
                Ui.toast(this, "Ocultação cancelada.")
            }
        } else if (requestCode == REQ_MOVE_WRITE) {
            val item = pendingMoveItem
            val folder = pendingMoveFolder
            if (resultCode == RESULT_OK && item != null && folder != null) {
                val result = MediaActions.moveToFolder(this, item, folder)
                Ui.toast(this, if (result == MediaActions.RESULT_DONE) "Item movido." else "Não foi possível mover.")
                if (result == MediaActions.RESULT_DONE) {
                    removeDeletedItem()
                }
            }
            pendingMoveItem = null
            pendingMoveFolder = null
        } else if (requestCode == REQ_ROTATE_WRITE) {
            val item = pendingRotateItem
            if (resultCode == RESULT_OK && item != null) {
                rotateImage(item)
            } else {
                Ui.toast(this, "Rotação cancelada.")
            }
        } else if (requestCode == REQ_CREATE_PDF) {
            val item = pendingPdfItem
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && item != null) {
                writePdf(uri, item)
            }
            pendingPdfItem = null
        }
    }

    private fun currentItem(): MediaItem = mediaQueue[currentIndex]

    private fun applyWindowSettings() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (prefs.getBoolean("fullscreen_max_brightness", false)) {
            val params = window.attributes
            params.screenBrightness = 1f
            window.attributes = params
        }
    }

    private fun saveCurrentPosition() {
        val key = currentVideoKey
        if (!shuffleMode && currentPlayer != null && key != null && prefs.getBoolean("remember_video_position", true)) {
            prefs.edit().putLong(key, rememberedVideoPosition()).apply()
        }
    }

    private fun rememberedVideoPosition(): Long {
        val player = currentPlayer ?: return 0L
        if (player.playbackState == Player.STATE_ENDED) return 0L
        val position = max(0L, player.currentPosition)
        val duration = player.duration
        if (duration > 0L && position >= duration - 750L) {
            return 0L
        }
        return position
    }

    private fun restoreCurrentPosition() {
        val key = currentVideoKey
        val player = currentPlayer
        if (player != null && key != null && prefs.getBoolean("remember_video_position", true)) {
            val savedPosition = prefs.getLong(key, 0L)
            if (savedPosition > 0L) {
                player.seekTo(savedPosition)
            }
        }
    }

    private fun releasePlayer() {
        currentPlayer?.release()
        currentPlayer = null
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        currentPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        releasePlayer()
        executor.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        resetSpeedAndFinish()
    }

    private fun resetSpeedAndFinish() {
        playbackSpeed = 1f
        finish()
        Ui.applyCloseTransition(this)
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    companion object {
        private const val REQ_DELETE = 31
        private const val REQ_HIDE_DELETE = 32
        private const val REQ_MOVE_WRITE = 33
        private const val REQ_ROTATE_WRITE = 34
        private const val REQ_CREATE_PDF = 35
        private const val SHUFFLE_PHOTO_DELAY_MS = 4200L
    }
}
