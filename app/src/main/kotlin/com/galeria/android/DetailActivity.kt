package com.galeria.android

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
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
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.view.WindowCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.github.panpf.zoomimage.view.zoom.OnViewLongPressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class DetailActivity : ComponentActivity() {
    internal val operations = ActivityOperationRunner(this)
    internal val executor = Executors.newSingleThreadExecutor()
    private val videoPreviewExecutor = Executors.newSingleThreadExecutor()
    private val motionPhotoExecutor = Executors.newSingleThreadExecutor()
    // Neighbours are checked ahead on their own low-priority thread, so the photo on screen never waits behind them.
    private val motionPhotoPrefetchExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "motion-photo-prefetch").apply { priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var motionPhotoPrefetchGeneration = 0
    private val imagePreloadScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_IMAGE_PRELOADS)
    )
    internal val imagePreloadJobs = LinkedHashMap<String, Job>()
    internal lateinit var queueController: DetailMediaQueueController
    internal lateinit var mediaActions: DetailMediaActions
    internal lateinit var metadataRepository: DetailMetadataRepository
    internal lateinit var metadataFormatter: DetailMetadataFormatter
    internal lateinit var playbackController: DetailPlaybackController
    private lateinit var cinemaPreferences: CinemaModePreferences
    private lateinit var videoTrackController: VideoTrackController
    internal val mediaQueue: List<MediaItem> get() = queueController.items
    internal var currentIndex: Int
        get() = queueController.currentIndex
        set(value) = queueController.setCurrentIndex(value)
    private var shuffleMode: Boolean
        get() = queueController.shuffleMode
        set(value) { queueController.shuffleMode = value }
    internal var presentationMode: Boolean
        get() = queueController.presentationMode
        set(value) { queueController.presentationMode = value }
    private var shuffleSeed: Long
        get() = queueController.shuffleSeed
        set(value) { queueController.shuffleSeed = value }
    internal val handler = Handler(Looper.getMainLooper())
    internal lateinit var prefs: SharedPreferences
    private lateinit var content: FrameLayout
    internal var activePage: FrameLayout? = null
    internal lateinit var topBar: LinearLayout
    internal lateinit var bottomBar: LinearLayout
    internal lateinit var title: TextView
    private lateinit var fullscreenDetails: TextView
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private lateinit var speedButton: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var favoriteButton: ImageButton
    private lateinit var clockBadge: TextView
    private lateinit var soundButton: ImageButton
    private lateinit var cinemaButton: ImageButton
    private lateinit var motionPhotoButton: ImageButton
    private lateinit var motionPhotoOverlay: FrameLayout
    private lateinit var imageInformationButton: ImageButton
    private val motionPhotoController by lazy {
        ViewerMotionPhotoController(
            detector = object : ViewerMotionPhotoController.Detector {
                override fun detect(source: ViewerMotionPhotoController.Source, isRelevant: () -> Boolean,
                    complete: (MotionPhotoClip?) -> Unit) {
                    motionPhotoExecutor.execute {
                        if (!isRelevant()) return@execute
                        val clip = MotionPhotoSupport.detect(applicationContext, source.uri.toUri(), isRelevant)
                        runOnUiThread { complete(clip) }
                    }
                }
            },
            currentSource = { mediaQueue.getOrNull(currentIndex)?.let(::motionPhotoSource) },
            hostActive = { !isFinishing && !isDestroyed },
            changed = { clip -> motionPhotoButton.visibility = if (clip != null) View.VISIBLE else View.GONE }
        )
    }
    private lateinit var actionsBar: LinearLayout
    internal lateinit var cinemaGestureIndicator: LinearLayout
    private lateinit var cinemaGestureLabel: TextView
    private lateinit var cinemaGestureProgress: ProgressBar
    internal lateinit var cinemaModeIndicator: TextView
    private lateinit var videoControls: LinearLayout
    private lateinit var timelineRow: LinearLayout
    private lateinit var videoTimeline: VideoTimelineView
    private lateinit var timelineBinding: VideoTimelineBinding
    private lateinit var videoProgressRow: LinearLayout
    private lateinit var timelineButton: ImageButton
    private lateinit var timelineLoadingRow: LinearLayout
    private var timelineExpanded = false
    private lateinit var textRecognitionButton: ImageButton
    private var ocrSelection: OcrSelectionOverlay? = null
    private var ocrProgress: LoadingIndicatorView? = null
    private var pendingOcrPoint: android.graphics.PointF? = null
    private val textRecognitionController by lazy {
        ViewerTextRecognitionController(
            scheduler = object : ViewerTextRecognitionController.Scheduler {
                override fun post(work: Runnable, delayMs: Long) { handler.postDelayed(work, delayMs) }
                override fun cancel(work: Runnable) { handler.removeCallbacks(work) }
            },
            recognizer = object : ViewerTextRecognitionController.Recognizer {
                override fun recognize(image: ViewerTextRecognitionController.Image, detailed: Boolean,
                    isRelevant: () -> Boolean, complete: (Result<OcrDocument>) -> Unit) {
                    ImageTextRecognition.recognizeDocument(this@DetailActivity, Uri.parse(image.uri), isRelevant, detailed, complete)
                }
            },
            currentImage = { mediaQueue.getOrNull(currentIndex)?.takeIf { it.isImage() }?.let(::textSource) },
            hostActive = { !isFinishing && !isDestroyed },
            listener = object : ViewerTextRecognitionController.Listener {
                override fun availabilityChanged(available: Boolean) {
                    // The manual shortcut stays in its slot even before detection,
                    // including photos for which automatic OCR finds no text.
                    textRecognitionButton.contentDescription = getString(
                        if (available) R.string.ocr_text_available else R.string.action_recognize_text
                    )
                }
                override fun manualStarted() {
                    clearOcrProgress()
                    textRecognitionButton.isEnabled = false
                    ocrProgress = LoadingIndicatorView.overImage(this@DetailActivity, getString(R.string.ocr_processing)).also {
                        activePage?.addView(it, FrameLayout.LayoutParams(Ui.dp(this@DetailActivity, 48), Ui.dp(this@DetailActivity, 48), Gravity.CENTER))
                    }
                }
                override fun manualCompleted(result: Result<OcrDocument>, cached: Boolean) {
                    clearOcrProgress()
                    result.fold(
                        onSuccess = { document ->
                            if (document.text.isBlank()) {
                                Haptics.perform(this@DetailActivity, Haptics.Effect.REJECT)
                                Ui.toast(this@DetailActivity, getString(R.string.ocr_no_text))
                            } else {
                                Haptics.perform(this@DetailActivity, Haptics.Effect.CONFIRM)
                                showRecognizedText(document)
                            }
                        },
                        onFailure = {
                            Haptics.perform(this@DetailActivity, Haptics.Effect.REJECT)
                            Ui.toast(this@DetailActivity, getString(R.string.ocr_error))
                        }
                    )
                }
            }
        )
    }
    internal var speedPopup: PopupWindow? = null
    internal var pendingDeleteUri: Uri? = null
    internal var pendingDeletePermanent = false
    internal var pendingHiddenCopy: File? = null
    internal var pendingMoveItem: MediaItem? = null
    internal var pendingMoveFolder: String? = null
    internal var pendingMoveDestinationKey: String? = null
    internal var pendingMoveDestinationName: String? = null
    internal val removedUris = arrayListOf<String>()
    internal val movedUris = arrayListOf<String>()
    internal var pendingRotateItem: MediaItem? = null
    internal var imageRotationRunning = false
    internal var pendingPdfItem: MediaItem? = null
    internal var pendingRenameItem: MediaItem? = null
    internal var pendingRenameName: String? = null
    internal val mediaTransitionController = MediaTransitionController(object : MediaTransitionController.Scheduler {
        override fun schedule(completion: Runnable, delayMs: Long) { handler.postDelayed(completion, delayMs) }
        override fun cancel(completion: Runnable) { handler.removeCallbacks(completion) }
    })
    private val switchingItem: Boolean get() = mediaTransitionController.isBusy
    private val swipeGestureController = ViewerSwipeGestureController()
    private val playbackSpeed: Float get() = playbackController.playbackSpeed
    private val videoMuted: Boolean get() = playbackController.muted
    internal var hudVisible = true
    internal var restoredShuffleDelayMs: Long? = null
    private var shuffleAdvanceDeadlineMs = 0L
    private var dragPreviewPage: FrameLayout? = null
    private val dragHorizontal: Boolean get() = swipeGestureController.axis == SwipeAxis.HORIZONTAL
    private val dragDirection: Int get() = swipeGestureController.direction
    private var dragTargetIndex = -1
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingSingleTap: Runnable? = null
    private var completedViewerTap = false
    internal var sourceAlbumKey: String? = null
    private val cinemaMode: Boolean get() = cinemaController.enabled
    internal val cinemaController by lazy {
        ViewerCinemaController(
            scheduler = object : ViewerCinemaController.Scheduler {
                override fun post(work: Runnable, delayMs: Long) { handler.postDelayed(work, delayMs) }
                override fun cancel(work: Runnable) { handler.removeCallbacks(work) }
            },
            requestedOrientation = { requestedOrientation },
            landscapeOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            effects = object : ViewerCinemaController.Effects {
                override fun modeChanged(enabled: Boolean) {
                    cinemaGestureController.cancel()
                    handler.removeCallbacks(hideCinemaGestureIndicator)
                    cinemaGestureIndicator.visibility = View.GONE
                    if (enabled) playbackController.player()?.let { videoTrackController.bind(it, cinemaAlbumKey(currentItem())) }
                    else videoTrackController.unbind()
                    updateCinemaButton()
                    applyViewerSystemBars()
                }
                override fun orientationChanged(orientation: Int) { requestedOrientation = orientation }
                override fun barsChanged(enabled: Boolean) { setViewerSystemBars(enabled) }
                override fun transitionStarted(enabled: Boolean) { animateCinemaModeTransition() }
                override fun transitionFinished() { completeCinemaModeTransition() }
            }
        )
    }
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
    internal val imageMetadataCache = HashMap<String, DetailImageMetadata>()
    // Whether each video was joined by the app and can be separated, read once per video.
    internal val separableCache = HashMap<String, Boolean>()
    internal var pendingSplitUri: Uri? = null
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
        // Before reading any option: launches from other apps keep only the file they sent.
        ViewerLaunch.sanitize(this, intent)
        super.onCreate(savedInstanceState)
        timelineExpanded = savedInstanceState?.getBoolean("timeline_expanded") ?: false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (closeOcrSelection()) return
                // A playing video (also in cinema mode) shrinks to a floating thumbnail
                // so the user can keep browsing the gallery or other apps (unless turned off).
                if (enterPictureInPictureIfPlaying()) return
                resetSpeedAndFinish()
            }
        })
        Ui.applyOpenTransition(this)
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        val normalOrientation = viewerRotationPreference()
        if (normalOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) requestedOrientation = normalOrientation
        cinemaPreferences = CinemaModePreferences(prefs)
        videoTrackController = VideoTrackController(cinemaPreferences, labels = TrackLabels.from(this))
        sourceAlbumKey = intent.getStringExtra("album_key")
        if (sourceAlbumKey?.let { SecurityStore(this).isSecureFolder(it) && !SecuritySession.isFolderUnlocked(it) } == true) {
            Ui.toast(this, getString(R.string.security_folder_locked))
            finish()
            return
        }
        pendingDeletePermanent = savedInstanceState?.getBoolean("pending_delete_permanent") ?: false
        cinemaController.restore(
            savedInstanceState?.takeIf { it.containsKey(STATE_CINEMA_MODE) }?.getBoolean(STATE_CINEMA_MODE),
            savedInstanceState?.takeIf { it.containsKey(STATE_ORIENTATION_BEFORE_CINEMA) }?.getInt(STATE_ORIENTATION_BEFORE_CINEMA),
            savedInstanceState?.getBoolean(STATE_AUTOMATIC_CINEMA) ?: false
        )
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
                DateTimeDisplay.format(this, seconds * 1000L)
            },
            durationFormatter = ::formatTime,
            label = { MetadataLabels.translate(this, it) }
        )
        val uri = resolveInitialUri(savedInstanceState)
        if (uri == null) {
            Ui.toast(this, getString(R.string.msg_open_media_failed))
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
            Ui.toast(this, getString(R.string.msg_unsupported_format))
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
        // "Abrir vídeos em tela separada": the video plays alone, without swiping to the rest of the album.
        val videoAlone = (mime ?: contentResolver.getType(uri))?.startsWith("video/") == true &&
            GalleryPreferences.flag(this, GalleryPreferences.VIDEO_SEPARATE_SCREEN)
        if (!videoAlone) loadAlbumQueueAsync(uri)
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
        queueController.setInitial(MediaItem(0, currentUri, name, mime, 0, 0, path, path ?: sourceAlbumKey, getString(R.string.media_generic)))
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
                refreshMotionPhotoAfterQueueLoad()
            }
        }
    }

    private fun buildLayout() {
        applyWindowSettings()
        val navSideInset = systemBars.side
        val navBottomInset = systemBars.bottom
        val root = FrameLayout(this).apply {
            setBackgroundColor(viewerBackground())
        }

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(
                Ui.dp(this@DetailActivity, 8),
                systemBars.top + Ui.dp(this@DetailActivity, 6),
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
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxEms = 18
        }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@DetailActivity, 4)
            marginEnd = Ui.dp(this@DetailActivity, 20)
        }
        // "Exibir detalhes em tela cheia": date, size and length under the name.
        fullscreenDetails = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(fullscreenDetails, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        topBar.addView(titleColumn, titleParams)

        // The 48-hour clock of a media used in a join; tapping it keeps the media.
        clockBadge = ClockBadge.create(this, 12f, 14, CLOCK_BADGE_DP).apply {
            setOnClickListener { ClockBadge.keep(this, currentItem().uri.toString()) }
        }
        topBar.addView(clockBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, CLOCK_BADGE_DP)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = Ui.dp(this@DetailActivity, 4)
            marginEnd = Ui.dp(this@DetailActivity, 2)
        })

        favoriteButton = iconButton(R.drawable.ic_heart, Ui.dp(this, 44)).apply {
            contentDescription = getString(R.string.action_favorite)
            setOnClickListener { toggleFavorite() }
        }
        if (sourceAlbumKey == VirtualAlbumRules.TRASH_KEY) favoriteButton.visibility = View.GONE
        topBar.addView(favoriteButton, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))

        val more = iconButton(R.drawable.ic_more_vertical, Ui.dp(this, 48)).apply {
            contentDescription = getString(R.string.action_more_options)
            setOnClickListener { showMediaMenu(it) }
        }
        topBar.addView(more, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)))

        var touchClickX = Float.NaN
        var touchClickY = Float.NaN
        content = FrameLayout(this).apply {
            setBackgroundColor(viewerBackground())
            contentDescription = getString(R.string.msg_media_viewer)
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
        systemBars.onChange { if (::topBar.isInitialized) applyBarInsets() }
        videoControls = LinearLayout(this).apply {
            tag = "video_playback_controls"
            gravity = Gravity.CENTER
        }
        playPauseButton = iconButton(R.drawable.ic_play, Ui.dp(this, 46)).apply {
            tag = "video_play_pause"
            background = Ui.rounded(0x66000000, 24, this@DetailActivity)
            setOnClickListener { togglePlayback() }
        }
        bottomBar.addView(videoControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))

        timelineRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        currentTime = timeLabel("00:00")
        durationTime = timeLabel("00:00")
        videoTimeline = VideoTimelineView(this)
        timelineBinding = VideoTimelineBinding(videoTimeline).apply {
            onPosition = { position, duration ->
                currentTime.text = formatTime(position)
                durationTime.text = formatTime(duration)
            }
            onScrubbing = { active ->
                if (active) handler.removeCallbacks(autoAdvanceRunnable)
                updatePlayPauseButton()
            }
        }
        speedButton = timeLabel("1x").apply {
            tag = "video_speed"
            gravity = Gravity.CENTER
            background = Ui.actionFeedback(this@DetailActivity, Color.WHITE)
            setOnClickListener {
                playbackController.setSpeed(DetailPlaybackRules.nextQuickSpeed(playbackSpeed))
                Haptics.perform(this, Haptics.Effect.TICK)
                updateSpeedButton()
            }
            setOnLongClickListener { showSpeedPopup(); true }
        }
        updateSpeedButton()
        timelineRow.addView(currentTime, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 34)))
        timelineRow.addView(TextView(this).apply {
            text = "/"
            textSize = 13f
            setTextColor(0xFFDDDDDD.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 34)))
        timelineRow.addView(durationTime, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 34)))
        // Equal-width side regions keep playback centered independently of the
        // time labels and the speed control, in portrait and landscape alike.
        videoControls.addView(timelineRow, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        videoControls.addView(playPauseButton, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)))
        val speedRegion = FrameLayout(this).apply {
            addView(speedButton, FrameLayout.LayoutParams(Ui.dp(this@DetailActivity, 48),
                Ui.dp(this@DetailActivity, 34), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        videoControls.addView(speedRegion, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        videoProgressRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val progressBar = SeekBar(this).apply {
            tag = "video_progress"
            contentDescription = getString(R.string.video_progress_description)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = progressTintList
        }
        timelineBinding.attachProgressBar(progressBar)
        videoProgressRow.addView(progressBar, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        timelineButton = actionButton(R.drawable.ic_timeline).apply {
            tag = "video_timeline_toggle"
            contentDescription = getString(R.string.video_timeline_show)
            setOnClickListener {
                if (videoTimeline.expansionState == VideoTimelineView.ExpansionState.LOADING) return@setOnClickListener
                videoTimeline.cancelGesture()
                timelineExpanded = !timelineExpanded
                updateTimelineVisibility()
            }
        }
        videoProgressRow.addView(timelineButton, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        // Puts the video in the floating thumbnail right away, playing or paused.
        if (pictureInPictureSupported()) {
            videoProgressRow.addView(actionButton(R.drawable.ic_picture_in_picture).apply {
                tag = "video_picture_in_picture"
                contentDescription = getString(R.string.video_picture_in_picture)
                if (Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
                setOnClickListener { enterPictureInPictureFromButton() }
            }, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        }
        bottomBar.addView(videoProgressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)))
        // Reserve only the filmstrip's space, leaving playback and other actions usable.
        timelineLoadingRow = LinearLayout(this).apply {
            tag = "video_timeline_loading"
            gravity = Gravity.CENTER
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(LoadingIndicatorView(this@DetailActivity, Color.WHITE,
                getString(R.string.video_timeline_loading)),
                LinearLayout.LayoutParams(Ui.dp(this@DetailActivity, 32), Ui.dp(this@DetailActivity, 32)))
        }
        bottomBar.addView(timelineLoadingRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)))
        videoTimeline.onExpansionStateChanged = { state ->
            val loading = state == VideoTimelineView.ExpansionState.LOADING
            timelineLoadingRow.visibility = if (loading) View.VISIBLE else View.GONE
            timelineButton.isEnabled = !loading
            timelineButton.alpha = if (loading) 0.35f else if (timelineExpanded) 1f else 0.65f
            timelineButton.contentDescription = getString(when {
                loading -> R.string.video_timeline_loading
                timelineExpanded -> R.string.video_timeline_hide
                else -> R.string.video_timeline_show
            })
            if (state == VideoTimelineView.ExpansionState.FAILED) {
                timelineExpanded = false
                timelineButton.alpha = 0.65f
                timelineButton.contentDescription = getString(R.string.video_timeline_show)
                Haptics.perform(this, Haptics.Effect.REJECT)
                Ui.toast(this, getString(R.string.video_timeline_load_failed))
            }
        }
        videoTimeline.visibility = View.GONE
        bottomBar.addView(videoTimeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)))

        actionsBar = LinearLayout(this).apply {
            tag = "viewer_actions"
            gravity = Gravity.CENTER
        }
        val edit = actionButton(R.drawable.ic_edit).apply {
            tag = "viewer_edit"
            contentDescription = getString(R.string.action_edit)
            visibility = if (sourceAlbumKey == VirtualAlbumRules.TRASH_KEY) View.GONE else View.VISIBLE
            setOnClickListener {
                when {
                    currentItem().isVideo() -> openCurrentEditor()
                    // A Motion Photo opens straight on choosing its frame.
                    motionPhotoController.clipFor(motionPhotoSource(currentItem())) != null -> openMotionFrameChooser()
                    else -> showImageEditMenu()
                }
            }
        }
        val share = actionButton(R.drawable.ic_share).apply {
            tag = "viewer_share"
            contentDescription = getString(R.string.action_share)
            setOnClickListener { shareCurrent() }
        }
        val trash = actionButton(R.drawable.ic_trash).apply {
            tag = "viewer_delete"
            contentDescription = getString(
                if (sourceAlbumKey == VirtualAlbumRules.TRASH_KEY) R.string.action_delete_permanently
                else R.string.action_delete
            )
            setOnClickListener { SecurityGate.requireFileOperation(this@DetailActivity) { confirmDeleteCurrent() } }
        }
        val restore = actionButton(R.drawable.ic_restore).apply {
            contentDescription = getString(R.string.action_restore)
            visibility = if (sourceAlbumKey == VirtualAlbumRules.TRASH_KEY) View.VISIBLE else View.GONE
            setOnClickListener { restoreCurrent() }
        }
        if (sourceAlbumKey == VirtualAlbumRules.TRASH_KEY) {
            share.visibility = View.GONE
        }
        soundButton = actionButton(R.drawable.ic_volume_on).apply {
            tag = "viewer_sound"
            contentDescription = getString(R.string.msg_mute)
            setOnClickListener { toggleVideoSound() }
        }
        cinemaButton = actionButton(R.drawable.ic_movie).apply {
            tag = CINEMA_BUTTON_TAG
            contentDescription = getString(R.string.viewer_cinema_mode)
            visibility = View.GONE
            setOnClickListener { toggleCinemaMode() }
        }
        motionPhotoButton = actionButton(R.drawable.ic_motion_photo).apply {
            tag = "viewer_motion_photo"
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                Ui.rounded(0x99000000.toInt(), 28, this@DetailActivity),
                Ui.rounded(Color.WHITE, 28, this@DetailActivity)
            )
            contentDescription = getString(R.string.action_motion_photo)
            visibility = View.GONE
            setOnClickListener { toggleInlineMotionPhoto() }
        }
        textRecognitionButton = actionButton(R.drawable.ic_text_recognition).apply {
            tag = "ocr_text_action"
            contentDescription = getString(R.string.action_recognize_text)
            visibility = View.GONE
            setOnClickListener { recognizeCurrentImageText(currentItem()) }
        }
        imageInformationButton = actionButton(R.drawable.ic_information).apply {
            tag = "viewer_information"
            contentDescription = getString(R.string.action_information)
            visibility = View.GONE
            setOnClickListener { showCurrentImageInformation() }
        }
        // Mutually exclusive photo/video actions share positions 2 and 3.
        // Optional OCR detection and Motion Photo must not shift this row.
        actionsBar.addView(edit, actionParams())
        actionsBar.addView(textRecognitionButton, actionParams())
        actionsBar.addView(soundButton, actionParams())
        actionsBar.addView(imageInformationButton, actionParams())
        actionsBar.addView(cinemaButton, actionParams())
        actionsBar.addView(share, actionParams())
        actionsBar.addView(restore, actionParams())
        actionsBar.addView(trash, actionParams())
        bottomBar.addView(actionsBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.ACTION_TOUCH_HEIGHT_DP)))
        if (!GalleryPreferences.flag(this, GalleryPreferences.SHOW_BOTTOM_ACTIONS)) actionsBar.visibility = View.GONE

        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        motionPhotoOverlay = FrameLayout(this).apply {
            tag = "motion_photo_overlay"
            addView(motionPhotoButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(motionPhotoOverlay, FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = Ui.dp(this@DetailActivity, 18)
        })
        bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = motionPhotoOverlay.layoutParams as FrameLayout.LayoutParams
            val margin = bottomBar.height + Ui.dp(this, 12)
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin
                motionPhotoOverlay.layoutParams = params
            }
        }
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
        ClickFeedbackImageButton(this).apply {
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
        val key = item.uri.toString()
        val known = if (sourceAlbumKey == VirtualAlbumRules.TRASH_KEY) false else separableCache[key]
        if (known != null) {
            showResolvedMediaMenu(anchor, item, null, known)
            return
        }
        executor.execute {
            val separable = MediaSplitRules.separable(MediaJoinExporter.readRecord(this, item.uri))
            runOnUiThread {
                separableCache[key] = separable
                if (!isFinishing && anchor.isAttachedToWindow && currentItem().uri == item.uri) {
                    showResolvedMediaMenu(anchor, item, null, separable)
                }
            }
        }
    }

    private fun showResolvedMediaMenu(anchor: View, item: MediaItem, imageMetadata: DetailImageMetadata?, separable: Boolean = false) {
        val loopEnabled = playbackController.isLooping() ||
            ViewerStateRules.shouldLoopVideo(
                shuffleMode,
                presentationMode,
                prefs.getBoolean(GalleryPreferences.LOOP_VIDEOS, false)
            )
        Ui.showPopupOptions(
            anchor,
            ViewerMenuRules.options(
                isVideo = item.isVideo(),
                loopEnabled = loopEnabled,
                shuffleMode = shuffleMode || presentationMode,
                hasLocation = imageMetadata?.hasLocation == true,
                cinemaMode = cinemaMode,
                separable = separable
            ),
            widthDp = 216,
            label = { ViewerMenuLabels.translate(this, it) }
        ) { selected ->
            when (selected) {
                ViewerMenuRules.RENAME -> SecurityGate.requireFileOperation(this) { askRenameCurrentImage() }
                ViewerMenuRules.OPEN_WITH -> openCurrentWithAnotherApp()
                ViewerMenuRules.COPY_TO -> askFolderForCopyOrMove(true, anchor)
                ViewerMenuRules.MOVE_TO -> SecurityGate.requireFileOperation(this) { askFolderForCopyOrMove(false, anchor) }
                ViewerMenuRules.HIDE -> SecurityGate.requireFileOperation(this) { confirmHideCurrent() }
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
                ViewerMenuRules.RECOGNIZE_TEXT -> recognizeCurrentImageText(item)
                ViewerMenuRules.SEPARATE -> SecurityGate.requireFileOperation(this) { confirmSeparateCurrent() }
            }
        }
    }

    private fun recognizeCurrentImageText(item: MediaItem, point: android.graphics.PointF? = null) {
        if (!item.isImage()) return
        if (point == null && closeOcrSelection()) return
        pendingOcrPoint = point
        textRecognitionController.requestManual(textSource(item))
    }

    private fun clearOcrProgress() {
        ocrProgress?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ocrProgress = null
        if (::textRecognitionButton.isInitialized) textRecognitionButton.isEnabled = true
    }

    internal fun closeOcrSelection(): Boolean {
        val selection = ocrSelection ?: return false
        selection.close()
        ocrSelection = null
        return true
    }

    private fun showRecognizedText(document: OcrDocument) {
        val page = activePage ?: return
        val image = activeZoomImage() ?: return
        closeOcrSelection()
        lateinit var selection: OcrSelectionOverlay
        selection = OcrSelectionOverlay(this, document, {
            android.graphics.Matrix(image.imageMatrix).apply {
                preScale((image.drawable?.intrinsicWidth ?: 0).toFloat(), (image.drawable?.intrinsicHeight ?: 0).toFloat())
                postTranslate(image.left + image.paddingLeft.toFloat(), image.top + image.paddingTop.toFloat())
            }
        }, image) { if (ocrSelection === selection) ocrSelection = null }
        ocrSelection = selection
        selection.attachTo(page)
        pendingOcrPoint?.let { selection.selectAt(it.x, it.y, continueDrag = true) }
        pendingOcrPoint = null
    }

    private fun textCacheKey(item: MediaItem) = "${MediaContentRevision.key(this, item.uri)}|${item.size}|${item.dateAdded}"

    private fun textSource(item: MediaItem) =
        ViewerTextRecognitionController.Image(item.uri.toString(), textCacheKey(item))

    private fun scheduleTextDetection(item: MediaItem?) {
        closeOcrSelection()
        clearOcrProgress()
        val photoVisibility = if (item?.isImage() == true) View.VISIBLE else View.GONE
        textRecognitionButton.visibility = photoVisibility
        imageInformationButton.visibility = photoVisibility
        textRecognitionController.detect(item?.takeIf { it.isImage() }?.let(::textSource))
    }

    private fun toggleVideoLoop() {
        if (shuffleMode || !currentItem().isVideo()) return
        val enabled = !playbackController.isLooping()
        prefs.edit().putBoolean(GalleryPreferences.LOOP_VIDEOS, enabled).apply()
        playbackController.setLooping(enabled)
        Ui.toast(this, getString(if (enabled) R.string.msg_loop_on else R.string.msg_loop_off))
    }

    private fun handleSwipeOrTap(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(startHoldSpeed)
            if (holdSpeedActive) {
                // Lifting the finger only ends the 2x boost; it is neither a tap nor a swipe.
                endHoldSpeed()
                completedViewerTap = false
                cinemaGestureController.cancel()
                cancelInteractiveSwipe()
                return true
            }
        }
        // While boosted, moving the finger never starts another gesture. Pulling down far enough locks the 2x.
        if (holdSpeedActive) {
            if (action == MotionEvent.ACTION_MOVE && !holdSpeedLocked &&
                HoldSpeedRules.locksBoost(holdDownY, event.rawY, Ui.dp(this, HoldSpeedRules.LOCK_DISTANCE_DP).toFloat())
            ) {
                holdSpeedLocked = true
                Haptics.perform(this, Haptics.Effect.CONFIRM)
            }
            return true
        }
        if (action == MotionEvent.ACTION_MOVE &&
            HoldSpeedRules.movedBeforeHold(holdDownX, holdDownY, event.rawX, event.rawY, (gestureTouchSlop * 2).toInt())
        ) handler.removeCallbacks(startHoldSpeed)
        if (switchingItem) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                completedViewerTap = false
                beginPointerGesture(event)
                holdDownX = event.rawX
                holdDownY = event.rawY
                handler.removeCallbacks(startHoldSpeed)
                handler.postDelayed(startHoldSpeed, ViewConfiguration.getLongPressTimeout().toLong())
                armVerticalAdjust(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (feedVerticalAdjust(event, finishing = false)) return true
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
                if (feedVerticalAdjust(event, finishing = true)) {
                    completedViewerTap = false
                    return true
                }
                updateInteractiveSwipe(event)
                if (dragPreviewPage != null) {
                    completedViewerTap = false
                    finishInteractiveSwipe()
                } else {
                    completedViewerTap = swipeGestureController.isTap(event.rawX, event.rawY, gestureTouchSlop)
                }
                return true
            }
        }
        return true
    }

    private var holdSpeedActive = false
    private var holdSpeedLocked = false
    private var holdSpeedPrevious = 1f
    private var holdDownX = 0f
    private var holdDownY = 0f
    private val startHoldSpeed = Runnable { beginHoldSpeed() }

    private fun beginHoldSpeed() {
        if (holdSpeedActive || isFinishing || !::content.isInitialized) return
        val allowed = HoldSpeedRules.canBoost(
            isVideo = mediaQueue.getOrNull(currentIndex)?.isVideo() == true,
            portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
            cinemaMode = cinemaMode,
            playing = playbackController.player()?.playWhenReady == true,
            swipingToAnotherMedia = dragPreviewPage != null || switchingItem,
            inPictureInPicture = isInPictureInPictureModeCompat()
        )
        if (!allowed) return
        holdSpeedActive = true
        holdSpeedLocked = false
        holdSpeedPrevious = playbackSpeed
        cancelPendingSingleTap()
        cinemaGestureController.cancel()
        playbackController.setSpeed(HoldSpeedRules.BOOST_SPEED)
        updateSpeedButton()
        Haptics.perform(this, Haptics.Effect.LONG_PRESS)
        // Keep the touch stream here so no parent view turns the drag into another gesture.
        activePage?.requestDisallowInterceptTouchEvent(true)
        // The speed shows only on the usual label in the lower right corner, so bring the controls up.
        holdSpeedRevealedHud = !hudVisible
        if (holdSpeedRevealedHud) toggleHud()
    }

    private var holdSpeedRevealedHud = false

    private fun endHoldSpeed() {
        handler.removeCallbacks(startHoldSpeed)
        if (!holdSpeedActive) return
        holdSpeedActive = false
        playbackController.setSpeed(HoldSpeedRules.speedAfterRelease(holdSpeedPrevious, holdSpeedLocked))
        holdSpeedLocked = false
        updateSpeedButton()
        if (holdSpeedRevealedHud && hudVisible) toggleHud()
        holdSpeedRevealedHud = false
        activePage?.requestDisallowInterceptTouchEvent(false)
        Haptics.perform(this, Haptics.Effect.TICK)
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
            if (!changeMediaBySideTap(x)) toggleHud()
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

    internal fun cancelPendingSingleTap() {
        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    private fun beginPointerGesture(event: MotionEvent) {
        swipeGestureController.start(event.rawX, event.rawY)
        closeGestureDownY = event.rawY
        closeGestureActive = false
    }

    // "Fechar com gesto para baixo": pulling the media down follows the finger and closes past a threshold.
    private var closeGestureActive = false
    private var closeGestureDownY = 0f

    private fun updateCloseGesture(event: MotionEvent) {
        val page = activePage ?: return
        val distance = (event.rawY - closeGestureDownY).coerceAtLeast(0f)
        page.translationY = distance
        page.alpha = 1f - (distance / content.height.coerceAtLeast(1)).coerceIn(0f, 0.6f)
    }

    /** Returns true when a pull-down gesture was in progress (closing or snapping back). */
    private fun finishCloseGesture(): Boolean {
        if (!closeGestureActive) return false
        closeGestureActive = false
        val page = activePage
        val distance = page?.translationY ?: 0f
        if (ViewerGestureRules.closesOnRelease(distance, content.height.toFloat(), Ui.dp(this, 120).toFloat())) {
            Haptics.perform(this, Haptics.Effect.CONFIRM)
            resetSpeedAndFinish()
        } else {
            page?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(160L)?.start()
        }
        return true
    }

    private fun updateInteractiveSwipe(event: MotionEvent) {
        if (closeGestureActive) { updateCloseGesture(event); return }
        if (operations.busy || switchingItem) return
        if (dragPreviewPage == null && GalleryPreferences.flag(this, GalleryPreferences.SWIPE_DOWN_TO_CLOSE)) {
            val intent = swipeGestureController.resolveIntent(event.rawX, event.rawY, gestureTouchSlop)
            if (intent != null && ViewerGestureRules.isPullDown(intent)) {
                closeGestureActive = true
                cancelPendingSingleTap()
                updateCloseGesture(event)
                return
            }
        }
        if (mediaQueue.size < 2) return
        if (dragPreviewPage == null) {
            val intent = swipeGestureController.resolveIntent(event.rawX, event.rawY, gestureTouchSlop) ?: return
            dragTargetIndex = wrappedIndex(currentIndex + intent.direction)
            beginInteractiveSwipe()
        }
        val offset = swipeOffset()
        val translation = swipeGestureController.translate(event.rawX, event.rawY, offset.toFloat())
        if (dragHorizontal) {
            activePage?.translationX = translation.current
            dragPreviewPage?.translationX = translation.incoming
        } else {
            activePage?.translationY = translation.current
            dragPreviewPage?.translationY = translation.incoming
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
        if (finishCloseGesture()) return
        if (switchingItem) return
        val offset = swipeOffset()
        val shouldCommit = swipeGestureController.shouldCommit(gestureTouchSlop)
        if (shouldCommit) {
            commitInteractiveSwipe(offset)
        } else {
            cancelInteractiveSwipe(offset)
        }
    }

    private fun commitInteractiveSwipe(offset: Int) {
        if (operations.busy) { cancelInteractiveSwipe(offset); return }
        if (switchingItem) return
        val incomingPage = dragPreviewPage ?: return
        val outgoingPage = activePage
        if (!mediaTransitionController.begin()) return
        scheduleTextDetection(null)
        clearMotionPhoto()
        zoomed = false
        val outgoingPlayer = playbackController.detachCurrent()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
        currentIndex = dragTargetIndex
        preloadGeneration++
        val completion = registerMediaTransition(outgoingPage, incomingPage, outgoingPlayer, 165L)
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
            .withEndAction(completion)
            .start()
    }

    // A committed transition owns its pages/player until this idempotent completion.
    // Do not rely solely on ViewPropertyAnimator's end action: cancellation skips it.
    private fun registerMediaTransition(
        outgoingPage: FrameLayout?,
        incomingPage: FrameLayout,
        outgoingPlayer: ExoPlayer?,
        durationMs: Long
    ): Runnable {
        return mediaTransitionController.register(durationMs, cleanup = { updateUi ->
            outgoingPage?.animate()?.cancel()
            incomingPage.animate().cancel()
            outgoingPage?.let { content.removeView(it) }
            playbackController.releaseDetached(outgoingPlayer)
            incomingPage.translationX = 0f
            incomingPage.translationY = 0f
            activePage = incomingPage
            resetInteractiveSwipeState()
            if (updateUi) applyCurrentUiAfterInteractiveSwipe()
        }, onSettled = { updateUi ->
            if (updateUi) {
                scheduleAdjacentPreload()
                scheduleShuffleAdvance()
            }
        })
    }

    private fun finishMediaTransition(updateUi: Boolean = true) {
        mediaTransitionController.finish(updateUi)
    }

    private fun cancelInteractiveSwipe(offset: Int = swipeOffset()) {
        if (closeGestureActive) {
            closeGestureActive = false
            activePage?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(160L)?.start()
        }
        // The queue index has already advanced; cancelling a drag must not undo
        // or replace the completion of a confirmed media change.
        if (switchingItem) return
        val incomingPage = dragPreviewPage
        val incomingTarget = if (dragDirection > 0) offset.toFloat() else -offset.toFloat()
        val horizontal = dragHorizontal
        resetInteractiveSwipeState()
        val interpolator = DecelerateInterpolator(1.35f)
        activePage?.animate()
            ?.translationX(0f)
            ?.translationY(0f)
            ?.setInterpolator(interpolator)
            ?.setDuration(150)
            ?.start()
        incomingPage?.animate()
            ?.translationX(if (horizontal) incomingTarget else 0f)
            ?.translationY(if (horizontal) 0f else incomingTarget)
            ?.setInterpolator(interpolator)
            ?.setDuration(150)
            ?.withEndAction {
                content.removeView(incomingPage)
            }
            ?.start()
    }

    private fun resetInteractiveSwipeState() {
        dragPreviewPage = null
        dragTargetIndex = -1
        swipeGestureController.resetDrag()
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
        updateFullscreenDetails(item)
        updateFavoriteButton()
        if (item.isVideo()) {
            content.removeAllViews()
            activePage = createCurrentPage()
            content.addView(activePage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            cinemaController.open(isVideo = false)
            videoControls.visibility = View.GONE
            timelineRow.visibility = View.GONE
            videoTimeline.visibility = View.GONE
            videoProgressRow.visibility = View.GONE
            timelineBinding.unbind()
            soundButton.visibility = View.GONE
            cinemaButton.visibility = View.GONE
            videoTrackController.unbind()
            activePage?.let { promoteImagePage(item, it) }
            updateMotionPhotoAction(item)
            scheduleTextDetection(item)
        }
    }

    internal fun toggleHud() {
        if (!::topBar.isInitialized || !::bottomBar.isInitialized) return
        hudVisible = !hudVisible
        val show = hudVisible
        animateHudView(topBar, show)
        animateHudView(bottomBar, show)
        animateHudView(motionPhotoOverlay, show)
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

    internal fun switchItem(direction: Int, horizontal: Boolean) {
        if (operations.busy || switchingItem || mediaQueue.size < 2) return
        cancelInteractiveSwipe()
        if (!mediaTransitionController.begin()) return
        scheduleTextDetection(null)
        clearMotionPhoto()
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
        val completion = registerMediaTransition(outgoingPage, incomingPage, outgoingPlayer, 245L)

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
            .withEndAction(completion)
            .start()
    }

    internal fun loadCurrentItem() {
        finishMediaTransition(updateUi = false)
        resetInteractiveSwipeState()
        cancelPendingSingleTap()
        zoomed = false
        timelineBinding.unbind()
        playbackController.releaseCurrent()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        speedPopup?.dismiss()
        content.removeAllViews()
        activePage = createCurrentPage()
        content.addView(activePage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun createCurrentPage(): FrameLayout {
        val item = currentItem()
        val page = FrameLayout(this).apply { setBackgroundColor(pageBackground(item)) }
        cinemaController.open(item.isVideo(), item.isVideo() && cinemaPreferences.isEnabled(cinemaAlbumKey(item)),
            item.isVideo() && automaticCinemaLandscape(resources.configuration))
        title.text = item.name
        updateFullscreenDetails(item)
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
        val page = FrameLayout(this).apply { setBackgroundColor(pageBackground(item)) }
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
        scheduleTextDetection(null)
        clearMotionPhoto()
        videoControls.visibility = View.VISIBLE
        timelineRow.visibility = View.VISIBLE
        videoProgressRow.visibility = View.VISIBLE
        updateTimelineVisibility()
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
            tag = "detail_video_player"
            alpha = 0f
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
            setKeepContentOnPlayerReset(true)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        page.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // PlayerView/SurfaceView can consume the stream even with useController=false.
        // Keep viewer swipes, taps and cinema gestures above the playback surface.
        page.addView(View(this).apply {
            tag = "video_gesture_surface"
            isClickable = true
            setOnClickListener { handleTap(width / 2f, height / 2f) }
            setOnTouchListener { view, event ->
                handleSwipeOrTap(event)
                if (event.actionMasked == MotionEvent.ACTION_UP && completedViewerTap) {
                    completedViewerTap = false
                    handleTap(event.x, event.y)
                }
                true
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val player = playbackController.start(
            uri = item.uri,
            loop = ViewerStateRules.shouldLoopVideo(
                shuffleMode,
                presentationMode,
                prefs.getBoolean(GalleryPreferences.LOOP_VIDEOS, false)
            ),
            defaultPlayWhenReady = shuffleMode || presentationMode ||
                prefs.getBoolean(GalleryPreferences.AUTOPLAY_VIDEOS, true),
            rememberPosition = !shuffleMode && !presentationMode,
            listener = object : DetailPlaybackController.Listener {
                override fun onPlaybackChanged() {
                    updatePlayPauseButton()
                    updateTimeline()
                    updatePictureInPictureActions()
                }

                override fun onPlaybackEnded() {
                    handler.removeCallbacks(autoAdvanceRunnable)
                    handler.postDelayed(autoAdvanceRunnable, 250L)
                }

                override fun onFirstFrame() {
                    // The video shape is known now: give the thumbnail the right orientation.
                    playerView.post { updatePictureInPictureActions() }
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
        timelineBinding.bind(player, item.uri, playbackController.commandChannel, "${item.size}|${item.dateAdded}")
        if (cinemaMode) videoTrackController.bind(player, cinemaAlbumKey(item)) else videoTrackController.unbind()
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

    internal fun scheduleAdjacentPreload() {
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
            .apply { ImageRotation.configureRequest(this@DetailActivity, item, this) }
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
        return "viewer_preview:${MediaContentRevision.key(this, item.uri)}:${metrics.widthPixels}x${metrics.heightPixels}"
    }

    private fun viewerNativeKey(item: MediaItem): String = "viewer_native:${MediaContentRevision.key(this, item.uri)}"

    private fun viewerSourceKey(item: MediaItem): String = "viewer_source:${MediaContentRevision.key(this, item.uri)}"

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

    internal fun scheduleShuffleAdvance() {
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
        videoTimeline.visibility = View.GONE
        videoProgressRow.visibility = View.GONE
        timelineBinding.unbind()
        soundButton.visibility = View.GONE
        cinemaButton.visibility = View.GONE
        videoTrackController.unbind()
        addImagePreviewToPage(item, page)
        promoteImagePage(item, page)
        updateMotionPhotoAction(item)
        scheduleTextDetection(item)
    }

    // Plays the clip on the photo itself; created on first use.
    private var inlineMotionPhoto: InlineMotionPhotoPlayer? = null

    private fun clearMotionPhoto() {
        inlineMotionPhoto?.stop(animated = false)
        motionPhotoController.select(null)
    }

    /** The footer button plays the clip in place, or stops it when it is already playing. */
    private fun toggleInlineMotionPhoto() {
        val player = inlineMotionPhoto ?: InlineMotionPhotoPlayer(this, motionPhotoExecutor, { videoMuted },
            ::showMotionPhotoPlaying) { Ui.toast(this, getString(R.string.motion_photo_error)) }.also { inlineMotionPhoto = it }
        if (player.isPlaying) { player.stop(animated = true); return }
        val item = currentItem()
        val clip = motionPhotoController.clipFor(motionPhotoSource(item)) ?: return
        val page = activePage ?: return
        val image = activeZoomImage() ?: return
        player.play(page, image, item.uri, clip)
    }

    private fun showMotionPhotoPlaying(playing: Boolean) {
        if (!::motionPhotoButton.isInitialized) return
        motionPhotoButton.isActivated = playing
        motionPhotoButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_motion_photo)
        motionPhotoButton.contentDescription =
            getString(if (playing) R.string.action_motion_photo_stop else R.string.action_motion_photo)
    }

    /**
     * The viewer opens before the album is read, with the photo's size still unknown; the loaded
     * queue gives the photo its full identity. Check it again (from the cache, so it is quick) and
     * look ahead at the real neighbours, which the one-item queue did not have.
     */
    private fun refreshMotionPhotoAfterQueueLoad() {
        if (!::motionPhotoButton.isInitialized) return
        val item = mediaQueue.getOrNull(currentIndex)?.takeIf { it.isImage() } ?: return
        if (inlineMotionPhoto?.isPlaying == true) return
        if (motionPhotoController.clipFor(motionPhotoSource(item)) == null) updateMotionPhotoAction(item)
        else prefetchMotionPhotoNeighbours()
    }

    private fun updateMotionPhotoAction(item: MediaItem) {
        inlineMotionPhoto?.stop(animated = false)
        motionPhotoController.select(motionPhotoSource(item))
        prefetchMotionPhotoNeighbours()
    }

    /** Checks the photos next to the current one, so their button is ready when the user swipes. */
    private fun prefetchMotionPhotoNeighbours() {
        val generation = ++motionPhotoPrefetchGeneration
        val neighbours = MotionPhotoPrefetchRules.neighbours(currentIndex, mediaQueue.size)
            .mapNotNull { mediaQueue.getOrNull(it)?.let(::motionPhotoSource) }
        if (neighbours.isEmpty()) return
        val context = applicationContext
        runCatching { motionPhotoPrefetchExecutor.execute {
            for (source in neighbours) {
                val relevant = { generation == motionPhotoPrefetchGeneration && !isDestroyed }
                if (!relevant()) return@execute
                runCatching { MotionPhotoSupport.detect(context, source.uri.toUri(), relevant) }
            }
        } }
    }

    private fun motionPhotoSource(item: MediaItem): ViewerMotionPhotoController.Source? =
        if (item.mimeType in setOf("image/jpeg", "image/heic", "image/heif")) {
            item.uri.toString().let { uri ->
                // Same photo whichever MediaStore form the queue holds (images/media/id or file/id).
                val revision = MediaContentRevision.key(this, item.uri).removePrefix(uri)
                ViewerMotionPhotoController.Source(uri, "${MediaIdentityRules.canonicalKey(uri)}$revision:${item.size}")
            }
        } else null

    private fun toggleVideoSound() {
        if (!currentItem().isVideo()) return
        playbackController.toggleMuted()
        Haptics.perform(this, Haptics.toggle(!videoMuted))
        updateSoundButton()
    }

    private fun updateSoundButton() {
        if (!::soundButton.isInitialized) return
        soundButton.setImageResource(if (videoMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        soundButton.contentDescription = getString(if (videoMuted) R.string.msg_unmute else R.string.msg_mute)
        soundButton.alpha = if (videoMuted) 0.62f else 1f
    }

    private fun toggleCinemaMode() {
        val wasCinema = cinemaMode
        cinemaController.toggle(currentItem().isVideo(), switchingItem)
        Haptics.perform(this, Haptics.toggle(if (cinemaMode != wasCinema) cinemaMode else !wasCinema))
    }

    private fun viewerRotationPreference(): Int = when (ChoicePreferences.code(this, ChoicePreferences.ROTATION)) {
        "sensor" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun automaticCinemaLandscape(config: Configuration): Boolean {
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        return when (viewerRotationPreference()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR -> true
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ->
                Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            else -> false
        }
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

    private fun animateCinemaModeTransition() {
        cinemaButton.isEnabled = false
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
                .setDuration(ViewerCinemaController.ROTATION_DELAY_MS)
                .start()
        }
    }

    private fun completeCinemaModeTransition() {
        listOf(content, topBar, bottomBar).forEach { view ->
            view.animate().cancel()
            view.animate().alpha(1f)
                .setDuration(CINEMA_CONTENT_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        cinemaButton.isEnabled = true
        handler.removeCallbacks(hideCinemaModeIndicator)
        handler.postDelayed(hideCinemaModeIndicator, CINEMA_MESSAGE_VISIBLE_MS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::content.isInitialized) return
        applyViewerSystemBars()
        cancelPendingSingleTap()
        cinemaGestureController.cancel()
        handler.removeCallbacks(hideCinemaGestureIndicator)
        cinemaGestureIndicator.visibility = View.GONE
        if (!switchingItem) cancelInteractiveSwipe()
        speedPopup?.dismiss()
        applyBarInsets()
        cinemaController.settle()
        val video = mediaQueue.getOrNull(currentIndex)?.isVideo() == true
        cinemaController.followRotation(video, video && automaticCinemaLandscape(newConfig), switchingItem)
    }

    private val rotationGesture = RotationGestureTracker()

    // Results from the write permission and the PDF file picker (Activity Result API).
    internal val renameWriteLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) {
        onRenameWriteResult(it.resultCode == RESULT_OK)
    }
    // The frame screen rewrites the photo in place; show the new version when it comes back.
    private val motionFrameLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || isFinishing || isDestroyed) return@registerForActivityResult
        val item = mediaQueue.getOrNull(currentIndex) ?: return@registerForActivityResult
        imageMetadataCache.remove(item.uri.toString())
        imagePreloadJobs.values.forEach { it.cancel() }
        imagePreloadJobs.clear()
        loadCurrentItem()
        scheduleAdjacentPreload()
    }

    private fun openMotionFrameChooser() {
        val item = currentItem()
        inlineMotionPhoto?.stop(animated = false)
        motionFrameLauncher.launch(Intent(this, MotionFrameActivity::class.java).apply {
            putExtra(MotionFrameActivity.EXTRA_URI, item.uri.toString())
            putExtra(MotionFrameActivity.EXTRA_MIME, item.mimeType)
            putExtra(MotionFrameActivity.EXTRA_NAME, item.name)
        })
    }

    internal val rotateWriteLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) {
        onRotateWriteResult(it.resultCode == RESULT_OK)
    }
    internal val createPdfLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val item = pendingPdfItem
        pendingPdfItem = null
        if (uri != null && item != null) writePdf(uri, item)
    }

    internal fun launchSender(launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>, sender: android.content.IntentSender) =
        launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())

    /** "Fundo preto em tela cheia": black behind the media, or the theme background when off. */
    private fun viewerBackground(): Int =
        if (GalleryPreferences.flag(this, GalleryPreferences.FULLSCREEN_BLACK_BG)) Color.BLACK else Ui.bg(this)

    /** Videos always play on black, like the cinema mode; photos follow "Fundo preto em tela cheia". */
    private fun pageBackground(item: MediaItem): Int = if (item.isVideo()) Color.BLACK else viewerBackground()

    private fun updateFullscreenDetails(item: MediaItem) {
        if (!::fullscreenDetails.isInitialized) return
        if (!GalleryPreferences.flag(this, GalleryPreferences.SHOW_FULLSCREEN_DETAILS)) {
            fullscreenDetails.visibility = View.GONE
            return
        }
        val parts = buildList {
            if (item.dateAdded > 0L) add(DateTimeDisplay.format(this@DetailActivity, item.dateAdded * 1000L))
            if (item.size > 0L) add(Formatter.formatFileSize(this@DetailActivity, item.size))
            if (item.isVideo() && item.duration > 0L) add(formatTime(item.duration))
        }
        fullscreenDetails.text = parts.joinToString("  ·  ")
        fullscreenDetails.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    /** "Trocar mídia tocando nas laterais": a tap near the left or right edge goes to the previous or next media. */
    private fun changeMediaBySideTap(x: Float): Boolean {
        if (!GalleryPreferences.flag(this, GalleryPreferences.TAP_SIDES_CHANGE_MEDIA) || mediaQueue.size < 2) return false
        val direction = ViewerGestureRules.sideTapDirection(x, content.width.toFloat())
        if (direction == 0) return false
        Haptics.perform(this, Haptics.Effect.TICK)
        switchItem(direction, true)
        return true
    }

    private fun cinemaGesturesEnabled(): Boolean =
        cinemaMode && currentItem().isVideo() && !switchingItem &&
            GalleryPreferences.flag(this, GalleryPreferences.VIDEO_VERTICAL_GESTURES)

    // Vertical drag that adjusts brightness or volume: in cinema mode (both sides) or, with
    // "Controle de brilho na vertical", brightness on the left half of any photo or video.
    private var verticalAdjustArmed = false

    private fun armVerticalAdjust(event: MotionEvent): Boolean {
        verticalAdjustArmed = !switchingItem && ViewerGestureRules.verticalAdjustAllowed(
            cinemaGestures = cinemaGesturesEnabled(),
            brightnessGesture = !cinemaMode && GalleryPreferences.flag(this, GalleryPreferences.VERTICAL_BRIGHTNESS_GESTURE),
            x = event.x,
            width = content.width.toFloat()
        )
        if (verticalAdjustArmed) {
            cinemaGestureController.start(
                event.x, event.y, content.width.toFloat(), content.height.toFloat(),
                currentBrightnessFraction(), currentVolumeFraction()
            )
        }
        return verticalAdjustArmed
    }

    /** Feeds the gesture; returns true when it consumed the touch (the finger is adjusting). */
    private fun feedVerticalAdjust(event: MotionEvent, finishing: Boolean): Boolean {
        if (!verticalAdjustArmed) return false
        val update = cinemaGestureController.update(event.x, event.y, gestureTouchSlop)
        if (!finishing) {
            if (update.consumed) update.kind?.let { applyCinemaGesture(it, update.fraction) }
            return update.consumed
        }
        update.kind?.let { applyCinemaGesture(it, update.fraction) }
        verticalAdjustArmed = false
        if (!cinemaGestureController.finish()) return false
        handler.removeCallbacks(hideCinemaGestureIndicator)
        handler.postDelayed(hideCinemaGestureIndicator, CINEMA_INDICATOR_HIDE_MS)
        return true
    }

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
        cinemaController.settle()
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
            setBackgroundColor(viewerBackground())
            tag = IMAGE_PREVIEW_TAG
        }
        page.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview.load(item.uri) {
            ImageRotation.configureRequest(this@DetailActivity, item, this)
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
            setBackgroundColor(viewerBackground())
            // "Zoom aprofundado" off keeps the zoom shallow; "Zoom 1:1 com dois toques duplos" adds the 1:1 step.
            if (!GalleryPreferences.flag(context, GalleryPreferences.DEEP_IMAGE_ZOOM)) {
                zoomable.setScalesCalculator(com.github.panpf.zoomimage.zoom.ScalesCalculator.fixed(2f))
            }
            zoomable.setThreeStepScale(GalleryPreferences.flag(context, GalleryPreferences.DOUBLE_DOUBLE_TAP_ZOOM))
            scrollBar = null
            alpha = 0f
            accessibilityClickAction = { toggleHud() }
        }
        page.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image.tag = item.uri
        val imageGestures = ImageGestureArbiter()
        // Use ZoomImage's detector: it owns the same stream as pan, pinch and
        // double-tap instead of competing with a second delayed View callback.
        image.onViewLongPressListener = OnViewLongPressListener { _, offset ->
            if (switchingItem || isFinishing || isDestroyed || !image.isAttachedToWindow || activePage !== page)
                return@OnViewLongPressListener
            val current = mediaQueue.getOrNull(currentIndex) ?: return@OnViewLongPressListener
            // Loading the album can replace /images/media/id with /file/id.
            // Compare media identity, then pass the current queue item to OCR;
            // its controller must not receive the obsolete URI/cache key.
            if (imageGestures.claimText { MediaIdentityRules.sameUri(current.uri.toString(), item.uri.toString()) }) {
                cancelPendingSingleTap()
                Haptics.perform(image, Haptics.Effect.LONG_PRESS)
                recognizeCurrentImageText(current, android.graphics.PointF(offset.x, offset.y))
            }
        }
        image.setOnTouchListener { _, event ->
            ocrSelection?.takeIf { it.parent === page }?.let { return@setOnTouchListener it.onTouchEvent(event) }
            if (switchingItem) {
                imageGestures.cancel()
                return@setOnTouchListener true
            }
            val atBaseScale = isAtBaseZoom(image)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    imageGestures.start(event.x, event.y)
                    beginPointerGesture(event)
                    armVerticalAdjust(event)
                    rotationGesture.start()
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (dragPreviewPage != null) cancelInteractiveSwipe()
                    imageGestures.pointerDown()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && feedVerticalAdjust(event, finishing = false)) return@setOnTouchListener true
                    if (event.pointerCount == 2 && GalleryPreferences.flag(this, GalleryPreferences.IMAGE_ROTATION_GESTURES)) {
                        rotationGesture.update(event)?.let { quarterTurns ->
                            Haptics.perform(image, Haptics.Effect.TICK)
                            lifecycleScope.launch { image.zoomable.rotateBy(90 * quarterTurns) }
                        }
                    }
                    val wasPaging = imageGestures.paging
                    val route = imageGestures.move(event.x, event.y, event.pointerCount, atBaseScale, gestureTouchSlop)
                    if (route == ImageGestureArbiter.MoveRoute.TEXT) return@setOnTouchListener true
                    if (route == ImageGestureArbiter.MoveRoute.IMAGE_ZOOM) {
                        if (wasPaging || dragPreviewPage != null) cancelInteractiveSwipe()
                        false
                    } else {
                        updateInteractiveSwipe(event)
                        if ((dragPreviewPage != null || closeGestureActive) && imageGestures.claimPaging()) {
                            val cancelEvent = MotionEvent.obtain(event).apply {
                                action = MotionEvent.ACTION_CANCEL
                            }
                            image.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        imageGestures.paging
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (feedVerticalAdjust(event, finishing = true)) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        image.onTouchEvent(cancel)
                        cancel.recycle()
                        imageGestures.cancel()
                        return@setOnTouchListener true
                    }
                    if (finishCloseGesture()) {
                        imageGestures.cancel()
                        return@setOnTouchListener true
                    }
                    val release = imageGestures.release()
                    val consumed = when (release) {
                        ImageGestureArbiter.Release.TEXT -> {
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            image.onTouchEvent(cancel)
                            cancel.recycle()
                            true
                        }
                        ImageGestureArbiter.Release.GALLERY -> {
                            updateInteractiveSwipe(event)
                            finishInteractiveSwipe()
                            true
                        }
                        ImageGestureArbiter.Release.TAP -> {
                            image.suppressAccessibilityClickAction = true
                            try {
                                image.performClick()
                            } finally {
                                image.suppressAccessibilityClickAction = false
                            }
                            false
                        }
                        ImageGestureArbiter.Release.IMAGE -> false
                    }
                    imageGestures.finishRelease(release)
                    consumed
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (imageGestures.paging || dragPreviewPage != null) cancelInteractiveSwipe()
                    imageGestures.cancel()
                }
                else -> imageGestures.paging
            }
        }
        image.onViewTapListener = OnViewTapListener { _, offset ->
            if (!imageGestures.textLongPressed && !changeMediaBySideTap(offset.x)) toggleHud()
        }
        image.load(item.uri) {
            ImageRotation.configureRequest(this@DetailActivity, item, this)
            memoryCacheKey(viewerNativeKey(item))
            diskCacheKey(viewerSourceKey(item))
            crossfade(false)
            allowHardware(true)
            precision(Precision.EXACT)
            scale(Scale.FIT)
            // "Maior qualidade possível": decode at the original size (Coil still caps huge bitmaps).
            if (GalleryPreferences.flag(this@DetailActivity, GalleryPreferences.BEST_IMAGE_QUALITY)) {
                size(coil3.size.Size.ORIGINAL)
                memoryCacheKey(viewerNativeKey(item) + ":original")
            }
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
                        Ui.toast(this@DetailActivity, getString(R.string.msg_open_image_failed))
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

    internal fun updatePlayPauseButton() {
        if (::playPauseButton.isInitialized) {
            playPauseButton.setImageResource(
                if (playbackController.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
            )
            playPauseButton.contentDescription = getString(if (playbackController.isPlaying()) R.string.video_pause else R.string.video_play)
        }
    }

    private fun updateTimelineVisibility() {
        videoTimeline.setExpanded(timelineExpanded)
        timelineButton.contentDescription = getString(if (timelineExpanded) R.string.video_timeline_hide else R.string.video_timeline_show)
        timelineButton.alpha = if (timelineExpanded) 1f else 0.65f
        if (videoTimeline.expansionState == VideoTimelineView.ExpansionState.LOADING) {
            timelineButton.contentDescription = getString(R.string.video_timeline_loading)
            timelineButton.alpha = 0.35f
        }
    }

    private fun updateTimeline() {
        // The last media just left the queue and the screen is closing.
        if (mediaQueue.isEmpty() || !currentItem().isVideo()) return
        val timeline = playbackController.timeline() ?: return
        currentTime.text = formatTime(timelineBinding.scrubPositionMs ?: timeline.positionMs)
        durationTime.text = if (timeline.durationMs > 0) formatTime(timeline.durationMs) else "00:00"
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = max(0L, millis / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    @Throws(Exception::class)
    internal fun decodeBitmap(uri: Uri, maxSide: Int): Bitmap {
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
        val decoded = contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalStateException("bitmap")
        return ImageRotation.orientDecodedBitmap(this, uri, decoded)
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
        speedButton.contentDescription = getString(R.string.video_speed_description, speedButton.text)
    }

    private fun toggleFavorite() {
        val item = currentItem()
        val favorite = mediaActions.toggleFavorite(item)
        Haptics.perform(this, Haptics.toggle(favorite))
        Ui.toast(this, getString(if (favorite) R.string.msg_favorite_added else R.string.msg_favorite_removed))
        updateFavoriteButton()
    }

    private fun updateClockBadge() {
        if (!::clockBadge.isInitialized || mediaQueue.isEmpty()) return
        ClockBadge.bind(clockBadge, currentItem().uri.toString())
    }

    private fun updateFavoriteButton() {
        if (!::favoriteButton.isInitialized || mediaQueue.isEmpty()) return
        updateClockBadge()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            val deleted = resultCode == RESULT_OK
            pendingDeleteUri = null
            if (deleted) {
                Ui.toast(
                    this,
                    getString(
                        if (pendingDeletePermanent) R.string.album_item_deleted
                        else R.string.item_moved_to_trash
                    )
                )
                removeDeletedItem()
            } else {
                Ui.toast(this, getString(R.string.msg_delete_cancelled))
            }
        } else if (requestCode == REQ_SPLIT_RESTORE) {
            onSplitRestoreResult(resultCode == RESULT_OK)
        } else if (requestCode == REQ_SPLIT_TRASH) {
            onSplitTrashResult(resultCode == RESULT_OK)
        } else if (requestCode == REQ_RESTORE) {
            pendingDeleteUri = null
            if (resultCode == RESULT_OK) {
                Ui.toast(this, getString(R.string.item_restored))
                removeDeletedItem()
            }
        } else if (requestCode == REQ_HIDE_DELETE) {
            val deleted = resultCode == RESULT_OK
            pendingDeleteUri = null
            if (deleted) {
                pendingHiddenCopy = null
                Ui.toast(this, getString(R.string.msg_item_hidden))
                removeDeletedItem()
            } else {
                pendingHiddenCopy?.delete()
                pendingHiddenCopy = null
                Ui.toast(this, getString(R.string.msg_hide_cancelled))
            }
        } else if (requestCode == REQ_MOVE_WRITE) {
            val item = pendingMoveItem
            val folder = pendingMoveFolder
            if (resultCode == RESULT_OK && item != null && folder != null) {
                moveCurrentToFolder(item, folder)
                return
            }
            pendingMoveItem = null
            pendingMoveFolder = null
        }
    }

    internal fun currentItem(): MediaItem = queueController.current()

    private fun cinemaAlbumKey(item: MediaItem): String =
        if (sourceAlbumKey?.let(VirtualAlbumRules::isVirtual) == true) item.albumKey
        else sourceAlbumKey ?: item.albumKey

    internal fun MediaItem.metadataDescription(): DetailMediaMetadata = DetailMediaMetadata(
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
        // "Exibir o notch" off keeps the media out of the camera cutout area.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !GalleryPreferences.flag(this, GalleryPreferences.SHOW_DISPLAY_CUTOUT)) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        if (prefs.getBoolean(GalleryPreferences.FULLSCREEN_MAX_BRIGHTNESS, false)) {
            val params = window.attributes
            params.screenBrightness = 1f
            window.attributes = params
        }
        applyViewerSystemBars()
    }

    private fun applyViewerSystemBars() {
        cinemaController.refreshBars()
    }

    private fun setViewerSystemBars(cinema: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Showing the app's playback controls must not reveal system bars in
        // cinema. Edge swipes still reveal them temporarily for system navigation.
        if (cinema || GalleryPreferences.flag(this, GalleryPreferences.FULLSCREEN_HIDE_SYSTEM_UI)) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::topBar.isInitialized && !isFinishing) applyViewerSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_delete_permanent", pendingDeletePermanent)
        finishMediaTransition()
        cinemaController.settle()
        outState.putBoolean("timeline_expanded", timelineExpanded)
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
        cinemaController.previousOrientation?.let { outState.putInt(STATE_ORIENTATION_BEFORE_CINEMA, it) }
        outState.putBoolean(STATE_AUTOMATIC_CINEMA, cinemaController.automatic)
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
        textRecognitionController.resume()
        motionPhotoController.resume()
        applyViewerSystemBars()
        if (::timelineBinding.isInitialized) timelineBinding.resumeUpdates()
        if (::textRecognitionButton.isInitialized) scheduleTextDetection(mediaQueue.getOrNull(currentIndex))
        if (::motionPhotoButton.isInitialized) mediaQueue.getOrNull(currentIndex)?.let(::updateMotionPhotoAction)
        playbackController.resumeAfterLifecycle()
        handler.removeCallbacks(progressUpdater)
        if (mediaQueue.getOrNull(currentIndex)?.isVideo() == true) {
            handler.post(progressUpdater)
        }
        scheduleShuffleAdvance()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideViewerChromeForPictureInPicture()
            registerPictureInPictureControls()
            return
        }
        pictureInPictureShownPlaying = null
        unregisterPictureInPictureControls()
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // The thumbnail was closed: stop the video instead of leaving it playing invisibly.
            playbackController.pauseForLifecycle()
            resetSpeedAndFinish()
            return
        }
        restoreViewerChromeAfterPictureInPicture()
        applyViewerSystemBars()
    }

    override fun onStop() {
        super.onStop()
        inlineMotionPhoto?.stop(animated = false)
        if (isInPictureInPictureModeCompat()) playbackController.pauseForLifecycle()
    }

    // Last play state sent to the thumbnail. Re-sending the same buttons on every player
    // callback makes Android rebuild them constantly and the user's tap gets lost.
    internal var pictureInPictureShownPlaying: Boolean? = null

    internal var pictureInPictureShownRatio: Pair<Int, Int>? = null

    internal var pictureInPictureReceiver: android.content.BroadcastReceiver? = null
    internal val viewsHiddenForPictureInPicture = mutableListOf<Pair<View, Int>>()

    override fun onPause() {
        endHoldSpeed()
        if (isInPictureInPictureModeCompat()) {
            // Entering the floating thumbnail pauses the activity, but the video must keep playing.
            super.onPause()
            return
        }
        closeOcrSelection()
        clearOcrProgress()
        // Settle before pausing so a new video cannot start after lifecycle pause.
        finishMediaTransition()
        cinemaController.settle()
        super.onPause()
        playbackController.pauseForLifecycle()
        if (::timelineBinding.isInitialized) timelineBinding.suspend()
        textRecognitionController.pause()
        motionPhotoController.pause()
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
        unregisterPictureInPictureControls()
        closeOcrSelection()
        clearOcrProgress()
        operations.close()
        finishMediaTransition(updateUi = false)
        super.onDestroy()
        if (::timelineBinding.isInitialized) timelineBinding.unbind()
        textRecognitionController.close()
        inlineMotionPhoto?.stop(animated = false)
        motionPhotoController.close()
        cinemaController.close()
        cancelPendingSingleTap()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(autoAdvanceRunnable)
        handler.removeCallbacks(hideCinemaGestureIndicator)
        handler.removeCallbacks(hideCinemaModeIndicator)
        videoTrackController.unbind()
        playbackController.releaseCurrent()
        imagePreloadJobs.values.forEach { it.cancel() }
        imagePreloadJobs.clear()
        imagePreloadScope.cancel()
        executor.shutdownNow()
        videoPreviewExecutor.shutdownNow()
        motionPhotoExecutor.shutdownNow()
        motionPhotoPrefetchExecutor.shutdownNow()
        queueController.close()
        if (::mediaActions.isInitialized) mediaActions.close()
    }

    private fun resetSpeedAndFinish() {
        if (closeOcrSelection()) return
        playbackController.resetSpeed()
        finish()
        Ui.applyCloseTransition(this)
    }

    private val systemBars by lazy { SystemBarInsets(this) }

    /** Keeps the top and bottom bars clear of the status and navigation bars. */
    /** Bars and indicators hidden while the viewer is a floating thumbnail (only those already built). */
    internal fun pictureInPictureChrome(): List<View> = listOfNotNull(
        if (::topBar.isInitialized) topBar else null,
        if (::bottomBar.isInitialized) bottomBar else null,
        if (::cinemaGestureIndicator.isInitialized) cinemaGestureIndicator else null,
        if (::cinemaModeIndicator.isInitialized) cinemaModeIndicator else null
    )

    private fun applyBarInsets() {
        topBar.setPadding(
            Ui.dp(this, 8), systemBars.top + Ui.dp(this, 6),
            systemBars.side + Ui.dp(this, 10), Ui.dp(this, 6)
        )
        bottomBar.setPadding(
            Ui.dp(this, 14), Ui.dp(this, 4),
            systemBars.side + Ui.dp(this, 14), systemBars.bottom + Ui.dp(this, 8)
        )
    }

    companion object {
        private const val CLOCK_BADGE_DP = 30
        internal const val ACTION_PICTURE_IN_PICTURE_TOGGLE = "com.galeria.android.PICTURE_IN_PICTURE_TOGGLE"
        internal const val EXTRA_PICTURE_IN_PICTURE_COMMAND = "command"
        internal const val PIP_COMMAND_TOGGLE = 0
        internal const val PIP_COMMAND_PREVIOUS = 1
        internal const val PIP_COMMAND_NEXT = 2
        internal const val PIP_SWITCH_REFRESH_MS = 700L
        internal const val REQ_DELETE = 31
        internal const val REQ_RESTORE = 36
        internal const val REQ_HIDE_DELETE = 32
        internal const val REQ_MOVE_WRITE = 33
        internal const val REQ_SPLIT_RESTORE = 41
        internal const val REQ_SPLIT_TRASH = 42
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
        private const val STATE_AUTOMATIC_CINEMA = "viewer_automatic_cinema"
        private const val CINEMA_BUTTON_TAG = "viewer_cinema_mode_button"
        private const val CINEMA_TRANSITION_TAG = "viewer_cinema_transition_message"
        private const val CINEMA_INDICATOR_HIDE_MS = 650L
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
