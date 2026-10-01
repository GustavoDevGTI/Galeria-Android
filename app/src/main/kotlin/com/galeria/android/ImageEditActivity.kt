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
import android.widget.ImageButton
import android.view.inputmethod.InputMethodManager
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
        const val EXTRA_ROTATE_CLOCKWISE = "image_edit_rotate_clockwise"
        const val MODE_CUSTOM = "custom"
        const val MODE_CROP = "crop"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var editor: EditorView
    private lateinit var sourceUri: Uri
    private var sourceName: String? = null
    private var mimeType: String? = null
    private lateinit var brushButton: ImageButton
    private lateinit var cropButton: ImageButton
    private lateinit var textButton: ImageButton
    private lateinit var filterButton: ImageButton
    private lateinit var stage: FrameLayout
    private lateinit var colorSpectrum: EditorColorSpectrum
    private lateinit var cropTools: LinearLayout
    private var textInput: EditText? = null
    private var drawingColor = Color.WHITE
    private var brushPreset = 0
    private var saving = false
    private var ocrRunning = false
    private lateinit var filterRow: LinearLayout
    private var selectedFilter = EditorFilter.ORIGINAL
    private var cropOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
            setPadding(Ui.dp(this@ImageEditActivity, 10), Ui.dp(this@ImageEditActivity, 6), Ui.dp(this@ImageEditActivity, 10), Ui.dp(this@ImageEditActivity, 6))
            setBackgroundColor(Color.BLACK)
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val back = EditorUi.button(this, R.drawable.ic_back, "Voltar") { finish() }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))

        val title = Ui.title(this, getString(if (cropOnly) R.string.image_edit_crop_title else R.string.image_edit_custom_title), 18)
            .apply { setTextColor(Color.WHITE) }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@ImageEditActivity, 10)
        }
        bar.addView(title, titleParams)

        val save = EditorUi.button(this, R.drawable.ic_check, "Salvar cópia") { commitText(); saveEditedImage() }
        bar.addView(save, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        root.addView(bar)

        stage = FrameLayout(this)
        editor = EditorView(this)
        editor.tag = "editor_canvas"
        editor.setOnLongClickListener { recognizeText(); true }
        stage.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ImageEditActivity, 8), Ui.dp(this@ImageEditActivity, 6), Ui.dp(this@ImageEditActivity, 8), Ui.dp(this@ImageEditActivity, 6))
            setBackgroundColor(Color.BLACK)
        }
        val toolRow = LinearLayout(this)
        cropButton = EditorUi.button(this, R.drawable.ic_crop, "Cortar") {
                commitText()
                editor.tool = if (editor.tool == EditorTool.CROP) EditorTool.NONE else EditorTool.CROP
                refreshToolButtons()
        }
        val rotate = EditorUi.button(this, R.drawable.ic_rotate, "Girar") {
            commitText(); editor.rotateClockwise(); selectedFilter = EditorFilter.ORIGINAL; refreshToolButtons()
        }
        val resize = EditorUi.button(this, R.drawable.ic_resize, "Redimensionar imagem") { commitText(); showResizeInput() }
        textButton = EditorUi.button(this, R.drawable.ic_text, "Texto") {
                showTextInput()
                refreshToolButtons()
        }
        brushButton = EditorUi.button(this, R.drawable.ic_brush, "Pincel") {
                commitText()
                if (editor.tool == EditorTool.BRUSH) brushPreset = (brushPreset + 1) % 6
                editor.tool = EditorTool.BRUSH
                editor.brushPreset = brushPreset
                val description = listOf("Fino", "Médio", "Grosso", "Marcador fino", "Marcador médio", "Marcador grosso")[brushPreset]
                Ui.toast(this, description)
                refreshToolButtons()
        }
        filterButton = EditorUi.button(this, R.drawable.ic_filter, "Filtros") {
                commitText()
                editor.tool = EditorTool.NONE
                filterRow.visibility = if (filterRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refreshToolButtons()
        }
        val clear = EditorUi.button(this, R.drawable.ic_undo, "Limpar marcas") { commitText(); editor.clearDrawing() }
        val ocr = EditorUi.button(this, R.drawable.ic_text_recognition, getString(R.string.action_recognize_text)) { recognizeText() }
        val buttons = if (cropOnly) listOf(cropButton, rotate) else listOf(cropButton, rotate, textButton, brushButton, filterButton, ocr, resize, clear)
        buttons.forEach { chip ->
            toolRow.addView(chip, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        }
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolRow)
        }
        colorSpectrum = EditorColorSpectrum(this) { color ->
            drawingColor = color
            editor.drawingColor = color
            textInput?.setTextColor(color)
        }.apply { visibility = View.GONE }
        tools.addView(colorSpectrum, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)).apply { bottomMargin = Ui.dp(this@ImageEditActivity, 8) })
        cropTools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(EditorUi.button(context, R.drawable.ic_ratio, "Proporção do recorte") { showCropRatios() }, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)))
            addView(EditorUi.button(context, R.drawable.ic_flip, "Espelhar") { editor.flipHorizontal() }, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)))
            addView(EditorUi.button(context, R.drawable.ic_reset, "Restaurar recorte") { editor.resetCrop() }, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)))
        }
        tools.addView(cropTools)
        tools.addView(toolScroll)
        filterRow = LinearLayout(this).apply {
            visibility = View.GONE
            EditorFilter.entries.forEach { filter ->
                addView(EditorUi.button(this@ImageEditActivity, R.drawable.ic_filter, filter.label) {
                        selectedFilter = filter
                        editor.filter = filter
                        refreshToolButtons()
                }.apply { setColorFilter(filter.previewColor) }, LinearLayout.LayoutParams(Ui.dp(this@ImageEditActivity, 48), Ui.dp(this@ImageEditActivity, 48)).apply {
                    marginEnd = Ui.dp(this@ImageEditActivity, 6)
                })
            }
        }
        tools.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterRow)
        })
        root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        refreshToolButtons()
    }

    private fun showTextInput() {
        if (textInput != null) { commitText(); return }
        editor.tool = EditorTool.TEXT
        val input = EditText(this).apply {
            tag = "editor_inline_text"
            hint = "Texto na imagem"
            setTextColor(drawingColor)
            setHintTextColor(0xFFCCCCCC.toInt())
            textSize = 24f
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            background = Ui.rounded(0x99222222.toInt(), 8, this@ImageEditActivity)
            maxLines = 4
            setSingleLine(false)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { commitText(); true } else false
            }
        }
        textInput = input
        stage.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = Ui.dp(this@ImageEditActivity, 24); marginEnd = marginStart
        })
        input.requestFocus()
        input.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun commitText() {
        val input = textInput ?: return
        val value = input.text.toString().trim().take(300)
        if (value.isNotEmpty()) editor.addText(value, input.x + input.width / 2f, input.y + input.baseline, input.textSize)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        stage.removeView(input)
        textInput = null
    }

    private fun showCropRatios() {
        val labels = arrayOf("Livre", "Original", "1:1", "4:3", "16:9", "9:16")
        AlertDialog.Builder(this).setTitle("Proporção do recorte").setItems(labels) { _, index ->
            editor.setCropRatio(when (index) { 1 -> editor.currentSize()?.let { it.first.toFloat() / it.second } ?: 0f; 2 -> 1f; 3 -> 4f / 3f; 4 -> 16f / 9f; 5 -> 9f / 16f; else -> 0f })
        }.show()
    }

    private fun recognizeText() {
        if (ocrRunning) return
        ocrRunning = true
        Ui.toast(this, getString(R.string.ocr_processing))
        ImageTextRecognition.recognize(this, sourceUri, isRelevant = { !isDestroyed && !isFinishing }, detailed = true) { result ->
            ocrRunning = false
            result.fold(onSuccess = {
                if (it.isBlank()) Ui.toast(this, getString(R.string.ocr_no_text)) else EditorUi.recognizedText(this, it)
            }, onFailure = { Ui.toast(this, getString(R.string.ocr_error)) })
        }
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
        fun style(view: View, selected: Boolean) {
            view.background = Ui.rounded(if (selected) 0xFF383838.toInt() else Color.TRANSPARENT, 14, this)
            view.alpha = if (selected) 1f else 0.82f
        }
        style(brushButton, editor.tool == EditorTool.BRUSH)
        style(cropButton, editor.tool == EditorTool.CROP)
        style(textButton, editor.tool == EditorTool.TEXT)
        style(filterButton, filterRow.visibility == View.VISIBLE)
        for (index in 0 until filterRow.childCount) {
            style(filterRow.getChildAt(index), EditorFilter.entries[index] == selectedFilter)
        }
        colorSpectrum.visibility = if (editor.tool == EditorTool.BRUSH || editor.tool == EditorTool.TEXT) View.VISIBLE else View.GONE
        cropTools.visibility = if (editor.tool == EditorTool.CROP) View.VISIBLE else View.GONE
    }

    private fun loadImage() {
        executor.execute {
            try {
                val bitmap = decodeBitmap(sourceUri, 4096)
                runOnUiThread {
                    if (isDestroyed || isFinishing) { bitmap.recycle(); return@runOnUiThread }
                    editor.setBitmap(bitmap)
                    if (intent.getBooleanExtra(EXTRA_ROTATE_CLOCKWISE, false)) editor.rotateClockwise()
                    if (cropOnly) editor.tool = EditorTool.CROP
                    editor.contentDescription = if (cropOnly) getString(R.string.image_edit_crop_ready) else "Imagem pronta para edição"
                    refreshToolButtons()
                }
            } catch (_: Exception) {
                runOnUiThread { Ui.toast(this, "Não foi possível abrir a imagem.") }
            }
        }
    }

    @Throws(Exception::class)
    private fun decodeBitmap(uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28 && !ImageRotation.isEditedPng(this, uri, mimeType, sourceName)) {
            return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
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
        val exif = contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it) }
        if (exif == null || (!exif.isFlipped && exif.rotationDegrees == 0)) return decoded
        val matrix = Matrix().apply { if (exif.isFlipped) postScale(-1f, 1f); postRotate(exif.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun saveEditedImage() {
        if (saving) return
        val edited = editor.renderEditedBitmap()
        if (edited == null) {
            Ui.toast(this, "Aguarde a imagem carregar.")
            return
        }
        saving = true
        executor.execute {
            try {
                val saved = saveBitmapToGallery(edited)
                runOnUiThread {
                    saving = false
                    Ui.toast(this, if (saved != null) "Imagem editada salva." else "Não foi possível salvar.")
                    if (saved != null) finish()
                }
            } catch (_: Exception) {
                runOnUiThread { saving = false; Ui.toast(this, "Não foi possível salvar.") }
            } finally {
                edited.recycle()
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

    private class EditorView(activity: Activity) : View(activity) {
        private data class Stroke(val path: Path, val paint: Paint)
        private val paths = ArrayList<Stroke>()
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
        private var activePaint: Paint? = null
        var drawingColor = Color.WHITE
        var brushPreset = 0
        private var cropRatio = 0f
        private var longPressX = 0f
        private var longPressY = 0f
        private val longPress = Runnable { performLongClick() }
        private var cropRect: RectF? = null
        private var cropDrag = 0
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        var tool = EditorTool.NONE
            set(value) {
                field = value
                if (value == EditorTool.CROP && cropRect == null) {
                    bitmap?.let { cropRect = RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
                }
                invalidate()
            }
        var filter = EditorFilter.ORIGINAL
            set(value) { field = value; invalidate() }

        init {
            setBackgroundColor(Color.BLACK)
        }

        fun setBitmap(bitmap: Bitmap) {
            val previous = this.bitmap
            this.bitmap = bitmap
            if (previous !== bitmap) previous?.recycle()
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

        fun addText(value: String, viewX: Float, viewY: Float, viewTextSize: Float) {
            val current = bitmap ?: return
            updateMatrices()
            val point = floatArrayOf(viewX, viewY)
            viewToBitmap.mapPoints(point)
            val size = viewTextSize * current.width / imageRect.width().coerceAtLeast(1f)
            labels.add(PlacedText(value, point[0], point[1], drawingColor, size))
            invalidate()
        }

        fun currentSize(): Pair<Int, Int>? = bitmap?.let { it.width to it.height }

        fun resizeWidth(width: Int): Boolean {
            if (width !in 1..4096) return false
            val source = renderEditedBitmap() ?: return false
            val height = (source.height.toLong() * width / source.width).coerceAtLeast(1L)
            if (height > 4096L) { source.recycle(); return false }
            val resized = Bitmap.createScaledBitmap(source, width, height.toInt(), true)
            setBitmap(resized)
            if (resized !== source) source.recycle()
            return true
        }

        fun rotateClockwise() {
            val cropping = tool == EditorTool.CROP
            val rendered = renderEditedBitmap() ?: return
            val matrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, matrix, true)
            setBitmap(rotated)
            if (rotated !== rendered) rendered.recycle()
            if (cropping) tool = EditorTool.CROP
        }

        fun flipHorizontal() {
            val cropping = tool == EditorTool.CROP
            val rendered = renderEditedBitmap() ?: return
            val flipped = Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, Matrix().apply { setScale(-1f, 1f) }, true)
            setBitmap(flipped)
            if (flipped !== rendered) rendered.recycle()
            if (cropping) tool = EditorTool.CROP
        }

        fun resetCrop() {
            cropRatio = 0f
            bitmap?.let { cropRect = RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
            invalidate()
        }

        fun setCropRatio(ratio: Float) {
            val current = bitmap ?: return
            cropRatio = ratio
            resetCrop()
            cropRatio = ratio
            val rect = cropRect ?: return
            if (ratio > 0f) {
                val width = min(current.width.toFloat(), current.height * ratio)
                val height = width / ratio
                rect.set((current.width - width) / 2f, (current.height - height) / 2f, (current.width + width) / 2f, (current.height + height) / 2f)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = bitmap ?: return
            updateMatrices()
            bitmapPaint.colorFilter = filter.colorFilter()
            canvas.drawBitmap(current, bitmapToView, bitmapPaint)
            canvas.save()
            canvas.clipRect(imageRect)
            canvas.concat(bitmapToView)
            for (stroke in paths) {
                canvas.drawPath(stroke.path, stroke.paint)
            }
            activePath?.let { canvas.drawPath(it, activePaint ?: brushPaint) }
            textPaint.textSize = max(28f, current.width * 0.045f)
            for (label in labels) drawLabel(canvas, label)
            canvas.restore()
            if (cropRect != null && tool == EditorTool.CROP) {
                val bounds = RectF(requireNotNull(cropRect))
                bitmapToView.mapRect(bounds)
                val shade = Paint().apply { color = 0x99000000.toInt() }
                canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, bounds.top, shade)
                canvas.drawRect(imageRect.left, bounds.bottom, imageRect.right, imageRect.bottom, shade)
                canvas.drawRect(imageRect.left, bounds.top, bounds.left, bounds.bottom, shade)
                canvas.drawRect(bounds.right, bounds.top, imageRect.right, bounds.bottom, shade)
                cropPaint.strokeWidth = Ui.dp(context, 1).toFloat()
                cropPaint.alpha = 130
                for (part in 1..2) {
                    val x = bounds.left + bounds.width() * part / 3f
                    val y = bounds.top + bounds.height() * part / 3f
                    canvas.drawLine(x, bounds.top, x, bounds.bottom, cropPaint)
                    canvas.drawLine(bounds.left, y, bounds.right, y, cropPaint)
                }
                cropPaint.alpha = 255
                canvas.drawRect(bounds, cropPaint)
                cropPaint.strokeWidth = Ui.dp(context, 3).toFloat()
                val length = Ui.dp(context, 18).toFloat().coerceAtMost(bounds.width() / 4)
                for (x in listOf(bounds.left, bounds.right)) for (y in listOf(bounds.top, bounds.bottom)) {
                    canvas.drawLine(x, y, x + if (x == bounds.left) length else -length, y, cropPaint)
                    canvas.drawLine(x, y, x, y + if (y == bounds.top) length else -length, cropPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val current = bitmap ?: return true
            updateMatrices()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                parent?.requestDisallowInterceptTouchEvent(true)
                longPressX = event.x; longPressY = event.y
                if (tool == EditorTool.NONE) postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                kotlin.math.abs(event.x - longPressX) > Ui.dp(context, 8) || kotlin.math.abs(event.y - longPressY) > Ui.dp(context, 8)) removeCallbacks(longPress)
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
                    activePaint = Paint(brushPaint).apply {
                        color = drawingColor
                        alpha = if (brushPreset >= 3) 100 else 255
                        strokeCap = if (brushPreset >= 3) Paint.Cap.SQUARE else Paint.Cap.ROUND
                        // Width is screen-relative, not fixed source pixels (invisible on large photos).
                        strokeWidth = (floatArrayOf(3f, 7f, 14f)[brushPreset % 3] * resources.displayMetrics.density) * current.width / imageRect.width().coerceAtLeast(1f)
                    }
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
                        paths.add(Stroke(it, Paint(activePaint ?: brushPaint)))
                    }
                    activePath = null
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { activePath = null; activePaint = null; invalidate() }
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
            for (stroke in paths) {
                canvas.drawPath(stroke.path, stroke.paint)
            }
            textPaint.textSize = max(28f, current.width * 0.045f)
            for (label in labels) drawLabel(canvas, label)
            val crop = cropRect ?: return output
            val left = max(0, Math.round(crop.left))
            val top = max(0, Math.round(crop.top))
            val right = min(output.width, Math.round(crop.right))
            val bottom = min(output.height, Math.round(crop.bottom))
            return Bitmap.createBitmap(output, left, top, max(1, right - left), max(1, bottom - top)).also { if (it !== output) output.recycle() }
        }

        private fun drawLabel(canvas: Canvas, label: PlacedText) {
            textPaint.color = label.color
            textPaint.textSize = label.size
            textPaint.textAlign = Paint.Align.CENTER
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
                    val threshold = Ui.dp(context, 28) * current.width / imageRect.width().coerceAtLeast(1f)
                    cropDrag = when {
                        kotlin.math.abs(x - rect.left) < threshold && kotlin.math.abs(y - rect.top) < threshold -> 1
                        kotlin.math.abs(x - rect.right) < threshold && kotlin.math.abs(y - rect.top) < threshold -> 2
                        kotlin.math.abs(x - rect.left) < threshold && kotlin.math.abs(y - rect.bottom) < threshold -> 3
                        kotlin.math.abs(x - rect.right) < threshold && kotlin.math.abs(y - rect.bottom) < threshold -> 4
                        kotlin.math.abs(x - rect.left) < threshold -> 6
                        kotlin.math.abs(x - rect.right) < threshold -> 7
                        kotlin.math.abs(y - rect.top) < threshold -> 8
                        kotlin.math.abs(y - rect.bottom) < threshold -> 9
                        rect.contains(x, y) -> 5
                        else -> 0
                    }
                    lastTouchX = x
                    lastTouchY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    val before = RectF(rect)
                    val minimum = min(current.width, current.height) * 0.1f
                    when (cropDrag) {
                        1 -> { rect.left = x.coerceIn(0f, rect.right - minimum); rect.top = y.coerceIn(0f, rect.bottom - minimum) }
                        2 -> { rect.right = x.coerceIn(rect.left + minimum, current.width.toFloat()); rect.top = y.coerceIn(0f, rect.bottom - minimum) }
                        3 -> { rect.left = x.coerceIn(0f, rect.right - minimum); rect.bottom = y.coerceIn(rect.top + minimum, current.height.toFloat()) }
                        4 -> { rect.right = x.coerceIn(rect.left + minimum, current.width.toFloat()); rect.bottom = y.coerceIn(rect.top + minimum, current.height.toFloat()) }
                        6 -> rect.left = x.coerceIn(0f, rect.right - minimum)
                        7 -> rect.right = x.coerceIn(rect.left + minimum, current.width.toFloat())
                        8 -> rect.top = y.coerceIn(0f, rect.bottom - minimum)
                        9 -> rect.bottom = y.coerceIn(rect.top + minimum, current.height.toFloat())
                        5 -> {
                            val dx = (x - lastTouchX).coerceIn(-rect.left, current.width - rect.right)
                            val dy = (y - lastTouchY).coerceIn(-rect.top, current.height - rect.bottom)
                            rect.offset(dx, dy)
                        }
                    }
                    if (cropRatio > 0f && cropDrag != 5 && cropDrag != 0) {
                        if (cropDrag == 8 || cropDrag == 9) {
                            val targetWidth = rect.height() * cropRatio
                            rect.left = before.centerX() - targetWidth / 2f
                            rect.right = before.centerX() + targetWidth / 2f
                        } else {
                            val targetHeight = rect.width() / cropRatio
                            if (cropDrag == 1 || cropDrag == 2) rect.top = rect.bottom - targetHeight
                            else rect.bottom = rect.top + targetHeight
                        }
                        if (rect.left < 0f || rect.top < 0f || rect.right > current.width || rect.bottom > current.height) rect.set(before)
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
            val inset = Ui.dp(context, 20).toFloat()
            val scale = min((width - 2 * inset).coerceAtLeast(1f) / current.width, (height - 2 * inset).coerceAtLeast(1f) / current.height)
            val dx = (width - current.width * scale) / 2f
            val dy = (height - current.height * scale) / 2f
            bitmapToView.reset()
            bitmapToView.postScale(scale, scale)
            bitmapToView.postTranslate(dx, dy)
            bitmapToView.invert(viewToBitmap)
            imageRect.set(dx, dy, dx + current.width * scale, dy + current.height * scale)
        }

        override fun onDetachedFromWindow() { removeCallbacks(longPress); super.onDetachedFromWindow() }

        private data class PlacedText(val value: String, var x: Float, var y: Float, val color: Int, val size: Float)
    }
}

private enum class EditorTool { NONE, BRUSH, CROP, TEXT }

private enum class EditorFilter(val label: String) {
    ORIGINAL("Original"), MONO("P&B"), WARM("Quente"), COOL("Frio"), SOFT("Suave");

    val previewColor: Int get() = when (this) {
        ORIGINAL -> Color.WHITE; MONO -> 0xFFAAAAAA.toInt(); WARM -> 0xFFFFC28B.toInt()
        COOL -> 0xFF9BBFFF.toInt(); SOFT -> 0xFFD6C5DD.toInt()
    }

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
