package com.galeria.android

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
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
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class DetailActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val videoPreviewExecutor = Executors.newSingleThreadExecutor()
    private val imagePreloadScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_IMAGE_PRELOADS)
    )
    private val imagePreloadJobs = LinkedHashMap<String, Job>()
    private lateinit var queueController: DetailMediaQueueController
    private lateinit var mediaActions: DetailMediaActions
    private lateinit var metadataRepository: DetailMetadataRepository
    private lateinit var metadataFormatter: DetailMetadataFormatter
    private lateinit var playbackController: DetailPlaybackController
    private lateinit var cinemaPreferences: CinemaModePreferences
    private lateinit var videoTrackController: VideoTrackController
    private val mediaQueue: List<MediaItem> get() = queueController.items
    private var currentIndex: Int
        get() = queueController.currentIndex
        set(value) = queueController.setCurrentIndex(value)
    private var shuffleMode: Boolean
        get() = queueController.shuffleMode
        set(value) { queueController.shuffleMode = value }
    private var presentationMode: Boolean
        get() = queueController.presentationMode
        set(value) { queueController.presentationMode = value }
    private var shuffleSeed: Long
        get() = queueController.shuffleSeed
        set(value) { queueController.shuffleSeed = value }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
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
    private lateinit var cinemaButton: ImageButton
    private lateinit var actionsBar: LinearLayout
    private lateinit var cinemaGestureIndicator: LinearLayout
    private lateinit var cinemaGestureLabel: TextView
    private lateinit var cinemaGestureProgress: ProgressBar
    private lateinit var cinemaModeIndicator: TextView
    private lateinit var videoControls: LinearLayout
    private lateinit var timelineRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private var speedPopup: PopupWindow? = null
    private var pendingDeleteUri: Uri? = null
    private var pendingHiddenCopy: File? = null
    private var pendingMoveItem: MediaItem? = null
    private var pendingMoveFolder: String? = null
    private var pendingMoveDestinationKey: String? = null
    private var pendingMoveDestinationName: String? = null
    private val removedUris = arrayListOf<String>()
    private val movedUris = arrayListOf<String>()
    private var pendingRotateItem: MediaItem? = null
    private var pendingPdfItem: MediaItem? = null
    private var pendingRenameItem: MediaItem? = null
    private var pendingRenameName: String? = null
    private var userSeeking = false
    private var switchingItem = false
    private var downY = 0f
    private var downX = 0f
    private val playbackSpeed: Float get() = playbackController.playbackSpeed
    private val videoMuted: Boolean get() = playbackController.muted
    private var hudVisible = true
    private var restoredShuffleDelayMs: Long? = null
    private var shuffleAdvanceDeadlineMs = 0L
    private var dragPreviewPage: FrameLayout? = null
    private var dragHorizontal = true
    private var dragDirection = 0
    private var dragTargetIndex = -1
    private var dragDistance = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingSingleTap: Runnable? = null
    private var completedViewerTap = false
    private var sourceAlbumKey: String? = null
    private var cinemaMode = false
    private var restoredCinemaMode: Boolean? = null
    private var orientationBeforeCinema: Int? = null
    private var cinemaTransitionRunning = false
    private var pendingCinemaOrientationChange: Runnable? = null
    private var pendingCinemaTransitionFinish: Runnable? = null
    private val cinemaGestureController = VideoCinemaGestureController()
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val hideCinemaGestureIndicator = Runnable {
        if (::cinemaGestureIndicator.isInitialized) cinemaGestureIndicator.visibility = View.GONE
    }
    private val hideCinemaModeIndicator = Runnable {
        if (!::cinemaModeIndicator.isInitialized) return@Runnable
        cinemaModeIndicator.animate().cancel()
        cinemaModeIndicator.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(CINEMA_MESSAGE_FADE_MS)
            .withEndAction { cinemaModeIndicator.visibility = View.GONE }
            .start()
    }
    private var zoomed = false
    private val imageMetadataCache = HashMap<String, DetailImageMetadata>()
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
        cinemaPreferences = CinemaModePreferences(prefs)
        videoTrackController = VideoTrackController(cinemaPreferences)
        sourceAlbumKey = intent.getStringExtra("album_key")
        restoredCinemaMode = savedInstanceState
            ?.takeIf { it.containsKey(STATE_CINEMA_MODE) }
            ?.getBoolean(STATE_CINEMA_MODE)
        orientationBeforeCinema = savedInstanceState
            ?.takeIf { it.containsKey(STATE_ORIENTATION_BEFORE_CINEMA) }
            ?.getInt(STATE_ORIENTATION_BEFORE_CINEMA)
        playbackController = DetailPlaybackController(
            context = applicationContext,
            prefs = prefs,
            initialSpeed = savedInstanceState?.getFloat(STATE_PLAYBACK_SPEED) ?: 1f,
            initiallyMuted = savedInstanceState?.getBoolean(STATE_VIDEO_MUTED) ?: false,
            restoredPositionMs = savedInstanceState
                ?.takeIf { it.containsKey(STATE_VIDEO_POSITION) }
                ?.getLong(STATE_VIDEO_POSITION),
            restoredPlayWhenReady = savedInstanceState
                ?.takeIf { it.containsKey(STATE_VIDEO_POSITION) }
                ?.getBoolean(STATE_VIDEO_PLAY_WHEN_READY)
        )
        queueController = DetailMediaQueueController(applicationContext)
        mediaActions = DetailMediaActions(this, prefs)
        removedUris.addAll(savedInstanceState?.getStringArrayList(MediaOperationNavigation.EXTRA_REMOVED_URIS).orEmpty())
        movedUris.addAll(savedInstanceState?.getStringArrayList(MediaOperationNavigation.EXTRA_MOVED_URIS).orEmpty())
        if (removedUris.isNotEmpty() || movedUris.isNotEmpty()) updateOperationResult()
        metadataRepository = DetailMetadataRepository(applicationContext)
        metadataFormatter = DetailMetadataFormatter(
            fileSizeFormatter = { bytes -> Formatter.formatFileSize(this, bytes) },
            dateAddedFormatter = { seconds ->
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(seconds * 1000L))
            },
            durationFormatter = ::formatTime
        )
        val uri = resolveInitialUri(savedInstanceState)
        if (uri == null) {
            Ui.toast(this, "Não foi possível abrir esta mídia.")
            finish()
            return
        }
        val name = savedInstanceState?.getString(STATE_CURRENT_NAME)
            ?: intent.getStringExtra("name")
            ?: queryIncomingDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
        val declaredMime = savedInstanceState?.getString(STATE_CURRENT_MIME)
            ?: intent.getStringExtra("mime")
            ?: intent.type
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val mime = ExternalMediaRules.normalizedMime(name, declaredMime)
        if (!ExternalMediaRules.isSupported(mime)) {
            Ui.toast(this, "Formato de mídia não compatível.")
            finish()
            return
        }
        val path = savedInstanceState?.getString(STATE_CURRENT_PATH) ?: intent.getStringExtra("path")
        queueController.configureModes(
            savedInstanceState?.getBoolean(STATE_SHUFFLE_MODE)
                ?: intent.getBooleanExtra("shuffle_mode", false),
            savedInstanceState?.getBoolean(STATE_PRESENTATION_MODE) ?: false,
            savedInstanceState?.getLong(STATE_SHUFFLE_SEED) ?: System.nanoTime()
        )
        if (savedInstanceState?.containsKey(STATE_SHUFFLE_DELAY) == true) {
            restoredShuffleDelayMs = savedInstanceState.getLong(STATE_SHUFFLE_DELAY)
        }
        prepareInitialMedia(uri, name, mime, path)
        buildLayout()
        loadCurrentItem()
        loadAlbumQueueAsync(uri)
    }

    private fun resolveInitialUri(savedInstanceState: Bundle?): Uri? {
        val saved = savedInstanceState?.getString(STATE_CURRENT_URI)?.takeIf { it.isNotBlank() }
        val internal = intent.getStringExtra("uri")?.takeIf { it.isNotBlank() }
        return when {
            saved != null -> Uri.parse(saved)
            internal != null -> Uri.parse(internal)
            else -> intent.data
        }
    }

    private fun queryIncomingDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun prepareInitialMedia(currentUri: Uri, name: String?, mime: String?, path: String?) {
        queueController.setInitial(MediaItem(0, currentUri, name, mime, 0, 0, path, "media", "Mídia"))
    }

    private fun loadAlbumQueueAsync(currentUri: Uri) {
        val includeHiddenFilesystem = intent.getBooleanExtra("include_hidden_filesystem", false)
        queueController.loadAlbum(
            sourceAlbumKey,
            includeHiddenFilesystem,
            currentUri,
        ) {
            if (!isFinishing) {
                scheduleAdjacentPreload()
                scheduleShuffleAdvance()
            }
        }
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
            marginStart = Ui.dp(this@DetailActivity, 4)
        }
        topBar.addView(title, titleParams)

        val more = iconButton(R.drawable.ic_more_vertical, Ui.dp(this, 48)).apply {
            contentDescription = "Mais opções"
            setOnClickListener { showMediaMenu(it) }
        }
        topBar.addView(more, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)))

        var touchClickX = Float.NaN
        var touchClickY = Float.NaN
        content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Visualizador de mídia"
            isClickable = true
            setOnClickListener {
                val x = touchClickX.takeIf { it.isFinite() } ?: width / 2f
                val y = touchClickY.takeIf { it.isFinite() } ?: height / 2f
                touchClickX = Float.NaN
                touchClickY = Float.NaN
                handleTap(x, y)
            }
            setOnTouchListener { view, event ->
                val handled = handleSwipeOrTap(event)
                if (event.actionMasked == MotionEvent.ACTION_UP && completedViewerTap) {
                    touchClickX = event.x
                    touchClickY = event.y
                    view.performClick()
                    completedViewerTap = false
                }
                handled
            }
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cinemaGestureLabel = timeLabel("").apply {
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        cinemaGestureProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
        }
        cinemaGestureIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rounded(0xCC111111.toInt(), 12, this@DetailActivity)
            setPadding(
                Ui.dp(this@DetailActivity, 18),
                Ui.dp(this@DetailActivity, 12),
                Ui.dp(this@DetailActivity, 18),
                Ui.dp(this@DetailActivity, 12)
            )
            addView(cinemaGestureLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@DetailActivity, 28)))
            addView(cinemaGestureProgress, LinearLayout.LayoutParams(Ui.dp(this@DetailActivity, 150), Ui.dp(this@DetailActivity, 12)))
            visibility = View.GONE
        }
        root.addView(
            cinemaGestureIndicator,
            FrameLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 64), Gravity.CENTER)
        )

        cinemaModeIndicator = TextView(this).apply {
            tag = CINEMA_TRANSITION_TAG
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            compoundDrawablePadding = Ui.dp(this@DetailActivity, 10)
            background = Ui.rounded(0xD9161719.toInt(), 24, this@DetailActivity)
            setPadding(
                Ui.dp(this@DetailActivity, 22),
                Ui.dp(this@DetailActivity, 13),
                Ui.dp(this@DetailActivity, 22),
                Ui.dp(this@DetailActivity, 13)
            )
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            visibility = View.GONE
            elevation = Ui.dp(this@DetailActivity, 10).toFloat()
        }
        root.addView(
            cinemaModeIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

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
                    val duration = playbackController.durationMs()
                    if (fromUser && duration != null) {
                        currentTime.text = formatTime(duration * progress / 1000L)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    userSeeking = true
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (bar != null) playbackController.seekToProgress(bar.progress)
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

        actionsBar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        favoriteButton = actionButton(R.drawable.ic_heart).apply { setOnClickListener { toggleFavorite() } }
        val share = actionButton(R.drawable.ic_share).apply {
            contentDescription = getString(R.string.action_share)
            setOnClickListener { shareCurrent() }
        }
        val trash = actionButton(R.drawable.ic_trash).apply {
            contentDescription = getString(R.string.action_delete)
            setOnClickListener { confirmDeleteCurrent() }
        }
        soundButton = actionButton(R.drawable.ic_volume_on).apply {
            contentDescription = "Desativar som"
            setOnClickListener { toggleVideoSound() }
        }
        cinemaButton = actionButton(R.drawable.ic_movie).apply {
            tag = CINEMA_BUTTON_TAG
            contentDescription = getString(R.string.viewer_cinema_mode)
            visibility = View.GONE
            setOnClickListener { toggleCinemaMode() }
        }
        actionsBar.addView(favoriteButton, actionParams())
        actionsBar.addView(share, actionParams())
        actionsBar.addView(trash, actionParams())
        actionsBar.addView(soundButton, actionParams())
        actionsBar.addView(cinemaButton, actionParams())
        bottomBar.addView(actionsBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.ACTION_TOUCH_HEIGHT_DP)))

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
        Ui.actionIconButton(this, icon, Color.WHITE)

    private fun actionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, Ui.dp(this, Ui.ACTION_TOUCH_HEIGHT_DP), 1f)

    private fun showMediaMenu(anchor: View) {
        val item = currentItem()
        if (item.isImage()) {
            val key = item.uri.toString()
            val cached = imageMetadataCache[key]
            if (cached != null) {
                showResolvedMediaMenu(anchor, item, cached)
            } else {
                executor.execute {
                    val metadata = metadataRepository.readImage(item.uri)
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

    private fun showResolvedMediaMenu(anchor: View, item: MediaItem, imageMetadata: DetailImageMetadata?) {
        val loopEnabled = playbackController.isLooping() ||
            ViewerStateRules.shouldLoopVideo(
                shuffleMode,
                presentationMode,
                prefs.getBoolean("loop_videos", false)
            )
        Ui.showPopupOptions(
            anchor,
            ViewerMenuRules.options(
                isVideo = item.isVideo(),
                loopEnabled = loopEnabled,
                shuffleMode = shuffleMode || presentationMode,
                hasLocation = imageMetadata?.hasLocation == true,
                cinemaMode = cinemaMode
            ),
            widthDp = 216
        ) { selected ->
            when (selected) {
                ViewerMenuRules.RENAME -> askRenameCurrentImage()
                ViewerMenuRules.OPEN_WITH -> openCurrentWithAnotherApp()
                ViewerMenuRules.COPY_TO -> askFolderForCopyOrMove(true, anchor)
                ViewerMenuRules.MOVE_TO -> askFolderForCopyOrMove(false, anchor)
                ViewerMenuRules.HIDE -> confirmHideCurrent()
                ViewerMenuRules.INFORMATION -> if (item.isVideo()) {
                    showCurrentVideoInformation()
                } else {
                    showCurrentImageInformation()
                }
                ViewerMenuRules.ENABLE_LOOP,
                ViewerMenuRules.DISABLE_LOOP -> toggleVideoLoop()
                ViewerMenuRules.AUDIO_TRACK -> showAudioTrackDialog()
                ViewerMenuRules.SUBTITLES -> showSubtitleTrackDialog()
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
        try {
            startActivity(Intent.createChooser(mediaActions.openWithIntent(item), "Abrir $mediaLabel com"))
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
            val updated = mediaActions.rename(item, newName)
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
        val currentRenamed = queueController.replace(item, mediaActions.renamedItem(item, newName))
        if (currentRenamed) title.text = newName
        MediaStoreRepository.invalidateCache()
    }

    private fun toggleVideoLoop() {
        if (shuffleMode || !currentItem().isVideo()) return
        val enabled = !playbackController.isLooping()
        prefs.edit().putBoolean("loop_videos", enabled).apply()
        playbackController.setLooping(enabled)
        Ui.toast(this, if (enabled) "Repetição ativada." else "Repetição desativada.")
    }

    private fun showCurrentVideoInformation() {
        val item = currentItem()
        val playerDuration = playbackController.durationMs()
        executor.execute {
            val metadata = metadataRepository.readVideo(item.uri, playerDuration)
            val information = metadataFormatter.videoInformation(
                item.metadataDescription(),
                metadata,
                metadataRepository.resolveMediaSize(item.uri, item.size)
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Ui.showMessageDialog(this, "Informações do vídeo", information)
            }
        }
    }

    private fun showCurrentImageInformation() {
        val item = currentItem()
        val key = item.uri.toString()
        imageMetadataCache[key]?.let { metadata ->
            showImageInformationDialog(item, metadata)
            return
        }
        executor.execute {
            val metadata = metadataRepository.readImage(item.uri).also {
                imageMetadataCache[key] = it
            }
            runOnUiThread {
                showImageInformationDialog(item, metadata)
            }
        }
    }

    private fun showImageInformationDialog(item: MediaItem, metadata: DetailImageMetadata) {
        if (isFinishing || isDestroyed) return
        Ui.showMessageDialog(
            this,
            "Informações da imagem",
            metadataFormatter.imageInformation(
                item.metadataDescription(),
                metadata,
                metadataRepository.resolveMediaSize(item.uri, item.size)
            )
        )
    }

    private fun openCurrentImageOnMap() {
        val item = currentItem()
        executor.execute {
            val key = item.uri.toString()
            val metadata = imageMetadataCache[key] ?: metadataRepository.readImage(item.uri).also {
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
        queueController.retainImages(currentUri)
        presentationMode = true
        restoredShuffleDelayMs = null
        scheduleAdjacentPreload()
        scheduleShuffleAdvance()
        if (hudVisible) toggleHud()
        Ui.toast(this, "Apresentação iniciada.")
    }

    private fun handleSwipeOrTap(event: MotionEvent): Boolean {
        if (switchingItem) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                completedViewerTap = false
                beginPointerGesture(event)
                if (cinemaGesturesEnabled()) {
                    cinemaGestureController.start(
                        event.x,
                        event.y,
                        content.width.toFloat(),
                        content.height.toFloat(),
                        currentBrightnessFraction(),
                        currentVolumeFraction()
                    )
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (cinemaGesturesEnabled()) {
                    val update = cinemaGestureController.update(event.x, event.y, gestureTouchSlop)
                    if (update.consumed) {
                        update.kind?.let { applyCinemaGesture(it, update.fraction) }
                        return true
                    }
                }
                updateInteractiveSwipe(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                completedViewerTap = false
                cinemaGestureController.cancel()
                cancelInteractiveSwipe()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (cinemaGesturesEnabled()) {
                    val update = cinemaGestureController.update(event.x, event.y, gestureTouchSlop)
                    update.kind?.let { applyCinemaGesture(it, update.fraction) }
                    if (cinemaGestureController.finish()) {
                        completedViewerTap = false
                        handler.removeCallbacks(hideCinemaGestureIndicator)
                        handler.postDelayed(hideCinemaGestureIndicator, CINEMA_INDICATOR_HIDE_MS)
                        return true
                    }
                }
                updateInteractiveSwipe(event)
                if (dragPreviewPage != null) {
                    completedViewerTap = false
                    finishInteractiveSwipe()
                } else {
                    val deltaY = event.rawY - downY
                    val deltaX = event.rawX - downX
                    completedViewerTap = SwipeGestureRules.isTap(deltaX, deltaY, gestureTouchSlop)
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
        playbackController.seekBy(deltaMs)
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
        switchingItem = true
        zoomed = false
        val outgoingPlayer = playbackController.detachCurrent()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
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
                playbackController.releaseDetached(outgoingPlayer)
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
            cinemaMode = false
            applyCinemaOrientation()
            videoControls.visibility = View.GONE
            timelineRow.visibility = View.GONE
            soundButton.visibility = View.GONE
            cinemaButton.visibility = View.GONE
            videoTrackController.unbind()
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
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
        val outgoingPlayer = playbackController.detachCurrent()
        val outgoingPage = activePage
        queueController.advance(direction)
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
                playbackController.releaseDetached(outgoingPlayer)
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
        playbackController.releaseCurrent()
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
        val restoredMode = restoredCinemaMode.also { restoredCinemaMode = null }
        cinemaMode = if (item.isVideo()) {
            restoredMode ?: cinemaPreferences.isEnabled(sourceAlbumKey)
        } else {
            false
        }
        applyCinemaOrientation()
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
        cinemaButton.visibility = View.VISIBLE
        updateCinemaButton()
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

        val player = playbackController.start(
            uri = item.uri,
            loop = ViewerStateRules.shouldLoopVideo(
                shuffleMode,
                presentationMode,
                prefs.getBoolean("loop_videos", false)
            ),
            defaultPlayWhenReady = shuffleMode || presentationMode ||
                prefs.getBoolean("autoplay_videos", true),
            rememberPosition = !shuffleMode && !presentationMode,
            listener = object : DetailPlaybackController.Listener {
                override fun onPlaybackChanged() {
                    updatePlayPauseButton()
                    updateTimeline()
                }

                override fun onPlaybackEnded() {
                    handler.removeCallbacks(autoAdvanceRunnable)
                    handler.postDelayed(autoAdvanceRunnable, 250L)
                }

                override fun onFirstFrame() {
                    playerView.animate().alpha(1f).setDuration(90).start()
                    preview.animate()
                        .alpha(0f)
                        .setDuration(140)
                        .withEndAction { page.removeView(preview) }
                        .start()
                }
            }
        )
        playerView.player = player
        if (cinemaMode) videoTrackController.bind(player, sourceAlbumKey) else videoTrackController.unbind()
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
        return ViewerStateRules.wrappedIndex(index, mediaQueue.size)
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
        cinemaButton.visibility = View.GONE
        videoTrackController.unbind()
        addImagePreviewToPage(item, page)
        promoteImagePage(item, page)
    }

    private fun toggleVideoSound() {
        if (!currentItem().isVideo()) return
        playbackController.toggleMuted()
        updateSoundButton()
    }

    private fun updateSoundButton() {
        if (!::soundButton.isInitialized) return
        soundButton.setImageResource(if (videoMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        soundButton.contentDescription = if (videoMuted) "Ativar som" else "Desativar som"
        soundButton.alpha = if (videoMuted) 0.62f else 1f
    }

    private fun toggleCinemaMode() {
        if (!currentItem().isVideo() || switchingItem || cinemaTransitionRunning) return
        cinemaMode = !cinemaMode
        cinemaGestureController.cancel()
        handler.removeCallbacks(hideCinemaGestureIndicator)
        cinemaGestureIndicator.visibility = View.GONE
        if (cinemaMode) {
            playbackController.player()?.let { videoTrackController.bind(it, sourceAlbumKey) }
        } else {
            videoTrackController.unbind()
        }
        updateCinemaButton()
        animateCinemaModeTransition()
    }

    private fun updateCinemaButton() {
        if (!::cinemaButton.isInitialized) return
        cinemaButton.setImageResource(if (cinemaMode) R.drawable.ic_movie_open else R.drawable.ic_movie)
        cinemaButton.background = Ui.actionFeedback(this, Color.WHITE)
        cinemaButton.alpha = 1f
        cinemaButton.isSelected = false
        ViewCompat.setStateDescription(
            cinemaButton,
            getString(if (cinemaMode) R.string.viewer_cinema_active else R.string.viewer_cinema_inactive)
        )
    }

    private fun applyCinemaOrientation() {
        if (cinemaMode) {
            if (orientationBeforeCinema == null) orientationBeforeCinema = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            orientationBeforeCinema?.let { previous ->
                orientationBeforeCinema = null
                requestedOrientation = previous
            }
        }
    }

    private fun animateCinemaModeTransition() {
        cinemaTransitionRunning = true
        cinemaButton.isEnabled = false
        pendingCinemaOrientationChange?.let(handler::removeCallbacks)
        pendingCinemaTransitionFinish?.let(handler::removeCallbacks)
        handler.removeCallbacks(hideCinemaModeIndicator)

        cinemaModeIndicator.animate().cancel()
        cinemaModeIndicator.text = getString(
            if (cinemaMode) R.string.viewer_cinema_transition_on else R.string.viewer_cinema_transition_off
        )
        cinemaModeIndicator.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (cinemaMode) R.drawable.ic_movie_open else R.drawable.ic_movie,
            0,
            0,
            0
        )
        cinemaModeIndicator.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(Color.WHITE)
        cinemaModeIndicator.visibility = View.VISIBLE
        cinemaModeIndicator.alpha = 0f
        cinemaModeIndicator.scaleX = 0.92f
        cinemaModeIndicator.scaleY = 0.92f
        cinemaModeIndicator.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(CINEMA_MESSAGE_APPEAR_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        listOf(content, topBar, bottomBar).forEach { view ->
            view.animate().cancel()
            view.animate().alpha(CINEMA_TRANSITION_DIM_ALPHA)
                .setDuration(CINEMA_ROTATION_DELAY_MS)
                .start()
        }

        pendingCinemaOrientationChange = Runnable {
            pendingCinemaOrientationChange = null
            applyCinemaOrientation()
        }.also { handler.postDelayed(it, CINEMA_ROTATION_DELAY_MS) }
        pendingCinemaTransitionFinish = Runnable {
            pendingCinemaTransitionFinish = null
            completeCinemaModeTransition()
        }.also { handler.postDelayed(it, CINEMA_TRANSITION_FALLBACK_MS) }
    }

    private fun completeCinemaModeTransition() {
        pendingCinemaTransitionFinish?.let(handler::removeCallbacks)
        pendingCinemaTransitionFinish = null
        listOf(content, topBar, bottomBar).forEach { view ->
            view.animate().cancel()
            view.animate().alpha(1f)
                .setDuration(CINEMA_CONTENT_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        cinemaTransitionRunning = false
        cinemaButton.isEnabled = true
        handler.removeCallbacks(hideCinemaModeIndicator)
        handler.postDelayed(hideCinemaModeIndicator, CINEMA_MESSAGE_VISIBLE_MS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::content.isInitialized) return
        cancelPendingSingleTap()
        cinemaGestureController.cancel()
        handler.removeCallbacks(hideCinemaGestureIndicator)
        cinemaGestureIndicator.visibility = View.GONE
        if (!switchingItem) cancelInteractiveSwipe()
        speedPopup?.dismiss()
        topBar.setPadding(
            Ui.dp(this, 8), statusBarHeight() + Ui.dp(this, 6),
            navigationBarSideInset() + Ui.dp(this, 10), Ui.dp(this, 6)
        )
        bottomBar.setPadding(
            Ui.dp(this, 14), Ui.dp(this, 4),
            navigationBarSideInset() + Ui.dp(this, 14), navigationBarBottomInset() + Ui.dp(this, 8)
        )
        if (cinemaTransitionRunning) completeCinemaModeTransition()
    }

    private fun cinemaGesturesEnabled(): Boolean =
        cinemaMode && currentItem().isVideo() && !switchingItem

    private fun currentBrightnessFraction(): Float {
        val windowBrightness = window.attributes.screenBrightness
        if (windowBrightness >= 0f) return windowBrightness.coerceIn(0f, 1f)
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    private fun currentVolumeFraction(): Float {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
    }

    private fun applyCinemaGesture(kind: CinemaGestureKind, fraction: Float) {
        val bounded = fraction.coerceIn(0f, 1f)
        when (kind) {
            CinemaGestureKind.BRIGHTNESS -> {
                window.attributes = window.attributes.apply {
                    screenBrightness = bounded.coerceAtLeast(MIN_CINEMA_BRIGHTNESS)
                }
                cinemaGestureLabel.text = getString(
                    R.string.viewer_brightness_percent,
                    (bounded * 100).roundToInt()
                )
            }
            CinemaGestureKind.VOLUME -> {
                val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (bounded * maximum).roundToInt().coerceIn(0, maximum),
                    0
                )
                cinemaGestureLabel.text = getString(
                    R.string.viewer_volume_percent,
                    (bounded * 100).roundToInt()
                )
            }
        }
        handler.removeCallbacks(hideCinemaGestureIndicator)
        handler.removeCallbacks(hideCinemaModeIndicator)
        pendingCinemaOrientationChange?.let(handler::removeCallbacks)
        pendingCinemaTransitionFinish?.let(handler::removeCallbacks)
        cinemaGestureProgress.progress = (bounded * 100).roundToInt()
        cinemaGestureIndicator.visibility = View.VISIBLE
    }

    private fun showAudioTrackDialog() {
        val choices = videoTrackController.audioChoices()
        if (choices.isEmpty()) {
            Ui.toast(this, getString(R.string.viewer_no_audio_tracks))
            return
        }
        val labels = arrayOf(getString(R.string.viewer_track_automatic)) + choices.map { it.label }
        val preferred = videoTrackController.audioPreference()
        val checked = preferred?.let { token ->
            choices.indexOfFirst { it.preferenceToken == token }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        Ui.showChoiceDialog(this, getString(R.string.viewer_audio_track), labels, checked) { selected ->
            if (selected == 0) videoTrackController.selectAutomaticAudio()
            else choices.getOrNull(selected - 1)?.let(videoTrackController::selectAudio)
        }
    }

    private fun showSubtitleTrackDialog() {
        val choices = videoTrackController.subtitleChoices()
        if (choices.isEmpty()) {
            Ui.toast(this, getString(R.string.viewer_no_subtitle_tracks))
            return
        }
        val labels = arrayOf(
            getString(R.string.viewer_subtitles_off),
            getString(R.string.viewer_track_automatic)
        ) + choices.map { it.label }
        val preferred = videoTrackController.subtitlePreference()
        val selectedTrack = preferred?.let { token ->
            choices.indexOfFirst { it.preferenceToken == token }
        } ?: -1
        val checked = when {
            !videoTrackController.subtitlesEnabled() -> 0
            selectedTrack >= 0 -> selectedTrack + 2
            else -> 1
        }
        Ui.showChoiceDialog(this, getString(R.string.viewer_subtitles), labels, checked) { selected ->
            when (selected) {
                0 -> videoTrackController.disableSubtitles()
                1 -> videoTrackController.selectAutomaticSubtitles()
                else -> choices.getOrNull(selected - 2)?.let(videoTrackController::selectSubtitle)
            }
        }
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
            crossfade(180)
        }
    }

    private fun promoteImagePage(item: MediaItem, page: FrameLayout) {
        if (page.children().any { it is CoilZoomImageView }) return
        val preview = page.children().firstOrNull { it.tag == IMAGE_PREVIEW_TAG }
        val image = AccessibleCoilZoomImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            scrollBar = null
            alpha = 0f
            accessibilityClickAction = { toggleHud() }
        }
        page.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image.tag = item.uri
        var pagingGesture = false
        var imageTapCandidate = false
        var imageTouchDownX = 0f
        var imageTouchDownY = 0f
        image.setOnTouchListener { _, event ->
            val atBaseScale = isAtBaseZoom(image)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pagingGesture = false
                    imageTapCandidate = true
                    imageTouchDownX = event.x
                    imageTouchDownY = event.y
                    beginPointerGesture(event)
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (dragPreviewPage != null) cancelInteractiveSwipe()
                    pagingGesture = false
                    imageTapCandidate = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - imageTouchDownX) > gestureTouchSlop ||
                        abs(event.y - imageTouchDownY) > gestureTouchSlop
                    ) {
                        imageTapCandidate = false
                    }
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
                        if (imageTapCandidate) {
                            image.suppressAccessibilityClickAction = true
                            try {
                                image.performClick()
                            } finally {
                                image.suppressAccessibilityClickAction = false
                            }
                        }
                        imageTapCandidate = false
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (pagingGesture || dragPreviewPage != null) cancelInteractiveSwipe()
                    val consumed = pagingGesture
                    pagingGesture = false
                    imageTapCandidate = false
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
                        image.animate()
                            .alpha(1f)
                            .setDuration(180L)
                            .withEndAction {
                                preview?.let { page.removeView(it) }
                            }
                            .start()
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
        playbackController.togglePlayback()
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        if (::playPauseButton.isInitialized) {
            playPauseButton.setImageResource(
                if (playbackController.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    private fun updateTimeline() {
        if (!currentItem().isVideo()) return
        val timeline = playbackController.timeline() ?: return
        currentTime.text = formatTime(timeline.positionMs)
        durationTime.text = if (timeline.durationMs > 0) formatTime(timeline.durationMs) else "00:00"
        if (!userSeeking && timeline.durationMs > 0) {
            seekBar.progress = timeline.progress
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
                playbackController.setSpeed(speed)
                updateSpeedButton()
                speedPopup?.dismiss()
            }
        }
        panel.addView(option, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)))
    }

    private fun updateSpeedButton() {
        if (!::speedButton.isInitialized) return
        speedButton.text = DetailPlaybackRules.speedLabel(playbackSpeed)
    }

    private fun toggleFavorite() {
        val item = currentItem()
        val favorite = mediaActions.toggleFavorite(item)
        Ui.toast(this, if (favorite) "Adicionado aos favoritos." else "Removido dos favoritos.")
        updateFavoriteButton()
    }

    private fun updateFavoriteButton() {
        if (!::favoriteButton.isInitialized || mediaQueue.isEmpty()) return
        val favorite = mediaActions.isFavorite(currentItem())
        favoriteButton.setImageResource(if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        favoriteButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (favorite) Color.rgb(238, 112, 132) else Color.WHITE
        )
        favoriteButton.isSelected = favorite
        favoriteButton.contentDescription = getString(
            if (favorite) R.string.action_unfavorite else R.string.action_favorite
        )
    }

    private fun shareCurrent() {
        startActivity(Intent.createChooser(mediaActions.shareIntent(currentItem()), "Compartilhar"))
    }

    private fun confirmHideCurrent() {
        Ui.showConfirmationDialog(
            this,
            "Ocultar arquivo",
            "O arquivo será copiado para a área oculta do app e removido da galeria pública.",
            "Ocultar"
        ) { hideCurrent() }
    }

    private fun hideCurrent() {
        val item = currentItem()
        pendingHiddenCopy = mediaActions.copyToHidden(item)
        if (pendingHiddenCopy == null) {
            Ui.toast(this, "Não foi possível copiar para ocultos.")
            return
        }
        pendingDeleteUri = item.uri
        val result = mediaActions.delete(item, REQ_HIDE_DELETE)
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

    private fun askFolderForCopyOrMove(copy: Boolean, anchor: View) {
        val item = currentItem()
        val exposedKeys = intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.toSet()
        val hiddenKeys = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty()
        val includeHidden = exposedKeys != null && StorageAccessRules.includeHiddenFilesystem(
            intent.getBooleanExtra("include_hidden_filesystem", false),
            MediaActions.hasAllFilesAccess(this)
        )
        mediaActions.loadTargets(
            exposedKeys,
            hiddenKeys,
            setOfNotNull(item.albumKey, intent.getStringExtra("album_key")),
            includeHidden
        ) { targets ->
            if (isFinishing || !anchor.isAttachedToWindow || currentItem().uri != item.uri) return@loadTargets
            if (targets.isEmpty()) {
                Ui.toast(this, "Nenhum álbum disponível.")
                return@loadTargets
            }
            Ui.showAlbumTargets(anchor, if (copy) "Copiar para" else "Mover para", targets) { album ->
                val folder = album.path.ifBlank { album.name }
                if (copy) {
                    copyCurrentToFolder(item, folder)
                } else {
                    pendingMoveDestinationKey = album.key
                    pendingMoveDestinationName = album.name
                    moveCurrentToFolder(item, folder)
                }
            }
        }
    }

    private fun copyCurrentToFolder(item: MediaItem, folder: String) {
        val result = mediaActions.copyToFolder(item, folder)
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item copiado.")
        } else {
            requestFileManagementAccess()
        }
    }

    private fun moveCurrentToFolder(item: MediaItem, folder: String) {
        pendingMoveItem = item
        pendingMoveFolder = folder
        val sourceFolder = MediaActions.fileFromMediaStore(this, item.uri)?.parentFile
        val result = mediaActions.moveToFolder(item, folder)
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item movido.")
            completeMovedItem(item, folder, sourceFolder)
            pendingMoveItem = null
            pendingMoveFolder = null
        } else if (result == MediaActions.RESULT_NEEDS_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaActions.requestWrite(this, item.uri, REQ_MOVE_WRITE)
        } else {
            pendingMoveItem = null
            pendingMoveFolder = null
            requestFileManagementAccess()
        }
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
        Ui.showConfirmationDialog(
            this,
            "Excluir arquivo",
            "Tem certeza que deseja excluir este arquivo?",
            "Excluir"
        ) { deleteCurrent() }
    }

    private fun deleteCurrent() {
        val item = currentItem()
        pendingDeleteUri = item.uri
        val result = mediaActions.delete(item, REQ_DELETE)
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
            Ui.showConfirmationDialog(
                this,
                "Permitir gerenciamento completo",
                "Esta mídia não pôde ser alterada pelo acesso padrão do Android. O acesso completo também libera mídias ocultas, pastas arbitrárias e operações em lote.",
                "Permitir"
            ) { MediaActions.requestAllFilesAccess(this) }
        } else {
            Ui.toast(this, "Não foi possível concluir a operação.")
        }
    }

    private fun updateOperationResult(destinationKey: String? = null, destinationName: String? = null) {
        setResult(RESULT_OK, Intent().apply {
            putStringArrayListExtra(MediaOperationNavigation.EXTRA_REMOVED_URIS, ArrayList(removedUris))
            putStringArrayListExtra(MediaOperationNavigation.EXTRA_MOVED_URIS, ArrayList(movedUris))
            if (destinationKey != null) {
                putExtra(MediaOperationNavigation.EXTRA_DESTINATION_KEY, destinationKey)
                putExtra(MediaOperationNavigation.EXTRA_DESTINATION_NAME, destinationName)
            }
        })
    }

    private fun completeMovedItem(item: MediaItem, folder: String, sourceFolder: File?) {
        movedUris.add(item.uri.toString())
        val sourceKey = intent.getStringExtra("album_key")
        if (!sourceKey.isNullOrEmpty() && sourceKey != "all_media" &&
            MediaOperationNavigation.isEmptyFolder(sourceFolder)) {
            val key = pendingMoveDestinationKey ?: MediaActions.destinationRelativePath(folder, item.isVideo())
            val name = pendingMoveDestinationName ?: key.trimEnd('/').substringAfterLast('/')
            updateOperationResult(key, name)
            if (callingActivity == null) {
                startActivity(MediaOperationNavigation.destinationIntent(this, intent, key, name))
            }
            finish()
        } else {
            updateOperationResult()
            removeDeletedItem(moved = true)
        }
    }

    private fun removeDeletedItem(moved: Boolean = false) {
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(applicationContext)
        if (!moved) {
            removedUris.add(currentItem().uri.toString())
            updateOperationResult()
        }
        pendingDeleteUri = null
        if (!queueController.removeCurrent()) {
            finish()
            return
        }
        loadCurrentItem()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            val deleted = resultCode == RESULT_OK
            pendingDeleteUri = null
            if (deleted) {
                Ui.toast(this, "Item excluído.")
                removeDeletedItem()
            } else {
                Ui.toast(this, "Exclusão cancelada.")
            }
        } else if (requestCode == REQ_HIDE_DELETE) {
            val deleted = resultCode == RESULT_OK
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
                val sourceFolder = MediaActions.fileFromMediaStore(this, item.uri)?.parentFile
                val result = mediaActions.moveToFolder(item, folder)
                Ui.toast(this, if (result == MediaActions.RESULT_DONE) "Item movido." else "Não foi possível mover.")
                if (result == MediaActions.RESULT_DONE) {
                    completeMovedItem(item, folder, sourceFolder)
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

    private fun currentItem(): MediaItem = queueController.current()

    private fun MediaItem.metadataDescription(): DetailMediaMetadata = DetailMediaMetadata(
        name = name,
        mimeType = mimeType,
        relativePath = relativePath,
        dateAddedSeconds = dateAdded
    )

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(MediaOperationNavigation.EXTRA_REMOVED_URIS, ArrayList(removedUris))
        outState.putStringArrayList(MediaOperationNavigation.EXTRA_MOVED_URIS, ArrayList(movedUris))
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
        outState.putBoolean(STATE_CINEMA_MODE, cinemaMode)
        orientationBeforeCinema?.let { outState.putInt(STATE_ORIENTATION_BEFORE_CINEMA, it) }
        playbackController.savedPlayWhenReady()?.let { playWhenReady ->
            outState.putLong(STATE_VIDEO_POSITION, playbackController.rememberedPosition())
            outState.putBoolean(
                STATE_VIDEO_PLAY_WHEN_READY,
                playWhenReady
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
        playbackController.resumeAfterLifecycle()
        handler.removeCallbacks(progressUpdater)
        if (mediaQueue.getOrNull(currentIndex)?.isVideo() == true) {
            handler.post(progressUpdater)
        }
        scheduleShuffleAdvance()
    }

    override fun onPause() {
        super.onPause()
        playbackController.pauseForLifecycle()
        cancelPendingSingleTap()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        if (shuffleAdvanceDeadlineMs > 0L) {
            restoredShuffleDelayMs =
                (shuffleAdvanceDeadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            shuffleAdvanceDeadlineMs = 0L
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingSingleTap()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        handler.removeCallbacks(hideCinemaGestureIndicator)
        handler.removeCallbacks(hideCinemaModeIndicator)
        pendingCinemaOrientationChange?.let(handler::removeCallbacks)
        pendingCinemaTransitionFinish?.let(handler::removeCallbacks)
        pendingCinemaOrientationChange = null
        pendingCinemaTransitionFinish = null
        videoTrackController.unbind()
        playbackController.releaseCurrent()
        imagePreloadJobs.values.forEach { it.cancel() }
        imagePreloadJobs.clear()
        imagePreloadScope.cancel()
        executor.shutdownNow()
        videoPreviewExecutor.shutdownNow()
        queueController.close()
        mediaActions.close()
    }

    private fun resetSpeedAndFinish() {
        playbackController.resetSpeed()
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
        private const val STATE_CINEMA_MODE = "viewer_cinema_mode"
        private const val STATE_ORIENTATION_BEFORE_CINEMA = "viewer_orientation_before_cinema"
        private const val CINEMA_BUTTON_TAG = "viewer_cinema_mode_button"
        private const val CINEMA_TRANSITION_TAG = "viewer_cinema_transition_message"
        private const val CINEMA_INDICATOR_HIDE_MS = 650L
        private const val CINEMA_ROTATION_DELAY_MS = 170L
        private const val CINEMA_TRANSITION_FALLBACK_MS = 520L
        private const val CINEMA_MESSAGE_APPEAR_MS = 150L
        private const val CINEMA_CONTENT_FADE_MS = 230L
        private const val CINEMA_MESSAGE_VISIBLE_MS = 1_200L
        private const val CINEMA_MESSAGE_FADE_MS = 180L
        private const val CINEMA_TRANSITION_DIM_ALPHA = 0.78f
        private const val MIN_CINEMA_BRIGHTNESS = 0.02f
        private val videoPreviewCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 32).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
}
