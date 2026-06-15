package com.galeria.android

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class ImageEditActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var editor: EditorView
    private lateinit var sourceUri: Uri
    private var sourceName: String? = null
    private var mimeType: String? = null
    private lateinit var brushButton: Button
    private lateinit var cropButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUri = Uri.parse(intent.getStringExtra("uri").orEmpty())
        sourceName = intent.getStringExtra("name")
        mimeType = intent.getStringExtra("mime")
        buildLayout()
        loadImage()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@ImageEditActivity, 10), statusBarHeight() + Ui.dp(this@ImageEditActivity, 6), Ui.dp(this@ImageEditActivity, 10), Ui.dp(this@ImageEditActivity, 6))
            setBackgroundColor(Color.BLACK)
        }

        val back = Ui.button(this, "Voltar").apply {
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 86), Ui.dp(this, 42)))

        val title = Ui.title(this, "Editar imagem", 18).apply { setTextColor(Color.WHITE) }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(this@ImageEditActivity, 10)
        }
        bar.addView(title, titleParams)

        val save = Ui.button(this, "Salvar").apply {
            setOnClickListener { saveEditedImage() }
        }
        bar.addView(save, LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 42)))
        root.addView(bar)

        val stage = FrameLayout(this)
        editor = EditorView(this)
        stage.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@ImageEditActivity, 12), Ui.dp(this@ImageEditActivity, 8), Ui.dp(this@ImageEditActivity, 12), navigationBarHeight() + Ui.dp(this@ImageEditActivity, 8))
            setBackgroundColor(Color.BLACK)
        }
        brushButton = Ui.button(this, "Pincel").apply {
            setOnClickListener {
                editor.brushEnabled = !editor.brushEnabled
                refreshToolButtons()
            }
        }
        cropButton = Ui.button(this, "Cortar").apply {
            setOnClickListener {
                editor.cropEnabled = !editor.cropEnabled
                refreshToolButtons()
            }
        }
        val clear = Ui.button(this, "Limpar").apply {
            setOnClickListener { editor.clearDrawing() }
        }

        tools.addView(brushButton, toolParams())
        tools.addView(cropButton, toolParams())
        tools.addView(clear, toolParams())
        root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        refreshToolButtons()
    }

    private fun toolParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f).apply {
            leftMargin = Ui.dp(this@ImageEditActivity, 5)
            rightMargin = Ui.dp(this@ImageEditActivity, 5)
        }

    private fun refreshToolButtons() {
        brushButton.alpha = if (editor.brushEnabled) 1f else 0.55f
        cropButton.alpha = if (editor.cropEnabled) 1f else 0.55f
    }

    private fun loadImage() {
        executor.execute {
            try {
                val bitmap = decodeBitmap(sourceUri, 3000)
                runOnUiThread { editor.setBitmap(bitmap) }
            } catch (_: Exception) {
                runOnUiThread { Ui.toast(this, "Não foi possível abrir a imagem.") }
            }
        }
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

    private fun saveEditedImage() {
        executor.execute {
            try {
                val edited = editor.renderEditedBitmap() ?: throw IllegalStateException("bitmap")
                val saved = saveBitmapToGallery(edited)
                runOnUiThread {
                    Ui.toast(this, if (saved != null) "Imagem editada salva." else "Não foi possível salvar.")
                    if (saved != null) finish()
                }
            } catch (_: Exception) {
                runOnUiThread { Ui.toast(this, "Não foi possível salvar.") }
            }
        }
    }

    @Throws(Exception::class)
    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val outputName = editedName()
        val png = isPng()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Galeria Editada/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver: ContentResolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri).use { output ->
                if (output == null) return null
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 94, output)
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        }

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Galeria Editada")
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        var target = File(dir, outputName)
        var count = 1
        while (target.exists()) {
            target = File(dir, "$count-$outputName")
            count++
        }
        FileOutputStream(target).use { output ->
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 94, output)
        }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        return Uri.fromFile(target)
    }

    private fun isPng(): Boolean {
        val mime = mimeType.orEmpty().lowercase(Locale.US)
        val name = sourceName.orEmpty().lowercase(Locale.US)
        return mime.contains("png") || name.endsWith(".png")
    }

    private fun editedName(): String {
        val name = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: "imagem"
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (isPng()) ".png" else ".jpg"
        return "$base-editada$ext"
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    private class EditorView(activity: Activity) : View(activity) {
        private val paths = ArrayList<Path>()
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val bitmapToView = Matrix()
        private val viewToBitmap = Matrix()
        private val imageRect = RectF()
        private var bitmap: Bitmap? = null
        private var activePath: Path? = null
        var brushEnabled = true
        var cropEnabled = false
            set(value) {
                field = value
                invalidate()
            }

        init {
            setBackgroundColor(Color.BLACK)
        }

        fun setBitmap(bitmap: Bitmap) {
            this.bitmap = bitmap
            invalidate()
        }

        fun clearDrawing() {
            paths.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = bitmap ?: return
            updateMatrices()
            canvas.drawBitmap(current, bitmapToView, bitmapPaint)
            canvas.save()
            canvas.concat(bitmapToView)
            for (path in paths) {
                canvas.drawPath(path, brushPaint)
            }
            activePath?.let { canvas.drawPath(it, brushPaint) }
            canvas.restore()
            if (cropEnabled) {
                canvas.drawRect(cropRectView(), cropPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val current = bitmap ?: return true
            if (!brushEnabled) {
                return true
            }
            updateMatrices()
            val point = floatArrayOf(event.x, event.y)
            viewToBitmap.mapPoints(point)
            point[0] = max(0f, min(current.width.toFloat(), point[0]))
            point[1] = max(0f, min(current.height.toFloat(), point[1]))
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activePath = Path().apply { moveTo(point[0], point[1]) }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    activePath?.lineTo(point[0], point[1])
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    activePath?.let {
                        it.lineTo(point[0], point[1])
                        paths.add(it)
                    }
                    activePath = null
                    invalidate()
                    return true
                }
            }
            return true
        }

        fun renderEditedBitmap(): Bitmap? {
            val current = bitmap ?: return null
            val output = current.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(output)
            for (path in paths) {
                canvas.drawPath(path, brushPaint)
            }
            if (!cropEnabled) {
                return output
            }
            val crop = cropRectBitmap()
            val left = max(0, Math.round(crop.left))
            val top = max(0, Math.round(crop.top))
            val right = min(output.width, Math.round(crop.right))
            val bottom = min(output.height, Math.round(crop.bottom))
            return Bitmap.createBitmap(output, left, top, max(1, right - left), max(1, bottom - top))
        }

        private fun updateMatrices() {
            val current = bitmap ?: return
            if (width == 0 || height == 0) {
                return
            }
            val scale = min(width.toFloat() / current.width, height.toFloat() / current.height)
            val dx = (width - current.width * scale) / 2f
            val dy = (height - current.height * scale) / 2f
            bitmapToView.reset()
            bitmapToView.postScale(scale, scale)
            bitmapToView.postTranslate(dx, dy)
            bitmapToView.invert(viewToBitmap)
            imageRect.set(dx, dy, dx + current.width * scale, dy + current.height * scale)
        }

        private fun cropRectView(): RectF {
            updateMatrices()
            val insetX = imageRect.width() * 0.08f
            val insetY = imageRect.height() * 0.08f
            return RectF(
                imageRect.left + insetX,
                imageRect.top + insetY,
                imageRect.right - insetX,
                imageRect.bottom - insetY
            )
        }

        private fun cropRectBitmap(): RectF {
            val bitmapRect = RectF(cropRectView())
            viewToBitmap.mapRect(bitmapRect)
            return bitmapRect
        }
    }
}
