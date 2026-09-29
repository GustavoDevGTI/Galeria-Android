package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    companion object {
        const val EXTRA_MODE = "image_edit_mode"
        const val MODE_CUSTOM = "custom"
        const val MODE_CROP = "crop"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var editor: EditorView
    private lateinit var sourceUri: Uri
    private var sourceName: String? = null
    private var mimeType: String? = null
    private lateinit var brushButton: TextView
    private lateinit var cropButton: TextView
    private lateinit var textButton: TextView
    private lateinit var filterButton: TextView
    private lateinit var filterRow: LinearLayout
    private var selectedFilter = EditorFilter.ORIGINAL
    private var cropOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUri = Uri.parse(intent.getStringExtra("uri").orEmpty())
        sourceName = intent.getStringExtra("name")
        mimeType = intent.getStringExtra("mime")
        cropOnly = intent.getStringExtra(EXTRA_MODE) == MODE_CROP
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

        val title = Ui.title(this, getString(if (cropOnly) R.string.image_edit_crop_title else R.string.image_edit_custom_title), 18)
            .apply { setTextColor(Color.WHITE) }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@ImageEditActivity, 10)
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
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ImageEditActivity, 8), Ui.dp(this@ImageEditActivity, 6), Ui.dp(this@ImageEditActivity, 8), navigationBarHeight() + Ui.dp(this@ImageEditActivity, 6))
            setBackgroundColor(Color.BLACK)
        }
        val toolRow = LinearLayout(this)
        cropButton = toolChip("Cortar").apply {
            setOnClickListener {
                editor.tool = if (editor.tool == EditorTool.CROP) EditorTool.NONE else EditorTool.CROP
                refreshToolButtons()
            }
        }
        val rotate = toolChip("Girar").apply {
            setOnClickListener { editor.rotateClockwise(); selectedFilter = EditorFilter.ORIGINAL; refreshToolButtons() }
        }
        val resize = toolChip("Tamanho").apply {
            setOnClickListener { showResizeInput() }
        }
        textButton = toolChip("Texto").apply {
            setOnClickListener {
                showTextInput()
                refreshToolButtons()
            }
        }
        brushButton = toolChip("Pincel").apply {
            setOnClickListener {
                editor.tool = if (editor.tool == EditorTool.BRUSH) EditorTool.NONE else EditorTool.BRUSH
                refreshToolButtons()
            }
        }
        filterButton = toolChip("Filtros").apply {
            setOnClickListener {
                filterRow.visibility = if (filterRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refreshToolButtons()
            }
        }
        val clear = toolChip("Limpar marcas").apply {
            setOnClickListener { editor.clearDrawing() }
        }
        listOf(cropButton, rotate, resize, textButton, brushButton, filterButton, clear).forEach { chip ->
            toolRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)).apply {
                marginEnd = Ui.dp(this@ImageEditActivity, 6)
            })
        }
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolRow)
        }
        tools.addView(toolScroll)
        filterRow = LinearLayout(this).apply {
            visibility = View.GONE
            EditorFilter.entries.forEach { filter ->
                addView(toolChip(filter.label).apply {
                    setOnClickListener {
                        selectedFilter = filter
                        editor.filter = filter
                        refreshToolButtons()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this@ImageEditActivity, 40)).apply {
                    marginEnd = Ui.dp(this@ImageEditActivity, 6)
                })
            }
        }
        tools.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterRow)
        })
        if (!cropOnly) {
            root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        refreshToolButtons()
    }

    private fun toolChip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = Ui.dp(this@ImageEditActivity, 64)
        setPadding(Ui.dp(this@ImageEditActivity, 12), 0, Ui.dp(this@ImageEditActivity, 12), 0)
        background = Ui.rounded(0xFF292929.toInt(), 14, this@ImageEditActivity)
        isClickable = true
        isFocusable = true
    }

    private fun showTextInput() {
        val input = EditText(this).apply {
            hint = "Texto na imagem"
            maxLines = 2
            setSingleLine(false)
        }
        AlertDialog.Builder(this).setTitle("Adicionar texto").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                val value = input.text.toString().trim().take(80)
                if (value.isNotEmpty()) {
                    editor.addText(value)
                    editor.tool = EditorTool.TEXT
                    refreshToolButtons()
                }
            }.show()
    }

    private fun showResizeInput() {
        val size = editor.currentSize() ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(size.first.toString())
            selectAll()
            hint = "Largura em pixels"
        }
        AlertDialog.Builder(this)
            .setTitle("Redimensionar imagem")
            .setMessage("Defina a largura. A altura será ajustada proporcionalmente (máximo de 4096 px).")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                val width = input.text.toString().toIntOrNull()
                if (width == null || !editor.resizeWidth(width)) {
                    Ui.toast(this, "Escolha uma largura válida de até 4096 px.")
                } else {
                    selectedFilter = EditorFilter.ORIGINAL
                    refreshToolButtons()
                }
            }
            .show()
    }

    private fun refreshToolButtons() {
        fun style(view: TextView, selected: Boolean) {
            view.background = Ui.rounded(if (selected) 0xFF535353.toInt() else 0xFF292929.toInt(), 14, this)
            view.alpha = if (selected) 1f else 0.82f
        }
        style(brushButton, editor.tool == EditorTool.BRUSH)
        style(cropButton, editor.tool == EditorTool.CROP)
        style(textButton, editor.tool == EditorTool.TEXT)
        style(filterButton, filterRow.visibility == View.VISIBLE)
        for (index in 0 until filterRow.childCount) {
            style(filterRow.getChildAt(index) as TextView, EditorFilter.entries[index] == selectedFilter)
        }
    }

    private fun loadImage() {
        executor.execute {
            try {
                val bitmap = decodeBitmap(sourceUri, 3000)
                runOnUiThread {
                    editor.setBitmap(bitmap)
                    if (cropOnly) editor.tool = EditorTool.CROP
                    if (cropOnly) editor.contentDescription = getString(R.string.image_edit_crop_ready)
                    refreshToolButtons()
                }
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
        val edited = editor.renderEditedBitmap()
        if (edited == null) {
            Ui.toast(this, "Aguarde a imagem carregar.")
            return
        }
        executor.execute {
            try {
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
            try {
                resolver.openOutputStream(uri).use { output ->
                    if (output == null || !bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 94, output)) {
                        throw IllegalStateException("Não foi possível gravar a imagem")
                    }
                }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
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
        private val labels = ArrayList<PlacedText>()
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
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        private val bitmapToView = Matrix()
        private val viewToBitmap = Matrix()
        private val imageRect = RectF()
        private var bitmap: Bitmap? = null
        private var activePath: Path? = null
        private var cropRect: RectF? = null
        private var cropDrag = 0
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        var tool = EditorTool.NONE
            set(value) {
                field = value
                if (value == EditorTool.CROP && cropRect == null) {
                    bitmap?.let { cropRect = RectF(it.width * 0.08f, it.height * 0.08f, it.width * 0.92f, it.height * 0.92f) }
                }
                invalidate()
            }
        var filter = EditorFilter.ORIGINAL
            set(value) { field = value; invalidate() }

        init {
            setBackgroundColor(Color.BLACK)
        }

        fun setBitmap(bitmap: Bitmap) {
            this.bitmap = bitmap
            paths.clear()
            labels.clear()
            activePath = null
            cropRect = null
            filter = EditorFilter.ORIGINAL
            tool = EditorTool.NONE
            invalidate()
        }

        fun clearDrawing() {
            paths.clear()
            labels.clear()
            activePath = null
            invalidate()
        }

        fun addText(value: String) {
            val current = bitmap ?: return
            labels.add(PlacedText(value, current.width * 0.16f, current.height * 0.5f))
            invalidate()
        }

        fun currentSize(): Pair<Int, Int>? = bitmap?.let { it.width to it.height }

        fun resizeWidth(width: Int): Boolean {
            val source = renderEditedBitmap() ?: return false
            if (width !in 1..4096) return false
            val height = (source.height.toLong() * width / source.width).coerceAtLeast(1L)
            if (height > 4096L) return false
            setBitmap(Bitmap.createScaledBitmap(source, width, height.toInt(), true))
            return true
        }

        fun rotateClockwise() {
            val rendered = renderEditedBitmap() ?: return
            val matrix = Matrix().apply { postRotate(90f) }
            setBitmap(Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, matrix, true))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = bitmap ?: return
            updateMatrices()
            bitmapPaint.colorFilter = filter.colorFilter()
            canvas.drawBitmap(current, bitmapToView, bitmapPaint)
            canvas.save()
            canvas.concat(bitmapToView)
            for (path in paths) {
                canvas.drawPath(path, brushPaint)
            }
            activePath?.let { canvas.drawPath(it, brushPaint) }
            textPaint.textSize = max(28f, current.width * 0.045f)
            for (label in labels) drawLabel(canvas, label)
            canvas.restore()
            if (cropRect != null) {
                val bounds = RectF(requireNotNull(cropRect))
                bitmapToView.mapRect(bounds)
                canvas.drawRect(bounds, cropPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val current = bitmap ?: return true
            updateMatrices()
            val point = floatArrayOf(event.x, event.y)
            viewToBitmap.mapPoints(point)
            point[0] = max(0f, min(current.width.toFloat(), point[0]))
            point[1] = max(0f, min(current.height.toFloat(), point[1]))
            if (tool == EditorTool.CROP) return handleCropTouch(event, point, current)
            if (tool == EditorTool.TEXT) {
                if (event.actionMasked == MotionEvent.ACTION_MOVE && labels.isNotEmpty()) {
                    labels.last().x = point[0]
                    labels.last().y = point[1]
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            if (tool != EditorTool.BRUSH) {
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            when (event.actionMasked) {
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

        override fun performClick(): Boolean = super.performClick()

        fun renderEditedBitmap(): Bitmap? {
            val current = bitmap ?: return null
            val output = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            bitmapPaint.colorFilter = filter.colorFilter()
            canvas.drawBitmap(current, 0f, 0f, bitmapPaint)
            for (path in paths) {
                canvas.drawPath(path, brushPaint)
            }
            textPaint.textSize = max(28f, current.width * 0.045f)
            for (label in labels) drawLabel(canvas, label)
            val crop = cropRect ?: return output
            val left = max(0, Math.round(crop.left))
            val top = max(0, Math.round(crop.top))
            val right = min(output.width, Math.round(crop.right))
            val bottom = min(output.height, Math.round(crop.bottom))
            return Bitmap.createBitmap(output, left, top, max(1, right - left), max(1, bottom - top))
        }

        private fun drawLabel(canvas: Canvas, label: PlacedText) {
            label.value.lines().forEachIndexed { index, line ->
                canvas.drawText(line, label.x, label.y + index * textPaint.textSize * 1.2f, textPaint)
            }
        }

        private fun handleCropTouch(event: MotionEvent, point: FloatArray, current: Bitmap): Boolean {
            val rect = cropRect ?: return true
            val x = point[0]
            val y = point[1]
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val threshold = min(current.width, current.height) * 0.09f
                    cropDrag = when {
                        kotlin.math.abs(x - rect.left) < threshold && kotlin.math.abs(y - rect.top) < threshold -> 1
                        kotlin.math.abs(x - rect.right) < threshold && kotlin.math.abs(y - rect.top) < threshold -> 2
                        kotlin.math.abs(x - rect.left) < threshold && kotlin.math.abs(y - rect.bottom) < threshold -> 3
                        kotlin.math.abs(x - rect.right) < threshold && kotlin.math.abs(y - rect.bottom) < threshold -> 4
                        rect.contains(x, y) -> 5
                        else -> 0
                    }
                    lastTouchX = x
                    lastTouchY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    val minimum = min(current.width, current.height) * 0.1f
                    when (cropDrag) {
                        1 -> { rect.left = x.coerceIn(0f, rect.right - minimum); rect.top = y.coerceIn(0f, rect.bottom - minimum) }
                        2 -> { rect.right = x.coerceIn(rect.left + minimum, current.width.toFloat()); rect.top = y.coerceIn(0f, rect.bottom - minimum) }
                        3 -> { rect.left = x.coerceIn(0f, rect.right - minimum); rect.bottom = y.coerceIn(rect.top + minimum, current.height.toFloat()) }
                        4 -> { rect.right = x.coerceIn(rect.left + minimum, current.width.toFloat()); rect.bottom = y.coerceIn(rect.top + minimum, current.height.toFloat()) }
                        5 -> {
                            val dx = (x - lastTouchX).coerceIn(-rect.left, current.width - rect.right)
                            val dy = (y - lastTouchY).coerceIn(-rect.top, current.height - rect.bottom)
                            rect.offset(dx, dy)
                        }
                    }
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> { cropDrag = 0; performClick() }
                MotionEvent.ACTION_CANCEL -> cropDrag = 0
            }
            return true
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

        private data class PlacedText(val value: String, var x: Float, var y: Float)
    }
}

private enum class EditorTool { NONE, BRUSH, CROP, TEXT }

private enum class EditorFilter(val label: String) {
    ORIGINAL("Original"), MONO("P&B"), WARM("Quente"), COOL("Frio"), SOFT("Suave");

    fun colorFilter(): ColorMatrixColorFilter? {
        val matrix = when (this) {
            ORIGINAL -> return null
            MONO -> ColorMatrix().apply { setSaturation(0f) }
            WARM -> ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 3f, 0f, 0f, 0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            COOL -> ColorMatrix(floatArrayOf(0.93f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.12f, 0f, 6f, 0f, 0f, 0f, 1f, 0f))
            SOFT -> ColorMatrix().apply { setSaturation(0.78f) }
        }
        return ColorMatrixColorFilter(matrix)
    }
}
