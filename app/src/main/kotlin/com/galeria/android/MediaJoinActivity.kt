package com.galeria.android

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * The video editor: a preview, the media as squares that can be reordered, and an editing
 * timeline to cut each one. Saving writes a new video and leaves every original untouched.
 */
// The preview player is marked experimental in Media3; the export does not depend on it.
@OptIn(UnstableApi::class, ExperimentalApi::class)
class MediaJoinActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URIS = "join_uris"
        private const val EXTRA_MIMES = "join_mimes"
        private const val EXTRA_NAMES = "join_names"
        private const val EXTRA_SIZES = "join_sizes"
        private const val ASKED_NOTIFICATIONS = "join_asked_notifications"

        fun intent(context: Context, items: List<MediaItem>): Intent = Intent(context, MediaJoinActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri.toString() }))
            putStringArrayListExtra(EXTRA_MIMES, ArrayList(items.map { it.mimeType }))
            putStringArrayListExtra(EXTRA_NAMES, ArrayList(items.map { it.name }))
            putExtra(EXTRA_SIZES, items.map { it.size }.toLongArray())
        }

        internal fun time(ms: Long): String {
            val seconds = (ms + 500L) / 1000L
            return "%d:%02d".format(seconds / 60L, seconds % 60L)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var clips: List<JoinClip> = emptyList()
    private var selected = 0
    private var photoSeconds = MediaJoinRules.DEFAULT_PHOTO_SECONDS
    /** The transition at each junction; index i sits between clip i and clip i + 1. */
    private var transitions: List<JoinTransition> = emptyList()
    private var quality = JoinQuality.NATIVE
    private val photoMs get() = photoSeconds * 1000L

    private lateinit var player: CompositionPlayer
    private lateinit var playerView: PlayerView
    private lateinit var edgePreview: ImageView
    private lateinit var strip: JoinStripView
    private lateinit var timeline: JoinTimelineView
    private lateinit var playButton: ImageButton
    private lateinit var splitButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var qualityChip: TextView
    private lateinit var timeLabel: TextView
    private lateinit var photoControls: LinearLayout
    private lateinit var photoLabel: TextView
    private lateinit var overlay: FrameLayout
    private lateinit var overlayPanel: LinearLayout
    private lateinit var ring: JoinProgressRing
    private lateinit var backgroundButton: TextView
    private val edgeFrames = HashMap<String, VideoTimelineFrames>()
    private lateinit var transitionStill: ImageView
    private lateinit var veil: View
    private val stills = HashMap<String, Bitmap>()
    private val stillCache = HashMap<String, Bitmap>()
    private val thumbs = HashMap<String, Bitmap>()
    private var scrubbing = false
    private var pendingFrame = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!::player.isInitialized) return
            if (!scrubbing) {
                timeline.setPosition(player.currentPosition)
                showTime(player.currentPosition)
            }
            if (player.isPlaying) handler.postDelayed(this, 33L)
        }
    }

    private val jobListener: (MediaJoinJobs.State) -> Unit = { state -> render(state) }

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> addMedia(uris) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startJoin() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        player = CompositionPlayer.Builder(this)
            .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    playButton.contentDescription = getString(if (isPlaying) R.string.video_pause else R.string.video_play)
                    handler.removeCallbacks(ticker)
                    if (isPlaying) handler.post(ticker)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) { pause(); seek(0L) }
                    // A paused preview only draws after a seek; ask for the frame under the playhead.
                    if (playbackState == Player.STATE_READY && pendingFrame && !isPlaying) {
                        pendingFrame = false
                        seekTo(currentPosition)
                    }
                }
            })
        }
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (MediaJoinJobs.state is MediaJoinJobs.State.Running && overlay.visibility == View.VISIBLE) leaveWhileJoining() else finish()
            }
        })
        loadMedia()
        MediaJoinJobs.observe(jobListener)
    }

    private fun loadMedia() {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(uris.size)
        executor.execute {
            val medias = uris.indices.mapNotNull { i ->
                MediaJoinExporter.probe(this, Uri.parse(uris[i]), mimes.getOrElse(i) { "" }, names.getOrElse(i) { "" }, sizes.getOrElse(i) { 0L })
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (medias.isEmpty()) {
                    Ui.toast(this, getString(R.string.join_open_error))
                    finish()
                    return@runOnUiThread
                }
                clips = medias.map { JoinClip(it) }
                refresh(rebuild = true)
                loadThumbs(medias)
            }
        }
    }

    private fun loadThumbs(medias: List<JoinMedia>) {
        executor.execute {
            val loaded = medias.associate { it.uri to thumbnail(it) }.filterValues { it != null }.mapValues { it.value!! }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                thumbs.putAll(loaded)
                strip.setThumbs(thumbs)
                timeline.setThumbs(thumbs)
            }
        }
    }

    /** Media chosen with "+" join the end of the montage, in the order they were picked. */
    private fun addMedia(picked: List<Uri>) {
        if (picked.isEmpty()) return
        executor.execute {
            val medias = picked.mapNotNull { uri ->
                val source = MediaJoinPicks.galleryUri(this, uri)
                val mime = contentResolver.getType(source) ?: contentResolver.getType(uri).orEmpty()
                val (name, size) = MediaJoinPicks.nameAndSize(this, source)
                MediaJoinExporter.probe(this, source, mime, name, size)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (medias.isEmpty()) { Ui.toast(this, getString(R.string.join_open_error)); return@runOnUiThread }
                clips = clips + medias.map { JoinClip(it) }
                selected = clips.size - medias.size
                refresh(rebuild = true)
                loadThumbs(medias)
            }
        }
    }

    private fun thumbnail(media: JoinMedia): Bitmap? = runCatching {
        val uri = Uri.parse(media.uri)
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            return@runCatching contentResolver.loadThumbnail(uri, Size(192, 192), null)
        }
        if (media.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.getFrameAtTime(0L)?.let { Bitmap.createScaledBitmap(it, 160, 160 * it.height / maxOf(1, it.width), true) }
            } finally { retriever.release() }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, minOf(media.width, media.height) / 192) }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val degrees = contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (bitmap == null || degrees == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }
    }.getOrNull()

    @androidx.annotation.VisibleForTesting internal fun clipsForTest(): List<JoinClip> = clips
    @androidx.annotation.VisibleForTesting internal fun photoMsForTest(): Long = photoMs
    @androidx.annotation.VisibleForTesting internal fun transitionsForTest(): List<JoinTransition> = transitions

    private fun dp(value: Int) = Ui.dp(this, value)

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        header.addView(EditorUi.button(this, R.drawable.ic_close, getString(R.string.msg_back)) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        qualityChip = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.rounded(0xFF23272C.toInt(), 18, context)
            contentDescription = getString(R.string.join_quality)
            setOnClickListener { chooseQuality() }
        }
        header.addView(qualityChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        saveButton = EditorUi.button(this, R.drawable.ic_check, getString(R.string.action_join)) { requestJoin() }
        header.addView(saveButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        column.addView(header)

        // The stage is a shade lighter than black, so the video's own borders stay visible.
        val stage = FrameLayout(this).apply { setBackgroundColor(0xFF101214.toInt()) }
        playerView = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            player = this@MediaJoinActivity.player
        }
        stage.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        edgePreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        transitionStill = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Black behind the picture, like the borders in the saved video.
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(transitionStill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        veil = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        stage.addView(veil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(edgePreview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), 0)
        }
        playButton = EditorUi.button(this, R.drawable.ic_play, getString(R.string.video_play)) { togglePlay() }
        controls.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        timeLabel = TextView(this).apply {
            setTextColor(0xFFC9CED6.toInt())
            textSize = 13f
            setPadding(dp(4), 0, 0, 0)
        }
        controls.addView(timeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        splitButton = EditorUi.button(this, R.drawable.ic_split, getString(R.string.join_split)) { splitAtPlayhead() }
        controls.addView(splitButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        photoControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        photoControls.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_timer)
            setColorFilter(0xFFC9CED6.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        photoControls.addView(EditorUi.button(this, R.drawable.ic_minus, "${getString(R.string.join_photo_time)} −") { changePhotoSeconds(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        photoLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(32)
        }
        photoControls.addView(photoLabel)
        photoControls.addView(EditorUi.button(this, R.drawable.ic_plus, "${getString(R.string.join_photo_time)} +") { changePhotoSeconds(1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(photoControls)
        column.addView(controls)

        strip = JoinStripView(this).apply {
            onSelect = { index -> select(index) }
            onMove = { from, to -> move(from, to) }
            onJunction = { index, centerX -> showTransitions(index, centerX) }
            onAdd = { mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        }
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        timeline = JoinTimelineView(this).apply {
            onScrub = { position, active -> scrub(position, active) }
            onSelect = { index -> select(index) }
            onTrim = { clip, range, start, sourceMs -> trim(clip, range, start, sourceMs) }
            onTrimming = { uri, sourceMs -> showEdge(uri, sourceMs) }
        }
        column.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        overlay = buildOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        showQuality()
    }

    private fun buildOverlay(): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(0xE6050607.toInt())
        visibility = View.GONE
        isClickable = true
        overlayPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        ring = JoinProgressRing(context)
        overlayPanel.addView(ring, LinearLayout.LayoutParams(dp(112), dp(112)))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
        actions.addView(textButton(getString(android.R.string.cancel)) { MediaJoinJobs.cancel() })
        backgroundButton = textButton(getString(R.string.join_background)) { finish() }
        actions.addView(backgroundButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        overlayPanel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        addView(overlayPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun textButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(18), 0, dp(18), 0)
        background = Ui.rounded(0xFF23272C.toInt(), 22, context)
        setOnClickListener { action() }
    }

    /** Puts the model on screen and, when the montage changed, into the preview player. */
    private fun refresh(rebuild: Boolean) {
        // One junction between each pair of neighbours; new ones start as a plain cut.
        transitions = List(maxOf(0, clips.size - 1)) { transitions.getOrElse(it) { JoinTransition.NONE } }
        val labels = clips.map { time(MediaJoinRules.clipDurationMs(it, photoMs)) }
        selected = selected.coerceIn(0, maxOf(0, clips.lastIndex))
        strip.setClips(clips, labels, selected, transitions)
        timeline.setClips(clips, photoMs, selected, transitions)
        photoControls.visibility = if (clips.any { !it.media.isVideo }) View.VISIBLE else View.GONE
        photoLabel.text = getString(R.string.join_seconds, photoSeconds)
        // One video is a plain edit that saves a copy; two or more media are joined.
        saveButton.contentDescription = getString(if (clips.size > 1) R.string.action_join else R.string.msg_save_copy)
        if (Build.VERSION.SDK_INT >= 26) saveButton.tooltipText = saveButton.contentDescription
        splitButton.visibility = if (clips.getOrNull(selected)?.media?.isVideo == true) View.VISIBLE else View.GONE
        if (rebuild && clips.isNotEmpty()) {
            val position = timeline.positionMs
            pendingFrame = true
            player.setComposition(MediaJoinExporter.previewComposition(this, JoinRequest(clips, photoMs, quality, transitions)), position)
            player.prepare()
            loadTransitionStills()
        }
        showTime(timeline.positionMs)
    }

    private fun showTime(position: Long) {
        timeLabel.text = "${time(position)} / ${time(MediaJoinRules.totalMs(clips, photoMs, transitions))}"
        applyTransitionLook(position)
    }

    /**
     * The preview plays one layer with a cut in the middle of each transition; over it the editor
     * draws the transition: the other side's frame fading or sliding in, or a black veil. The
     * saved video has the real one.
     */
    private fun applyTransitionLook(position: Long) {
        if (!::transitionStill.isInitialized) return
        playerView.translationX = 0f
        playerView.scaleX = 1f; playerView.scaleY = 1f
        transitionStill.visibility = View.GONE
        veil.alpha = 0f
        val window = MediaJoinRules.windows(clips, photoMs, transitions).firstOrNull { position in it.startMs until it.endMs } ?: return
        val p = ((position - window.startMs).toFloat() / (window.endMs - window.startMs)).coerceIn(0f, 1f)
        val beforeCut = p < 0.5f
        // Before the cut the player still shows the outgoing media; after it, the incoming one.
        val other = if (beforeCut) stills["in:${window.junction}"] else stills["out:${window.junction}"]
        // The other side's frame sits exactly over the video area, borders included, like the saved video.
        // The area comes from the joined video's size fitted to the stage, as the player shows it.
        val canvas = MediaJoinRules.canvas(clips.map { it.media }, quality)
        val stage = playerView.parent as View
        val fit = minOf(stage.width.toFloat() / canvas.width, stage.height.toFloat() / canvas.height)
        val frameWidth = (canvas.width * fit).toInt()
        val frameHeight = (canvas.height * fit).toInt()
        val width = frameWidth.toFloat()
        if (frameWidth > 0 && frameHeight > 0) {
            val params = transitionStill.layoutParams as FrameLayout.LayoutParams
            val left = (stage.width - frameWidth) / 2
            val top = (stage.height - frameHeight) / 2
            if (params.width != frameWidth || params.height != frameHeight || params.leftMargin != left || params.topMargin != top) {
                params.width = frameWidth; params.height = frameHeight
                params.leftMargin = left; params.topMargin = top
                transitionStill.layoutParams = params
            }
        }
        fun showStill(alpha: Float, shift: Float = 0f, scale: Float = 1f) {
            other ?: return
            transitionStill.setImageBitmap(other)
            transitionStill.visibility = View.VISIBLE
            transitionStill.alpha = alpha
            transitionStill.translationX = shift
            transitionStill.scaleX = scale; transitionStill.scaleY = scale
        }
        when (window.type) {
            JoinTransition.NONE -> Unit
            JoinTransition.FADE -> veil.alpha = 1f - kotlin.math.abs(2f * p - 1f)
            JoinTransition.DISSOLVE -> showStill(if (beforeCut) p else 1f - p)
            JoinTransition.ZOOM -> {
                val outScale = 1f + 0.15f * p
                val inScale = 0.85f + 0.15f * p
                playerView.scaleX = if (beforeCut) outScale else inScale; playerView.scaleY = playerView.scaleX
                showStill(if (beforeCut) p else 1f - p, scale = if (beforeCut) inScale else outScale)
            }
            JoinTransition.SLIDE -> {
                // Outgoing leaves to the left while the incoming one follows from the right.
                playerView.translationX = if (beforeCut) -p * width else (1f - p) * width
                showStill(1f, if (beforeCut) (1f - p) * width else -p * width)
            }
        }
    }

    /** First and last frames on both sides of each transition, for the preview's effects. */
    private fun loadTransitionStills() {
        val wanted = MediaJoinRules.windows(clips, photoMs, transitions).flatMap { window ->
            val outgoing = clips[window.junction]
            val incoming = clips[window.junction + 1]
            listOf("out:${window.junction}" to (outgoing.media to (outgoing.ranges.lastOrNull()?.last ?: 0L)),
                "in:${window.junction}" to (incoming.media to (incoming.ranges.firstOrNull()?.first ?: 0L)))
        }
        if (wanted.isEmpty()) return
        executor.execute {
            val loaded = wanted.mapNotNull { (key, source) ->
                val (media, atMs) = source
                val bitmap = stillCache.getOrPut("${media.uri}@$atMs") { still(media, atMs) ?: return@mapNotNull null }
                key to bitmap
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                stills.clear(); stills.putAll(loaded)
                applyTransitionLook(timeline.positionMs)
            }
        }
    }

    private fun still(media: JoinMedia, atMs: Long): Bitmap? = runCatching {
        val uri = Uri.parse(media.uri)
        if (media.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val time = (atMs * 1000L).coerceAtMost(maxOf(0L, media.durationMs * 1000L - 40_000L))
                if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)
                else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { retriever.release() }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(media.width, media.height) / 1080) }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val degrees = contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (bitmap == null || degrees == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }
    }.getOrNull()

    private fun showQuality() {
        qualityChip.text = when (quality) {
            JoinQuality.NATIVE -> getString(R.string.join_quality_native)
            JoinQuality.P1080 -> "1080p"
            JoinQuality.P720 -> "720p"
        }
    }

    private fun chooseQuality() {
        PopupMenu(this, qualityChip).apply {
            JoinQuality.entries.forEachIndexed { index, option ->
                menu.add(0, index, index, when (option) {
                    JoinQuality.NATIVE -> getString(R.string.join_quality_native)
                    JoinQuality.P1080 -> "1080p"
                    JoinQuality.P720 -> "720p"
                }).isCheckable = true
                menu.getItem(index).isChecked = option == quality
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                quality = JoinQuality.entries[item.itemId]
                showQuality()
                true
            }
            show()
        }
    }

    private fun select(index: Int) {
        if (index !in clips.indices) return
        selected = index
        strip.setSelected(index)
        timeline.setSelected(index)
        splitButton.visibility = if (clips[index].media.isVideo) View.VISIBLE else View.GONE
        // Jump to the start of the chosen media when it is not under the playhead.
        val segments = MediaJoinRules.segments(clips, photoMs, transitions).filter { it.clipIndex == index }
        val position = timeline.positionMs
        if (segments.none { position in it.startMs until it.endMs }) {
            // One millisecond in, so the paused preview draws this media and not the end of the previous one.
            segments.firstOrNull()?.let { seek(it.startMs + 1L); timeline.setPosition(it.startMs); showTime(it.startMs) }
        }
    }

    private fun move(from: Int, to: Int) {
        if (from !in clips.indices) return
        clips = MediaJoinRules.moved(clips, from, to)
        selected = to.coerceIn(0, clips.lastIndex)
        refresh(rebuild = true)
    }

    private var transitionPopup: android.widget.PopupWindow? = null

    private fun transitionLabel(type: JoinTransition): String = getString(when (type) {
        JoinTransition.NONE -> R.string.join_transition_none
        JoinTransition.DISSOLVE -> R.string.join_transition_dissolve
        JoinTransition.FADE -> R.string.join_transition_fade
        JoinTransition.SLIDE -> R.string.join_transition_slide
        JoinTransition.ZOOM -> R.string.join_transition_zoom
    })

    /** The choices for one junction, in a small row that rises from the circle that was tapped. */
    private fun showTransitions(junction: Int, centerX: Float) {
        if (junction !in transitions.indices) return
        transitionPopup?.dismiss()
        strip.setActiveJunction(junction)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = Ui.rounded(0xFF1A1D21.toInt(), 24, context)
            elevation = dp(8).toFloat()
        }
        JoinTransition.entries.forEach { type ->
            val chosen = transitions[junction] == type
            row.addView(TextView(this).apply {
                text = transitionLabel(type)
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (chosen) 0xFF101214.toInt() else Color.WHITE)
                background = Ui.rounded(if (chosen) Color.WHITE else 0xFF23272C.toInt(), 20, context)
                isSelected = chosen
                setOnClickListener { chooseTransition(junction, type) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(10))
            addView(row)
        }
        scroll.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = minOf(scroll.measuredWidth, strip.width - dp(16))
        val height = scroll.measuredHeight
        val x = (centerX - width / 2f).toInt().coerceIn(dp(8), maxOf(dp(8), strip.width - width - dp(8)))
        transitionPopup = android.widget.PopupWindow(scroll, width, height, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = 0
            setOnDismissListener { strip.setActiveJunction(-1); transitionPopup = null }
            showAsDropDown(strip, x, -(strip.height + height))
        }
        // Rises from the circle that opened it.
        Motion.enter(row, Motion.POPOVER_SCALE, Motion.POPOVER_MS, pivotX = centerX - x, pivotY = height.toFloat())
    }

    private fun chooseTransition(junction: Int, type: JoinTransition) {
        val popup = transitionPopup
        val content = (popup?.contentView as? ViewGroup)?.getChildAt(0)
        if (content != null) {
            content.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.easeOut).withEndAction { popup.dismiss() }.start()
        } else popup?.dismiss()
        if (transitions.getOrNull(junction) == type) return
        strip.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        transitions = transitions.toMutableList().apply { set(junction, type) }
        refresh(rebuild = true)
        // Stop a little before the junction, so pressing play shows the transition at once.
        val window = MediaJoinRules.windows(clips, photoMs, transitions).firstOrNull { it.junction == junction }
        val starts = MediaJoinRules.clipStarts(clips, photoMs, transitions)
        val at = maxOf(0L, (window?.startMs ?: starts.getOrElse(junction + 1) { 0L }) - 700L)
        timeline.setPosition(at)
        seek(at)
        showTime(at)
    }

    private fun changePhotoSeconds(delta: Int) {
        val next = MediaJoinRules.photoSeconds(photoSeconds + delta)
        if (next == photoSeconds) {
            photoLabel.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        photoSeconds = next
        Motion.settle(photoLabel)
        refresh(rebuild = true)
    }

    private fun splitAtPlayhead() {
        val clip = clips.getOrNull(selected) ?: return
        val position = timeline.positionMs
        val segment = MediaJoinRules.segments(clips, photoMs, transitions).firstOrNull { it.clipIndex == selected && position in it.startMs until it.endMs }
        val split = segment?.let { MediaJoinRules.split(clip, it.sourceStartMs + (position - it.startMs)) }
        if (split == null || split == clip) {
            splitButton.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        splitButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        clips = clips.toMutableList().apply { set(selected, split) }
        refresh(rebuild = true)
    }

    private fun trim(clipIndex: Int, range: Int, start: Boolean, sourceMs: Long) {
        val clip = clips.getOrNull(clipIndex) ?: return
        clips = clips.toMutableList().apply { set(clipIndex, MediaJoinRules.trimmed(clip, range, start, sourceMs)) }
        val segment = MediaJoinRules.segments(clips, photoMs, transitions).filter { it.clipIndex == clipIndex }.getOrNull(range)
        val position = segment?.let { if (start) it.startMs else maxOf(it.startMs, it.endMs - 1L) } ?: timeline.positionMs
        hideEdge()
        refresh(rebuild = true)
        timeline.setPosition(position)
        seek(position)
        showTime(position)
    }

    /** While a handle is dragged, the preview shows the frame at the new edge. */
    private fun showEdge(uri: String, sourceMs: Long) {
        pause()
        val frames = edgeFrames.getOrPut(uri) { VideoTimelineFrames(this, Uri.parse(uri), "join-edge") }
        frames.request(listOf(JoinTimelineRules.frameTime(sourceMs))) { _, bitmap ->
            edgePreview.setImageBitmap(bitmap)
            if (edgePreview.visibility != View.VISIBLE) {
                edgePreview.alpha = 1f
                edgePreview.visibility = View.VISIBLE
            }
        }
    }

    private fun hideEdge() {
        if (edgePreview.visibility != View.VISIBLE) return
        edgePreview.animate().cancel()
        edgePreview.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.easeOut)
            .withEndAction { edgePreview.visibility = View.GONE; edgePreview.alpha = 1f }.start()
    }

    private fun scrub(position: Long, active: Boolean) {
        scrubbing = active
        if (active) pause()
        seek(position)
        showTime(position)
    }

    private fun seek(position: Long) { player.seekTo(position) }
    private fun pause() { player.pause() }

    private fun togglePlay() {
        if (clips.isEmpty()) return
        if (player.isPlaying) player.pause() else {
            if (timeline.positionMs >= MediaJoinRules.totalMs(clips, photoMs, transitions)) seek(0L)
            player.play()
        }
    }

    private fun requestJoin() {
        if (clips.isEmpty()) return
        val prefs = GalleryPreferences.prefs(this)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean(ASKED_NOTIFICATIONS, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startJoin()
    }

    private fun startJoin() {
        pause()
        // With the photo clock on, the photos may be deleted later: the video keeps a copy for Separar.
        val keepPhotos = clips.size > 1 && GalleryPreferences.flag(this, GalleryPreferences.CLOCK_PHOTOS)
        MediaJoinJobs.start(applicationContext, JoinRequest(clips, photoMs, quality, transitions, keepPhotos))
    }

    private fun render(state: MediaJoinJobs.State) {
        when (state) {
            is MediaJoinJobs.State.Running -> {
                showOverlay()
                ring.setProgress(state.progress)
            }
            is MediaJoinJobs.State.Saved -> {
                MediaJoinJobs.acknowledge()
                ring.setProgress(100)
                Ui.toast(this, getString(R.string.join_saved))
                setResult(RESULT_OK, Intent().setData(state.uri))
                finish()
            }
            is MediaJoinJobs.State.Failed -> {
                MediaJoinJobs.acknowledge()
                hideOverlay()
                android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(R.string.join_failed)
                    .apply { state.message?.let { setMessage(it) } }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            MediaJoinJobs.State.Cancelled -> { MediaJoinJobs.acknowledge(); hideOverlay() }
            MediaJoinJobs.State.Idle -> Unit
        }
    }

    private fun showOverlay() {
        if (overlay.visibility == View.VISIBLE) return
        backgroundButton.visibility = if (GalleryPreferences.flag(this, GalleryPreferences.JOIN_IN_BACKGROUND)) View.VISIBLE else View.GONE
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(Motion.MODAL_MS).setInterpolator(Motion.easeOut).start()
        Motion.enter(overlayPanel, Motion.MODAL_SCALE, Motion.MODAL_MS)
    }

    private fun hideOverlay() {
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate().cancel()
        overlay.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.easeOut)
            .withEndAction { overlay.visibility = View.GONE; ring.setProgress(0, animate = false) }.start()
    }

    private fun leaveWhileJoining() {
        if (!GalleryPreferences.flag(this, GalleryPreferences.JOIN_IN_BACKGROUND)) MediaJoinJobs.cancel()
        finish()
    }

    override fun onStop() {
        super.onStop()
        pause()
        if (!isChangingConfigurations && MediaJoinJobs.state is MediaJoinJobs.State.Running &&
            !GalleryPreferences.flag(this, GalleryPreferences.JOIN_IN_BACKGROUND)) {
            MediaJoinJobs.cancel()
        }
    }

    override fun onDestroy() {
        MediaJoinJobs.stopObserving(jobListener)
        handler.removeCallbacks(ticker)
        edgeFrames.values.forEach { it.close() }
        if (::timeline.isInitialized) timeline.release()
        player.release()
        executor.shutdown()
        super.onDestroy()
    }
}

/** A ring that fills as the montage is written, with the percentage inside. */
internal class JoinProgressRing(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f * density; color = 0xFF2A2E34.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f * density; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
    }
    private val bounds = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null
    var progress = 0
        private set

    init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun setProgress(value: Int, animate: Boolean = true) {
        val target = value.coerceIn(0, 100)
        progress = target
        contentDescription = "$target%"
        animator?.cancel()
        if (!animate || !Motion.movementEnabled()) { shown = target.toFloat(); invalidate(); return }
        // Progress is constant motion: it moves at an even pace towards the latest value.
        animator = ValueAnimator.ofFloat(shown, target.toFloat()).apply {
            duration = 240L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth / 2f + density
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        canvas.drawArc(bounds, -90f, 360f * shown / 100f, false, arc)
        val baseline = height / 2f - (label.descent() + label.ascent()) / 2f
        canvas.drawText("${shown.toInt()}%", width / 2f, baseline, label)
    }
}
