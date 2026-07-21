package com.galeria.android

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import coil3.SingletonImageLoader
import coil3.load
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import com.github.panpf.zoomimage.CoilZoomImageView
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class DetailActivity : ComponentActivity() {
    private data class ImageMetadata(
        val width: Int? = null,
        val height: Int? = null,
        val make: String? = null,
        val model: String? = null,
        val capturedAt: String? = null,
        val iso: String? = null,
        val aperture: String? = null,
        val exposure: String? = null,
        val focalLength: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    ) {
        val hasLocation: Boolean get() = latitude != null && longitude != null
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val videoPreviewExecutor = Executors.newSingleThreadExecutor()
    private val imagePreloadScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_IMAGE_PRELOADS)
    )
    private val imagePreloadJobs = LinkedHashMap<String, Job>()
    private val mediaQueue = ArrayList<MediaItem>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var currentPlayer: ExoPlayer? = null
    private lateinit var content: FrameLayout
    private var activePage: FrameLayout? = null
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private lateinit var speedButton: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var favoriteButton: ImageButton
    private lateinit var soundButton: ImageButton
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
    private var pendingRenameItem: MediaItem? = null
    private var pendingRenameName: String? = null
    private var videoPositionRestored = false
    private var userSeeking = false
    private var switchingItem = false
    private var shuffleMode = false
    private var presentationMode = false
    private var currentIndex = 0
    private var downY = 0f
    private var downX = 0f
    private var playbackSpeed = 1f
    private var videoMuted = false
    private var queueLoadGeneration = 0
    private var hudVisible = true
    private var shuffleSeed = 0L
    private var restoredVideoPositionMs: Long? = null
    private var restoredVideoPlayWhenReady: Boolean? = null
    private var restoredShuffleDelayMs: Long? = null
    private var shuffleAdvanceDeadlineMs = 0L
    private var playWhenReadyBeforePause: Boolean? = null
    private var dragPreviewPage: FrameLayout? = null
    private var dragHorizontal = true
    private var dragDirection = 0
    private var dragTargetIndex = -1
    private var dragDistance = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingSingleTap: Runnable? = null
    private var zoomed = false
    private val imageMetadataCache = HashMap<String, ImageMetadata>()
    private val gestureTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    @Volatile private var preloadGeneration = 0

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateTimeline()
            handler.postDelayed(this, 350)
        }
    }

    private val autoAdvanceRunnable = Runnable {
        shuffleAdvanceDeadlineMs = 0L
        if ((shuffleMode || presentationMode) && !switchingItem && mediaQueue.size > 1) {
            switchItem(1, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                resetSpeedAndFinish()
            }
        })
        Ui.applyOpenTransition(this)
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        val uri = Uri.parse(savedInstanceState?.getString(STATE_CURRENT_URI) ?: intent.getStringExtra("uri").orEmpty())
        val name = savedInstanceState?.getString(STATE_CURRENT_NAME) ?: intent.getStringExtra("name")
        val mime = savedInstanceState?.getString(STATE_CURRENT_MIME) ?: intent.getStringExtra("mime")
        val path = savedInstanceState?.getString(STATE_CURRENT_PATH) ?: intent.getStringExtra("path")
        shuffleMode = savedInstanceState?.getBoolean(STATE_SHUFFLE_MODE)
            ?: intent.getBooleanExtra("shuffle_mode", false)
        presentationMode = savedInstanceState?.getBoolean(STATE_PRESENTATION_MODE) ?: false
        shuffleSeed = savedInstanceState?.getLong(STATE_SHUFFLE_SEED) ?: System.nanoTime()
        if (savedInstanceState?.containsKey(STATE_VIDEO_POSITION) == true) {
            restoredVideoPositionMs = savedInstanceState.getLong(STATE_VIDEO_POSITION)
            restoredVideoPlayWhenReady = savedInstanceState.getBoolean(STATE_VIDEO_PLAY_WHEN_READY)
        }
        if (savedInstanceState?.containsKey(STATE_SHUFFLE_DELAY) == true) {
            restoredShuffleDelayMs = savedInstanceState.getLong(STATE_SHUFFLE_DELAY)
        }
        playbackSpeed = savedInstanceState?.getFloat(STATE_PLAYBACK_SPEED) ?: playbackSpeed
        videoMuted = savedInstanceState?.getBoolean(STATE_VIDEO_MUTED) ?: videoMuted
        prepareInitialMedia(uri, name, mime, path)
        buildLayout()
        loadCurrentItem()
        loadAlbumQueueAsync(uri)
    }

    private fun prepareInitialMedia(currentUri: Uri, name: String?, mime: String?, path: String?) {
        mediaQueue.add(MediaItem(0, currentUri, name, mime, 0, 0, path, "media", "Mídia"))
        currentIndex = 0
    }

    private fun loadAlbumQueueAsync(currentUri: Uri) {
        val albumKey = intent.getStringExtra("album_key")
        val includeHiddenFilesystem = intent.getBooleanExtra("include_hidden_filesystem", false)
        if (albumKey.isNullOrEmpty()) return
        val request = ++queueLoadGeneration
        executor.execute {
            val loaded = applyCustomOrder(
                MediaStoreRepository.loadMediaForAlbum(applicationContext, albumKey, includeHiddenFilesystem),
                albumKey
            )
            runOnUiThread {
                val available = if (presentationMode) loaded.filter { it.isImage() } else loaded
                if (request != queueLoadGeneration || isFinishing || available.isEmpty()) return@runOnUiThread
                mediaQueue.clear()
                mediaQueue.addAll(available)
                if (shuffleMode) {
                    shuffleQueueFrom(currentUri)
                } else {
                    currentIndex = mediaQueue.indexOfFirst { it.uri.toString() == currentUri.toString() }.takeIf { it >= 0 } ?: 0
                }
                scheduleAdjacentPreload()
                scheduleShuffleAdvance()
            }
        }
    }

    private fun shuffleQueueFrom(currentUri: Uri) {
        val byUri = mediaQueue.associateBy { it.uri.toString() }
        val restoredOrder = ViewerStateRules.shuffledFromCurrent(
            availableUris = byUri.keys.toList(),
            currentUri = currentUri.toString(),
            seed = shuffleSeed
        )
        mediaQueue.clear()
        restoredOrder.mapNotNullTo(mediaQueue) { byUri[it] }
        currentIndex = 0
    }

    private fun applyCustomOrder(items: List<MediaItem>, albumKey: String): List<MediaItem> {
        val saved = GalleryCatalogStore.migrateLegacyOrder(applicationContext, albumKey)
        if (saved.isEmpty()) return items
        val byUri = HashMap<String, MediaItem>()
        for (item in items) {
            byUri[item.uri.toString()] = item
        }
        val ordered = ArrayList<MediaItem>()
        val used = HashSet<String>()
        for (line in saved) {
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
        val navSideInset = navigationBarSideInset()
        val navBottomInset = navigationBarBottomInset()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(
                Ui.dp(this@DetailActivity, 8),
                statusBarHeight() + Ui.dp(this@DetailActivity, 6),
                navSideInset + Ui.dp(this@DetailActivity, 10),
                Ui.dp(this@DetailActivity, 6)
            )
        }
        val back = iconButton(R.drawable.ic_back, Ui.dp(this, 48)).apply {
            setOnClickListener { resetSpeedAndFinish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)))

        title = Ui.title(this, "", 17).apply {
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(this@DetailActivity, 4)
        }
        topBar.addView(title, titleParams)

        val more = iconButton(R.drawable.ic_more_vertical, Ui.dp(this, 48)).apply {
            contentDescription = "Mais opções"
            setOnClickListener { showMediaMenu(it) }
        }
        topBar.addView(more, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)))

        content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Visualizador de mídia"
            setOnTouchListener { _, event -> handleSwipeOrTap(event) }
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(
                Ui.dp(this@DetailActivity, 14),
                Ui.dp(this@DetailActivity, 4),
                navSideInset + Ui.dp(this@DetailActivity, 14),
                navBottomInset + Ui.dp(this@DetailActivity, 8)
            )
        }
        videoControls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        playPauseButton = iconButton(R.drawable.ic_play, Ui.dp(this, 46)).apply {
            background = Ui.rounded(0x66000000, 24, this@DetailActivity)
            setOnClickListener { togglePlayback() }
        }
        videoControls.addView(playPauseButton, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)))
        bottomBar.addView(videoControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))

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
        bottomBar.addView(timelineRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        favoriteButton = actionButton(R.drawable.ic_star).apply { setOnClickListener { toggleFavorite() } }
        val share = actionButton(R.drawable.ic_share).apply { setOnClickListener { shareCurrent() } }
        val trash = actionButton(R.drawable.ic_trash).apply { setOnClickListener { confirmDeleteCurrent() } }
        soundButton = actionButton(R.drawable.ic_volume_on).apply {
            contentDescription = "Desativar som"
            setOnClickListener { toggleVideoSound() }
        }
        actions.addView(favoriteButton, actionParams())
        actions.addView(share, actionParams())
        actions.addView(trash, actionParams())
        actions.addView(soundButton, actionParams())
        bottomBar.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)))

        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
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
        LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 44)).apply {
            leftMargin = Ui.dp(this@DetailActivity, 10)
            rightMargin = Ui.dp(this@DetailActivity, 10)
        }

    private fun showMediaMenu(anchor: View) {
        val item = currentItem()
        if (item.isImage()) {
            val key = item.uri.toString()
            val cached = imageMetadataCache[key]
            if (cached != null) {
                showResolvedMediaMenu(anchor, item, cached)
            } else {
                executor.execute {
                    val metadata = readImageMetadata(item.uri)
                    imageMetadataCache[key] = metadata
                    runOnUiThread {
                        if (!isFinishing && anchor.isAttachedToWindow && currentItem().uri == item.uri) {
                            showResolvedMediaMenu(anchor, item, metadata)
                        }
                    }
                }
            }
            return
        }
        showResolvedMediaMenu(anchor, item, null)
    }

    private fun showResolvedMediaMenu(anchor: View, item: MediaItem, imageMetadata: ImageMetadata?) {
        val loopEnabled = currentPlayer?.repeatMode == Player.REPEAT_MODE_ONE ||
            (!shuffleMode && prefs.getBoolean("loop_videos", false))
        Ui.showPopupOptions(
            anchor,
            ViewerMenuRules.options(
                isVideo = item.isVideo(),
                loopEnabled = loopEnabled,
                shuffleMode = shuffleMode || presentationMode,
                hasLocation = imageMetadata?.hasLocation == true
            ),
            widthDp = 216
        ) { selected ->
            when (selected) {
                ViewerMenuRules.RENAME -> askRenameCurrentImage()
                ViewerMenuRules.OPEN_WITH -> openCurrentWithAnotherApp()
                ViewerMenuRules.COPY_TO -> askFolderForCopyOrMove(true)
                ViewerMenuRules.MOVE_TO -> askFolderForCopyOrMove(false)
                ViewerMenuRules.HIDE -> confirmHideCurrent()
                ViewerMenuRules.INFORMATION -> if (item.isVideo()) {
                    showCurrentVideoInformation()
                } else {
                    showCurrentImageInformation()
                }
                ViewerMenuRules.ENABLE_LOOP,
                ViewerMenuRules.DISABLE_LOOP -> toggleVideoLoop()
                ViewerMenuRules.SET_AS -> setCurrentAsWallpaper()
                ViewerMenuRules.ROTATE -> rotateCurrentImage()
                ViewerMenuRules.EXPORT_PDF -> createPdfFromCurrentImage()
                ViewerMenuRules.RESIZE -> openImageEditor()
                ViewerMenuRules.SHOW_ON_MAP -> openCurrentImageOnMap()
                ViewerMenuRules.PRESENTATION -> startImagePresentation()
            }
        }
    }

    private fun openCurrentWithAnotherApp() {
        val item = currentItem()
        val mediaLabel = if (item.isVideo()) "vídeo" else "imagem"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType.ifEmpty { if (item.isVideo()) "video/*" else "image/*" })
            clipData = ClipData.newRawUri(mediaLabel, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(viewIntent, "Abrir $mediaLabel com"))
        } catch (_: ActivityNotFoundException) {
            Ui.toast(this, "Nenhum aplicativo compatível foi encontrado.")
        }
    }

    private fun askRenameCurrentImage() {
        val item = currentItem()
        if (!item.isImage()) return
        Ui.showTextInputDialog(
            this,
            title = "Renomear imagem",
            hint = "Nome do arquivo",
            positiveText = "Renomear",
            initialValue = item.name
        ) { requestedName ->
            val newName = ViewerMenuRules.normalizedRename(requestedName, item.name)
            when {
                newName == null -> Ui.toast(this, "Digite um nome válido.")
                newName == item.name -> Unit
                else -> renameImage(item, newName, requestPermission = true)
            }
        }
    }

    private fun renameImage(item: MediaItem, newName: String, requestPermission: Boolean) {
        try {
            val updated = contentResolver.update(
                item.uri,
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
                null,
                null
            ) > 0
            if (updated) {
                applyRenamedItem(item, newName)
                pendingRenameItem = null
                pendingRenameName = null
                Ui.toast(this, "Imagem renomeada.")
            } else if (requestPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingRenameItem = item
                pendingRenameName = newName
                MediaActions.requestWrite(this, item.uri, REQ_RENAME_WRITE)
            } else {
                Ui.toast(this, "Não foi possível renomear a imagem.")
            }
        } catch (error: SecurityException) {
            if (!requestPermission) {
                Ui.toast(this, "Não foi possível renomear a imagem.")
                return
            }
            pendingRenameItem = item
            pendingRenameName = newName
            requestRenamePermission(item.uri, error)
        }
    }

    private fun requestRenamePermission(uri: Uri, error: SecurityException) {
        when {
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException -> {
                startIntentSenderForResult(
                    error.userAction.actionIntent.intentSender,
                    REQ_RENAME_WRITE,
                    null,
                    0,
                    0,
                    0
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> MediaActions.requestWrite(this, uri, REQ_RENAME_WRITE)
            else -> Ui.toast(this, "Não foi possível solicitar permissão para renomear.")
        }
    }

    private fun applyRenamedItem(item: MediaItem, newName: String) {
        val index = mediaQueue.indexOfFirst { it.uri == item.uri }
        if (index < 0) return
        mediaQueue[index] = MediaItem(
            item.id,
            item.uri,
            newName,
            item.mimeType,
            item.dateAdded,
            item.size,
            item.relativePath,
            item.albumKey,
            item.albumName
        )
        if (index == currentIndex) title.text = newName
        MediaStoreRepository.invalidateCache()
    }

    private fun toggleVideoLoop() {
        if (shuffleMode || !currentItem().isVideo()) return
        val enabled = currentPlayer?.repeatMode != Player.REPEAT_MODE_ONE
        prefs.edit().putBoolean("loop_videos", enabled).apply()
        currentPlayer?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        Ui.toast(this, if (enabled) "Repetição ativada." else "Repetição desativada.")
    }

    private fun showCurrentVideoInformation() {
        val item = currentItem()
        val playerDuration = currentPlayer?.duration?.takeIf { it > 0L }
        executor.execute {
            val information = buildVideoInformation(item, playerDuration)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("Informações do vídeo")
                    .setMessage(information)
                    .setPositiveButton("Fechar", null)
                    .show()
            }
        }
    }

    private fun showCurrentImageInformation() {
        val item = currentItem()
        executor.execute {
            val key = item.uri.toString()
            val metadata = imageMetadataCache[key] ?: readImageMetadata(item.uri).also {
                imageMetadataCache[key] = it
            }
            val information = buildImageInformation(item, metadata)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("Informações da imagem")
                    .setMessage(information)
                    .setPositiveButton("Fechar", null)
                    .show()
            }
        }
    }

    private fun buildImageInformation(item: MediaItem, metadata: ImageMetadata): String = buildString {
        appendLine("Nome: ${item.name}")
        if (metadata.width != null && metadata.height != null) {
            appendLine("Resolução: ${metadata.width} × ${metadata.height}")
        }
        val size = item.size.takeIf { it > 0L } ?: queryMediaSize(item.uri)
        size?.let { appendLine("Tamanho: ${Formatter.formatFileSize(this@DetailActivity, it)}") }
        appendLine("Formato: ${item.mimeType.ifEmpty { "Desconhecido" }}")
        if (item.relativePath.isNotBlank()) appendLine("Pasta: ${item.relativePath.trimEnd('/')}")
        if (item.dateAdded > 0L) {
            appendLine("Adicionado em: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateAdded * 1000L))}")
        }
        listOfNotNull(metadata.make, metadata.model).joinToString(" ").takeIf { it.isNotBlank() }?.let {
            appendLine("Câmera: $it")
        }
        metadata.capturedAt?.let { appendLine("Capturada em: $it") }
        metadata.iso?.let { appendLine("ISO: $it") }
        metadata.aperture?.let { appendLine("Abertura: f/$it") }
        metadata.exposure?.let { appendLine("Exposição: ${it}s") }
        metadata.focalLength?.let { appendLine("Distância focal: ${it} mm") }
        if (metadata.hasLocation) {
            append(
                "Localização: ${String.format(Locale.US, "%.6f", metadata.latitude)}, " +
                    String.format(Locale.US, "%.6f", metadata.longitude)
            )
        }
    }.trim()

    private fun readImageMetadata(uri: Uri): ImageMetadata {
        var exifWidth: Int? = null
        var exifHeight: Int? = null
        var make: String? = null
        var model: String? = null
        var capturedAt: String? = null
        var iso: String? = null
        var aperture: String? = null
        var exposure: String? = null
        var focalLength: String? = null
        var latitude: Double? = null
        var longitude: Double? = null
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                exifWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                exifHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                capturedAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                exif.latLong?.let { coordinates ->
                    latitude = coordinates[0]
                    longitude = coordinates[1]
                }
            }
        } catch (_: Exception) {
        }
        val bounds = readImageBounds(uri)
        return ImageMetadata(
            width = exifWidth ?: bounds.first,
            height = exifHeight ?: bounds.second,
            make = make,
            model = model,
            capturedAt = capturedAt,
            iso = iso,
            aperture = aperture,
            exposure = exposure,
            focalLength = focalLength,
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun readImageBounds(uri: Uri): Pair<Int?, Int?> = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        options.outWidth.takeIf { it > 0 } to options.outHeight.takeIf { it > 0 }
    } catch (_: Exception) {
        null to null
    }

    private fun openCurrentImageOnMap() {
        val item = currentItem()
        executor.execute {
            val key = item.uri.toString()
            val metadata = imageMetadataCache[key] ?: readImageMetadata(item.uri).also {
                imageMetadataCache[key] = it
            }
            val latitude = metadata.latitude
            val longitude = metadata.longitude
            runOnUiThread {
                if (latitude == null || longitude == null) {
                    Ui.toast(this, "A imagem não possui localização GPS.")
                    return@runOnUiThread
                }
                val location = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, location), "Exibir localização"))
                } catch (_: ActivityNotFoundException) {
                    Ui.toast(this, "Nenhum aplicativo de mapas foi encontrado.")
                }
            }
        }
    }

    private fun startImagePresentation() {
        val currentUri = currentItem().uri
        val images = mediaQueue.filter { it.isImage() }
        if (images.size < 2) {
            Ui.toast(this, "A apresentação precisa de pelo menos duas imagens.")
            return
        }
        mediaQueue.clear()
        mediaQueue.addAll(images)
        currentIndex = mediaQueue.indexOfFirst { it.uri == currentUri }.takeIf { it >= 0 } ?: 0
        presentationMode = true
        restoredShuffleDelayMs = null
        scheduleAdjacentPreload()
        scheduleShuffleAdvance()
        if (hudVisible) toggleHud()
        Ui.toast(this, "Apresentação iniciada.")
    }

    private fun buildVideoInformation(item: MediaItem, playerDuration: Long?): String {
        var duration = playerDuration
        var width: Int? = null
        var height: Int? = null
        var rotation = 0
        var bitRate: Long? = null
        var frameRate: Float? = null
        var detectedMime: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, item.uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            detectedMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val size = item.size.takeIf { it > 0L } ?: queryMediaSize(item.uri)
        val codec = detectVideoCodec(item.uri)
        return buildString {
            appendLine("Nome: ${item.name}")
            duration?.takeIf { it > 0L }?.let { appendLine("Duração: ${formatTime(it)}") }
            if (width != null && height != null && width > 0 && height > 0) {
                appendLine("Resolução: ${width} × ${height}")
            }
            if (rotation != 0) appendLine("Rotação: ${rotation}°")
            size?.takeIf { it > 0L }?.let { appendLine("Tamanho: ${Formatter.formatFileSize(this@DetailActivity, it)}") }
            appendLine("Formato: ${item.mimeType.ifEmpty { detectedMime ?: "Desconhecido" }}")
            codec?.let { appendLine("Codec: $it") }
            bitRate?.takeIf { it > 0L }?.let { appendLine("Taxa de bits: ${formatBitRate(it)}") }
            frameRate?.takeIf { it > 0f }?.let { appendLine("Quadros por segundo: ${formatFrameRate(it)}") }
            if (item.relativePath.isNotBlank()) appendLine("Pasta: ${item.relativePath.trimEnd('/')}")
            if (item.dateAdded > 0L) {
                append("Adicionado em: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateAdded * 1000L))}")
            }
        }.trim()
    }

    private fun queryMediaSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun detectVideoCodec(uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            var videoMime: String? = null
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoMime = mime
                    break
                }
            }
            when (videoMime) {
                "video/avc" -> "H.264 / AVC"
                "video/hevc" -> "H.265 / HEVC"
                "video/x-vnd.on2.vp9" -> "VP9"
                "video/av01" -> "AV1"
                "video/mp4v-es" -> "MPEG-4 Visual"
                null -> null
                else -> videoMime.substringAfter("video/").uppercase(Locale.US)
            }
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun formatBitRate(bitsPerSecond: Long): String =
        if (bitsPerSecond >= 1_000_000L) {
            String.format(Locale.getDefault(), "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f kbps", bitsPerSecond / 1_000.0)
        }

    private fun formatFrameRate(frameRate: Float): String =
        if (abs(frameRate - frameRate.toInt()) < 0.01f) {
            frameRate.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.2f", frameRate)
        }

    private fun handleSwipeOrTap(event: MotionEvent): Boolean {
        if (switchingItem) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginPointerGesture(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateInteractiveSwipe(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelInteractiveSwipe()
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateInteractiveSwipe(event)
                if (dragPreviewPage != null) {
                    finishInteractiveSwipe()
                } else {
                    val deltaY = event.rawY - downY
                    val deltaX = event.rawX - downX
                    if (SwipeGestureRules.isTap(deltaX, deltaY, gestureTouchSlop)) {
                        handleTap(event.x, event.y)
                    }
                }
                return true
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val closeEnough = abs(x - lastTapX) < Ui.dp(this, 56) && abs(y - lastTapY) < Ui.dp(this, 56)
        if (now - lastTapTime <= DOUBLE_TAP_MS && closeEnough) {
            cancelPendingSingleTap()
            lastTapTime = 0L
            handleDoubleTap(x, y)
            return
        }
        lastTapTime = now
        lastTapX = x
        lastTapY = y
        cancelPendingSingleTap()
        pendingSingleTap = Runnable {
            pendingSingleTap = null
            toggleHud()
        }.also { handler.postDelayed(it, DOUBLE_TAP_MS) }
    }

    private fun handleDoubleTap(x: Float, y: Float) {
        val width = max(1, content.width)
        val item = currentItem()
        if (item.isVideo()) {
            when {
                x < width * 0.35f -> {
                    seekCurrentVideoBy(-10_000L)
                    return
                }
                x > width * 0.65f -> {
                    seekCurrentVideoBy(10_000L)
                    return
                }
            }
        }
        if (activeZoomImage() == null) {
            toggleZoom(x, y)
        }
    }

    private fun seekCurrentVideoBy(deltaMs: Long) {
        val player = currentPlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        updateTimeline()
    }

    private fun toggleZoom(x: Float, y: Float) {
        val page = activePage ?: return
        page.animate().cancel()
        zoomed = !zoomed
        if (zoomed) {
            page.pivotX = x
            page.pivotY = y
        }
        page.animate()
            .scaleX(if (zoomed) 2.15f else 1f)
            .scaleY(if (zoomed) 2.15f else 1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
    }

    private fun resetZoom(animated: Boolean) {
        val page = activePage ?: return
        activeZoomImage()?.zoomable?.reset()
        if (!zoomed && page.scaleX == 1f && page.scaleY == 1f) return
        zoomed = false
        page.animate().cancel()
        if (animated) {
            page.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(130L)
                .start()
        } else {
            page.scaleX = 1f
            page.scaleY = 1f
            page.translationX = 0f
            page.translationY = 0f
        }
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    private fun beginPointerGesture(event: MotionEvent) {
        downY = event.rawY
        downX = event.rawX
        dragDistance = 0f
    }

    private fun updateInteractiveSwipe(event: MotionEvent) {
        if (mediaQueue.size < 2) return
        val deltaY = event.rawY - downY
        val deltaX = event.rawX - downX
        if (dragPreviewPage == null) {
            val intent = SwipeGestureRules.intent(deltaX, deltaY, gestureTouchSlop) ?: return
            dragHorizontal = intent.axis == SwipeAxis.HORIZONTAL
            dragDirection = intent.direction
            dragTargetIndex = wrappedIndex(currentIndex + dragDirection)
            beginInteractiveSwipe()
        }
        val offset = swipeOffset()
        val delta = if (dragHorizontal) deltaX else deltaY
        dragDistance = (if (dragDirection > 0) -delta else delta).coerceIn(0f, offset.toFloat())
        val currentTranslation = if (dragDirection > 0) -dragDistance else dragDistance
        val incomingTranslation = if (dragDirection > 0) offset - dragDistance else -offset + dragDistance
        if (dragHorizontal) {
            activePage?.translationX = currentTranslation
            dragPreviewPage?.translationX = incomingTranslation
        } else {
            activePage?.translationY = currentTranslation
            dragPreviewPage?.translationY = incomingTranslation
        }
    }

    private fun beginInteractiveSwipe() {
        cancelPendingSingleTap()
        if (activeZoomImage() == null) {
            resetZoom(false)
        } else {
            zoomed = false
        }
        activePage?.animate()?.cancel()
        val page = createSwipePreviewPage(mediaQueue[dragTargetIndex])
        page.animate().cancel()
        val offset = swipeOffset()
        if (dragHorizontal) {
            page.translationX = if (dragDirection > 0) offset.toFloat() else -offset.toFloat()
        } else {
            page.translationY = if (dragDirection > 0) offset.toFloat() else -offset.toFloat()
        }
        dragPreviewPage = page
        content.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun finishInteractiveSwipe() {
        val offset = swipeOffset()
        val shouldCommit = SwipeGestureRules.shouldCommit(dragDistance, gestureTouchSlop)
        if (shouldCommit) {
            commitInteractiveSwipe(offset)
        } else {
            cancelInteractiveSwipe(offset)
        }
    }

    private fun commitInteractiveSwipe(offset: Int) {
        val incomingPage = dragPreviewPage ?: return
        val outgoingPage = activePage
        val outgoingPlayer = currentPlayer
        switchingItem = true
        zoomed = false
        saveCurrentPosition()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
        currentPlayer = null
        currentVideoKey = null
        currentIndex = dragTargetIndex
        preloadGeneration++
        val outgoingTarget = if (dragDirection > 0) -offset.toFloat() else offset.toFloat()
        val interpolator = DecelerateInterpolator(1.35f)
        outgoingPage?.animate()
            ?.translationX(if (dragHorizontal) outgoingTarget else 0f)
            ?.translationY(if (dragHorizontal) 0f else outgoingTarget)
            ?.setInterpolator(interpolator)
            ?.setDuration(165)
            ?.start()
        incomingPage.animate()
            .translationX(0f)
            .translationY(0f)
            .setInterpolator(interpolator)
            .setDuration(165)
            .withEndAction {
                outgoingPage?.let { content.removeView(it) }
                if (outgoingPlayer != null && outgoingPlayer !== currentPlayer) {
                    outgoingPlayer.release()
                }
                activePage = incomingPage
                applyCurrentUiAfterInteractiveSwipe()
                resetInteractiveSwipeState()
                switchingItem = false
                scheduleAdjacentPreload()
                scheduleShuffleAdvance()
            }
            .start()
    }

    private fun cancelInteractiveSwipe(offset: Int = swipeOffset()) {
        val incomingPage = dragPreviewPage
        val incomingTarget = if (dragDirection > 0) offset.toFloat() else -offset.toFloat()
        val interpolator = DecelerateInterpolator(1.35f)
        activePage?.animate()
            ?.translationX(0f)
            ?.translationY(0f)
            ?.setInterpolator(interpolator)
            ?.setDuration(150)
            ?.start()
        incomingPage?.animate()
            ?.translationX(if (dragHorizontal) incomingTarget else 0f)
            ?.translationY(if (dragHorizontal) 0f else incomingTarget)
            ?.setInterpolator(interpolator)
            ?.setDuration(150)
            ?.withEndAction {
                content.removeView(incomingPage)
                resetInteractiveSwipeState()
            }
            ?.start()
        if (incomingPage == null) {
            resetInteractiveSwipeState()
        }
    }

    private fun resetInteractiveSwipeState() {
        dragPreviewPage = null
        dragDirection = 0
        dragTargetIndex = -1
        dragDistance = 0f
    }

    private fun swipeOffset(): Int =
        if (dragHorizontal) {
            if (content.width == 0) resources.displayMetrics.widthPixels else content.width
        } else {
            if (content.height == 0) resources.displayMetrics.heightPixels else content.height
        }

    private fun applyCurrentUiAfterInteractiveSwipe() {
        val item = currentItem()
        title.text = item.name
        updateFavoriteButton()
        if (item.isVideo()) {
            content.removeAllViews()
            activePage = createCurrentPage()
            content.addView(activePage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            videoControls.visibility = View.GONE
            timelineRow.visibility = View.GONE
            soundButton.visibility = View.GONE
            activePage?.let { promoteImagePage(item, it) }
        }
    }

    private fun toggleHud() {
        if (!::topBar.isInitialized || !::bottomBar.isInitialized) return
        hudVisible = !hudVisible
        val show = hudVisible
        animateHudView(topBar, show)
        animateHudView(bottomBar, show)
        speedPopup?.dismiss()
    }

    private fun animateHudView(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(140L)
            .withEndAction {
                if (!show) view.visibility = View.GONE
            }
            .start()
    }

    private fun switchItem(direction: Int, horizontal: Boolean) {
        if (mediaQueue.size < 2) return
        switchingItem = true
        cancelPendingSingleTap()
        resetZoom(false)
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
        preloadGeneration++
        val offset = if (horizontal) {
            if (content.width == 0) resources.displayMetrics.widthPixels else content.width
        } else {
            if (content.height == 0) resources.displayMetrics.heightPixels else content.height
        }
        val incomingPage = createSwipePreviewPage(currentItem())
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
        incomingPage.alpha = 1f
        content.addView(incomingPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activePage = incomingPage

        val interpolator = DecelerateInterpolator(1.4f)
        outgoingPage?.animate()
            ?.translationX(if (horizontal) if (direction > 0) -offset.toFloat() else offset.toFloat() else 0f)
            ?.translationY(if (horizontal) 0f else if (direction > 0) -offset.toFloat() else offset.toFloat())
            ?.setInterpolator(interpolator)
            ?.setDuration(245)
            ?.start()
        incomingPage.animate()
            .translationX(0f)
            .translationY(0f)
            .setInterpolator(interpolator)
            .setDuration(245)
            .withEndAction {
                outgoingPage?.let { content.removeView(it) }
                if (outgoingPlayer != null && outgoingPlayer !== currentPlayer) {
                    outgoingPlayer.release()
                }
                activePage = incomingPage
                applyCurrentUiAfterInteractiveSwipe()
                switchingItem = false
                scheduleAdjacentPreload()
                scheduleShuffleAdvance()
            }
            .start()
    }

    private fun loadCurrentItem() {
        cancelPendingSingleTap()
        zoomed = false
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

    private fun createSwipePreviewPage(item: MediaItem): FrameLayout {
        val page = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (item.isVideo()) {
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            page.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            loadVideoPreview(item, preview)
        } else {
            addImagePreviewToPage(item, page)
        }
        return page
    }

    private fun showVideo(item: MediaItem, page: FrameLayout) {
        videoControls.visibility = View.VISIBLE
        timelineRow.visibility = View.VISIBLE
        soundButton.visibility = View.VISIBLE
        updateSoundButton()
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
            setKeepContentOnPlayerReset(true)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        page.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val player = ExoPlayer.Builder(this).build()
        currentPlayer = player
        currentVideoKey = "video_pos_${item.uri.hashCode()}"
        videoPositionRestored = false
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
        player.repeatMode = if (!shuffleMode && !presentationMode && prefs.getBoolean("loop_videos", false)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        player.volume = if (videoMuted) 0f else 1f
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !videoPositionRestored) {
                    val restoredPosition = restoredVideoPositionMs
                    if (restoredPosition != null) {
                        if (restoredPosition > 0L) player.seekTo(restoredPosition)
                        restoredVideoPositionMs = null
                    } else if (!shuffleMode && !presentationMode) {
                        restoreCurrentPosition()
                    }
                    videoPositionRestored = true
                }
                if ((shuffleMode || presentationMode) && player === currentPlayer && playbackState == Player.STATE_ENDED) {
                    handler.removeCallbacks(autoAdvanceRunnable)
                    handler.postDelayed(autoAdvanceRunnable, 250L)
                }
                if (!shuffleMode && !presentationMode && player === currentPlayer && playbackState == Player.STATE_ENDED) {
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
        player.playWhenReady = restoredVideoPlayWhenReady
            ?: (shuffleMode || presentationMode || prefs.getBoolean("autoplay_videos", true))
        restoredVideoPlayWhenReady = null
        updateSpeedButton()
        updatePlayPauseButton()
        handler.post(progressUpdater)
    }

    private fun loadVideoPreview(item: MediaItem, target: ImageView) {
        val key = item.uri.toString()
        val cached = videoPreviewCache.get(key)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        videoPreviewExecutor.execute {
            val bitmap = decodeVideoPreview(item)
            if (bitmap != null) {
                videoPreviewCache.put(key, bitmap)
                target.post {
                    if (target.parent != null) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun scheduleAdjacentPreload() {
        if (mediaQueue.size < 2) return
        val generation = ++preloadGeneration
        val centerIndex = currentIndex
        val seen = HashSet<String>()
        val radius = min(PRELOAD_AROUND_RADIUS, mediaQueue.size - 1)
        val ordered = ArrayList<MediaItem>(radius * 2 + 1)
        collectPreloadTarget(centerIndex, seen, ordered)
        for (distance in 1..radius) {
            collectPreloadTarget(centerIndex + distance, seen, ordered)
            collectPreloadTarget(centerIndex - distance, seen, ordered)
        }
        updateImagePreloadWindow(ordered.filter { it.isImage() })
        for (item in ordered) {
            if (item.isVideo()) preloadVideoPreview(item, generation)
        }
    }

    private fun collectPreloadTarget(index: Int, seen: MutableSet<String>, target: MutableList<MediaItem>) {
        val item = mediaQueue[wrappedIndex(index)]
        if (!seen.add(item.uri.toString())) return
        target.add(item)
    }

    private fun updateImagePreloadWindow(items: List<MediaItem>) {
        val desiredKeys = items.mapTo(LinkedHashSet()) { viewerPreviewKey(it) }
        val iterator = imagePreloadJobs.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!desiredKeys.contains(entry.key)) {
                entry.value.cancel()
                iterator.remove()
            }
        }
        for (item in items) {
            val key = viewerPreviewKey(item)
            if (imagePreloadJobs.containsKey(key)) continue
            val request = viewerPreviewRequest(item)
            imagePreloadJobs[key] = imagePreloadScope.launch {
                SingletonImageLoader.get(this@DetailActivity).execute(request)
            }
        }
    }

    private fun preloadVideoPreview(item: MediaItem, generation: Int) {
        if (!item.isVideo() || videoPreviewCache.get(item.uri.toString()) != null) return
        videoPreviewExecutor.execute {
            if (generation != preloadGeneration) return@execute
            if (videoPreviewCache.get(item.uri.toString()) != null) return@execute
            decodeVideoPreview(item)?.let { videoPreviewCache.put(item.uri.toString(), it) }
        }
    }

    private fun viewerPreviewRequest(item: MediaItem): ImageRequest {
        val metrics = resources.displayMetrics
        return ImageRequest.Builder(this)
            .data(item.uri)
            .memoryCacheKey(viewerPreviewKey(item))
            .diskCacheKey(viewerSourceKey(item))
            .size(metrics.widthPixels, metrics.heightPixels)
            .precision(Precision.EXACT)
            .scale(Scale.FIT)
            .allowHardware(true)
            .build()
    }

    private fun viewerPreviewKey(item: MediaItem): String {
        val metrics = resources.displayMetrics
        return "viewer_preview:${item.uri}:${metrics.widthPixels}x${metrics.heightPixels}"
    }

    private fun viewerNativeKey(item: MediaItem): String = "viewer_native:${item.uri}"

    private fun viewerSourceKey(item: MediaItem): String = "viewer_source:${item.uri}"

    private fun wrappedIndex(index: Int): Int {
        if (mediaQueue.isEmpty()) return 0
        var wrapped = index % mediaQueue.size
        if (wrapped < 0) wrapped += mediaQueue.size
        return wrapped
    }

    private fun decodeVideoPreview(item: MediaItem): Bitmap? =
        try {
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

    private fun scheduleShuffleAdvance() {
        handler.removeCallbacks(autoAdvanceRunnable)
        shuffleAdvanceDeadlineMs = 0L
        if ((!shuffleMode && !presentationMode) || switchingItem || mediaQueue.size < 2) return
        if (!currentItem().isVideo()) {
            val delay = restoredShuffleDelayMs?.coerceAtLeast(0L) ?: SHUFFLE_PHOTO_DELAY_MS
            restoredShuffleDelayMs = null
            shuffleAdvanceDeadlineMs = SystemClock.uptimeMillis() + delay
            handler.postDelayed(autoAdvanceRunnable, delay)
        }
    }

    private fun showImage(item: MediaItem, page: FrameLayout) {
        videoControls.visibility = View.GONE
        timelineRow.visibility = View.GONE
        soundButton.visibility = View.GONE
        addImagePreviewToPage(item, page)
        promoteImagePage(item, page)
    }

    private fun toggleVideoSound() {
        if (!currentItem().isVideo()) return
        videoMuted = !videoMuted
        currentPlayer?.volume = if (videoMuted) 0f else 1f
        updateSoundButton()
    }

    private fun updateSoundButton() {
        if (!::soundButton.isInitialized) return
        soundButton.setImageResource(if (videoMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        soundButton.contentDescription = if (videoMuted) "Ativar som" else "Desativar som"
        soundButton.alpha = if (videoMuted) 0.62f else 1f
    }

    private fun addImagePreviewToPage(item: MediaItem, page: FrameLayout) {
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            tag = IMAGE_PREVIEW_TAG
        }
        page.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview.load(item.uri) {
            val metrics = resources.displayMetrics
            size(metrics.widthPixels, metrics.heightPixels)
            precision(Precision.EXACT)
            scale(Scale.FIT)
            memoryCacheKey(viewerPreviewKey(item))
            diskCacheKey(viewerSourceKey(item))
            allowHardware(true)
            crossfade(false)
        }
    }

    private fun promoteImagePage(item: MediaItem, page: FrameLayout) {
        if (page.children().any { it is CoilZoomImageView }) return
        val preview = page.children().firstOrNull { it.tag == IMAGE_PREVIEW_TAG }
        val image = CoilZoomImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            scrollBar = null
            alpha = 0f
        }
        page.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image.tag = item.uri
        var pagingGesture = false
        image.setOnTouchListener { _, event ->
            val atBaseScale = isAtBaseZoom(image)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pagingGesture = false
                    beginPointerGesture(event)
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (dragPreviewPage != null) cancelInteractiveSwipe()
                    pagingGesture = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1 || !atBaseScale) {
                        if (pagingGesture || dragPreviewPage != null) cancelInteractiveSwipe()
                        pagingGesture = false
                        false
                    } else {
                        updateInteractiveSwipe(event)
                        if (!pagingGesture && dragPreviewPage != null) {
                            pagingGesture = true
                            val cancelEvent = MotionEvent.obtain(event).apply {
                                action = MotionEvent.ACTION_CANCEL
                            }
                            image.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        pagingGesture
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pagingGesture) {
                        updateInteractiveSwipe(event)
                        finishInteractiveSwipe()
                        pagingGesture = false
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (pagingGesture || dragPreviewPage != null) cancelInteractiveSwipe()
                    val consumed = pagingGesture
                    pagingGesture = false
                    consumed
                }
                else -> pagingGesture
            }
        }
        image.onViewTapListener = OnViewTapListener { _, _ ->
            toggleHud()
        }
        image.load(item.uri) {
            memoryCacheKey(viewerNativeKey(item))
            diskCacheKey(viewerSourceKey(item))
            crossfade(false)
            allowHardware(true)
            precision(Precision.EXACT)
            scale(Scale.FIT)
            listener(
                onSuccess = { _, _ ->
                    if (image.tag == item.uri) {
                        image.alpha = 1f
                        preview?.let { page.removeView(it) }
                    }
                },
                onError = { _, _ ->
                    if (image.tag == item.uri) {
                        Ui.toast(this@DetailActivity, "Não foi possível abrir a imagem.")
                    }
                }
            )
        }
    }

    private fun activeZoomImage(): CoilZoomImageView? {
        val page = activePage ?: return null
        for (index in 0 until page.childCount) {
            val child = page.getChildAt(index)
            if (child is CoilZoomImageView) return child
        }
        return null
    }

    private fun ViewGroup.children(): Sequence<View> = sequence {
        for (index in 0 until childCount) yield(getChildAt(index))
    }

    private fun isAtBaseZoom(image: CoilZoomImageView): Boolean {
        val scale = image.zoomable.transformState.value.scaleX
        val minimum = image.zoomable.minScaleState.value
        return scale <= minimum * 1.01f
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
            if (!names.containsKey(label)) names[label] = album.path.ifBlank { album.name }
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
                    imageMetadataCache.remove(item.uri.toString())
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
        } else if (requestCode == REQ_RENAME_WRITE) {
            val item = pendingRenameItem
            val name = pendingRenameName
            if (resultCode == RESULT_OK && item != null && name != null) {
                renameImage(item, name, requestPermission = false)
            } else {
                Ui.toast(this, "Renomeação cancelada.")
            }
            pendingRenameItem = null
            pendingRenameName = null
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
        WindowCompat.getInsetsController(window, window.decorView).apply {
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
        if (!shuffleMode && !presentationMode && currentPlayer != null && key != null && prefs.getBoolean("remember_video_position", true)) {
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

    override fun onSaveInstanceState(outState: Bundle) {
        val item = mediaQueue.getOrNull(currentIndex)
        if (item != null) {
            outState.putString(STATE_CURRENT_URI, item.uri.toString())
            outState.putString(STATE_CURRENT_NAME, item.name)
            outState.putString(STATE_CURRENT_MIME, item.mimeType)
            outState.putString(STATE_CURRENT_PATH, item.relativePath)
        }
        outState.putBoolean(STATE_SHUFFLE_MODE, shuffleMode)
        outState.putBoolean(STATE_PRESENTATION_MODE, presentationMode)
        outState.putLong(STATE_SHUFFLE_SEED, shuffleSeed)
        outState.putFloat(STATE_PLAYBACK_SPEED, playbackSpeed)
        outState.putBoolean(STATE_VIDEO_MUTED, videoMuted)
        currentPlayer?.let { player ->
            outState.putLong(STATE_VIDEO_POSITION, rememberedVideoPosition())
            outState.putBoolean(
                STATE_VIDEO_PLAY_WHEN_READY,
                playWhenReadyBeforePause ?: player.playWhenReady
            )
        }
        val shuffleDelay = if (shuffleAdvanceDeadlineMs > 0L) {
            (shuffleAdvanceDeadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        } else {
            restoredShuffleDelayMs
        }
        if (shuffleDelay != null) {
            outState.putLong(
                STATE_SHUFFLE_DELAY,
                shuffleDelay
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (playWhenReadyBeforePause == true) {
            currentPlayer?.play()
        }
        playWhenReadyBeforePause = null
        scheduleShuffleAdvance()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        cancelPendingSingleTap()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        if (shuffleAdvanceDeadlineMs > 0L) {
            restoredShuffleDelayMs =
                (shuffleAdvanceDeadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            shuffleAdvanceDeadlineMs = 0L
        }
        playWhenReadyBeforePause = currentPlayer?.playWhenReady
        currentPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingSingleTap()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        releasePlayer()
        imagePreloadJobs.values.forEach { it.cancel() }
        imagePreloadJobs.clear()
        imagePreloadScope.cancel()
        executor.shutdownNow()
        videoPreviewExecutor.shutdownNow()
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

    private fun navigationBarWidth(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_width", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else navigationBarHeight()
    }

    private fun navigationBarSideInset(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) navigationBarWidth() else 0

    private fun navigationBarBottomInset(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else navigationBarHeight()

    companion object {
        private const val REQ_DELETE = 31
        private const val REQ_HIDE_DELETE = 32
        private const val REQ_MOVE_WRITE = 33
        private const val REQ_ROTATE_WRITE = 34
        private const val REQ_CREATE_PDF = 35
        private const val REQ_RENAME_WRITE = 36
        private const val SHUFFLE_PHOTO_DELAY_MS = 4200L
        private const val DOUBLE_TAP_MS = 260L
        private const val PRELOAD_AROUND_RADIUS = 5
        private const val MAX_CONCURRENT_IMAGE_PRELOADS = 2
        private const val IMAGE_PREVIEW_TAG = "viewer_image_preview"
        private const val STATE_CURRENT_URI = "viewer_current_uri"
        private const val STATE_CURRENT_NAME = "viewer_current_name"
        private const val STATE_CURRENT_MIME = "viewer_current_mime"
        private const val STATE_CURRENT_PATH = "viewer_current_path"
        private const val STATE_SHUFFLE_MODE = "viewer_shuffle_mode"
        private const val STATE_PRESENTATION_MODE = "viewer_presentation_mode"
        private const val STATE_SHUFFLE_SEED = "viewer_shuffle_seed"
        private const val STATE_SHUFFLE_DELAY = "viewer_shuffle_delay"
        private const val STATE_VIDEO_POSITION = "viewer_video_position"
        private const val STATE_VIDEO_PLAY_WHEN_READY = "viewer_video_play_when_ready"
        private const val STATE_PLAYBACK_SPEED = "viewer_playback_speed"
        private const val STATE_VIDEO_MUTED = "viewer_video_muted"
        private val videoPreviewCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 32).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
}
