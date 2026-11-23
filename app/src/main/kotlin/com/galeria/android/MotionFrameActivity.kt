package com.galeria.android

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chooses which moment of a Motion Photo is the photo. Saving replaces the photo in place and
 * keeps it a Motion Photo; the camera's original stays inside the file and can be brought back.
 */
class MotionFrameActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val previewWorker = Executors.newSingleThreadExecutor()
    private val previewGeneration = AtomicInteger()
    private lateinit var uri: Uri
    private var mime = "image/jpeg"
    private var source: MotionFrameEditor.Source? = null
    private var chosenMs: Long? = null
    private var busy = false
    private var pendingWrite: (() -> Unit)? = null
    private lateinit var preview: ImageView
    private lateinit var strip: MotionFrameStripView
    private lateinit var progress: LoadingIndicatorView
    private lateinit var saveButton: TextView
    private lateinit var originalButton: TextView

    private val writePermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val retry = pendingWrite
        pendingWrite = null
        if (result.resultCode == RESULT_OK && retry != null) retry() else setBusy(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this)
        uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        mime = intent.getStringExtra(EXTRA_MIME) ?: mime
        chosenMs = savedInstanceState?.getLong(STATE_CHOSEN, -1L)?.takeIf { it >= 0L }
        buildLayout()
        load()
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = ImageView(this).apply {
            tag = "motion_frame_preview"
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(ClickFeedbackImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = null
            contentDescription = getString(R.string.album_back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        top.addView(TextView(this).apply {
            text = getString(R.string.motion_frame_title)
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            setPadding(Ui.dp(this@MotionFrameActivity, 8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ClickFeedbackImageButton(this).apply {
            tag = "motion_frame_more_edits"
            setImageResource(R.drawable.ic_edit)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = null
            contentDescription = getString(R.string.motion_frame_more_edits)
            setOnClickListener { openOtherEdits() }
        }, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))

        strip = MotionFrameStripView(this).apply {
            isEnabled = false
            onChoose = { time, final -> choose(time, final) }
        }
        originalButton = textButton(getString(R.string.motion_frame_original), primary = false).apply {
            tag = "motion_frame_original"
            visibility = View.INVISIBLE
            setOnClickListener { restoreOriginal() }
        }
        saveButton = textButton(getString(R.string.motion_frame_save), primary = true).apply {
            tag = "motion_frame_save"
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { saveChosenFrame() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(originalButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this@MotionFrameActivity, 48)))
            addView(View(this@MotionFrameActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this@MotionFrameActivity, 48)))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MotionFrameActivity, 60)))
            addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@MotionFrameActivity, 8)
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(bars.left + Ui.dp(this, 4), bars.top + Ui.dp(this, 4), bars.right + Ui.dp(this, 4), Ui.dp(this, 4))
            bottom.setPadding(bars.left + Ui.dp(this, 16), Ui.dp(this, 14), bars.right + Ui.dp(this, 16), bars.bottom + Ui.dp(this, 10))
            insets
        }
        progress = LoadingIndicatorView(this, Color.WHITE)
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(progress, FrameLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36), Gravity.CENTER))
        setContentView(root)
    }

    private fun textButton(label: String, primary: Boolean) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        if (primary) setTypeface(Typeface.DEFAULT_BOLD)
        isClickable = true
        isFocusable = true
        minWidth = Ui.dp(this@MotionFrameActivity, 96)
        setPadding(Ui.dp(this@MotionFrameActivity, 18), 0, Ui.dp(this@MotionFrameActivity, 18), 0)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            if (primary) Ui.rounded(0x33FFFFFF, 24, this@MotionFrameActivity) else null,
            Ui.rounded(Color.WHITE, 24, this@MotionFrameActivity)
        )
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        val context = applicationContext
        val screen = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        worker.execute {
            val loaded = runCatching { MotionFrameEditor.read(context, uri) }.getOrNull()
            val photo = loaded?.let { runCatching { decodeShownPhoto(context, it, screen) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) { photo?.recycle(); return@runOnUiThread }
                progress.visibility = View.GONE
                if (loaded == null || loaded.durationMs <= 0L) {
                    Ui.toast(this, getString(R.string.motion_frame_open_error))
                    finish()
                    return@runOnUiThread
                }
                source = loaded
                photo?.let(preview::setImageBitmap)
                val start = chosenMs ?: loaded.layout.frameTimeUs?.div(1000L) ?: 0L
                strip.setClip(loaded.clip, loaded.durationMs, start)
                strip.isEnabled = true
                originalButton.visibility = if (loaded.layout.edited) View.VISIBLE else View.INVISIBLE
                chosenMs?.let { choose(it, true) }
            }
        }
    }

    /** The photo as the viewer shows it now: the chosen frame, or the camera's photo. */
    private fun decodeShownPhoto(context: Context, loaded: MotionFrameEditor.Source, screen: Int): Bitmap? {
        val bytes = loaded.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= screen) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return ImageRotation.orientDecodedBitmap(context, uri, bitmap)
    }

    private fun choose(timeMs: Long, final: Boolean) {
        val loaded = source ?: return
        chosenMs = timeMs
        saveButton.isEnabled = !busy
        saveButton.alpha = if (busy) 0.4f else 1f
        val generation = previewGeneration.incrementAndGet()
        val side = maxOf(preview.width, preview.height).coerceAtLeast(Ui.dp(this, 320))
        previewWorker.execute {
            if (generation != previewGeneration.get()) return@execute
            // While dragging, the nearest key frame keeps up with the finger; on release, the exact frame.
            val frame = runCatching { MotionFrameEditor.frame(loaded.clip, timeMs * 1000L, side, exact = final) }.getOrNull()
            runOnUiThread {
                if (frame == null) return@runOnUiThread
                if (generation != previewGeneration.get() || isDestroyed) { frame.recycle(); return@runOnUiThread }
                preview.setImageBitmap(frame)
            }
        }
    }

    private fun saveChosenFrame() {
        val loaded = source ?: return
        val time = chosenMs ?: return
        runWrite(R.string.motion_frame_saved) { MotionFrameEditor.saveFrame(applicationContext, uri, loaded, time * 1000L) }
    }

    private fun restoreOriginal() {
        val loaded = source ?: return
        if (!loaded.layout.edited) return
        runWrite(R.string.motion_frame_restored) { MotionFrameEditor.restoreOriginal(applicationContext, uri, loaded) }
    }

    private fun runWrite(done: Int, write: () -> Unit) {
        if (busy) return
        setBusy(true)
        worker.execute {
            try {
                write()
                MotionFrameEditor.announceChange(applicationContext, uri, mime)
                runOnUiThread {
                    Haptics.perform(saveButton, Haptics.Effect.CONFIRM)
                    Ui.toast(this, getString(done))
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: SecurityException) {
                runOnUiThread {
                    if (!requestWritePermission(error) { runWrite(done, write) }) {
                        setBusy(false)
                        Ui.toast(this, getString(R.string.motion_frame_save_error))
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("MotionFrame", "Falha ao gravar o quadro", error)
                runOnUiThread {
                    setBusy(false)
                    Haptics.perform(saveButton, Haptics.Effect.REJECT)
                    Ui.toast(this, getString(R.string.motion_frame_save_error))
                }
            }
        }
    }

    private fun requestWritePermission(error: SecurityException, retry: () -> Unit): Boolean = runCatching {
        val sender = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
            error.userAction.actionIntent.intentSender
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY) {
            MediaStore.createWriteRequest(contentResolver, listOf(uri)).intentSender
        } else return false
        pendingWrite = retry
        busy = false
        writePermission.launch(IntentSenderRequest.Builder(sender).build())
        true
    }.getOrDefault(false)

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        strip.isEnabled = !value && source != null
        saveButton.isEnabled = !value && chosenMs != null
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.4f
        originalButton.isEnabled = !value
    }

    /** Crop, rotate and draw stay in the regular editor, which saves a copy. */
    private fun openOtherEdits() {
        startActivity(Intent(this, ImageEditActivity::class.java).apply {
            putExtra("uri", uri.toString())
            putExtra("name", intent.getStringExtra(EXTRA_NAME))
            putExtra("mime", mime)
            putExtra(ImageEditActivity.EXTRA_MODE, ImageEditActivity.MODE_CUSTOM)
        })
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        chosenMs?.let { outState.putLong(STATE_CHOSEN, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        previewWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "motion_frame_uri"
        const val EXTRA_MIME = "motion_frame_mime"
        const val EXTRA_NAME = "motion_frame_name"
        private const val STATE_CHOSEN = "motion_frame_chosen"
    }
}
