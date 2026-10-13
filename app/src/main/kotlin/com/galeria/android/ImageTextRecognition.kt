package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executors

internal object ImageTextRecognition {
    private val decodeExecutor = Executors.newSingleThreadExecutor { work ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "GalleryOCR")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    // Reuse the bundled local model rather than reinitializing it for every photo.
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun recognize(context: Context, uri: Uri, isRelevant: () -> Boolean = { true }, detailed: Boolean = true, onComplete: (Result<String>) -> Unit) {
        recognizeDocument(context, uri, isRelevant, detailed) { result -> onComplete(result.map { it.text }) }
    }

    fun recognizeDocument(context: Context, uri: Uri, isRelevant: () -> Boolean = { true }, detailed: Boolean = true,
        onComplete: (Result<OcrDocument>) -> Unit) {
        val appContext = context.applicationContext
        recognizeBitmap({ decode(appContext, uri, detailed) }, isRelevant, detailed, onComplete)
    }

    /** The factory executes on the OCR worker and transfers bitmap ownership. */
    fun recognizeBitmap(createBitmap: () -> Bitmap, isRelevant: () -> Boolean, detailed: Boolean = true,
        onComplete: (Result<OcrDocument>) -> Unit) {
        decodeExecutor.execute {
            if (!isRelevant()) return@execute
            val result = runCatching {
                val bitmap = normalize(createBitmap())
                try {
                    if (!isRelevant()) return@execute
                    val inverted = if (hasDarkBackground(bitmap)) invert(bitmap) else null
                    try {
                        var best = readOrientations(recognizer, inverted ?: bitmap, detailed, isRelevant)
                        if (inverted != null && !OcrRules.isReliable(best) && isRelevant()) {
                            best = OcrRules.best(best, readOrientations(recognizer, bitmap, detailed, isRelevant))
                        }
                        best.document
                    } finally { inverted?.recycle() }
                } finally { bitmap.recycle() }
            }
            result.exceptionOrNull()?.let { Log.w("GalleryOCR", "Falha no reconhecimento local de texto", it) }
            mainHandler.post { if (isRelevant()) onComplete(result) }
        }
    }

    private fun readOrientations(recognizer: TextRecognizer, bitmap: Bitmap, detailed: Boolean, isRelevant: () -> Boolean): OcrReading {
        var best = OcrReading("")
        for (rotation in OcrRules.rotations) {
            if (!isRelevant()) break
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)))
            val lines = result.textBlocks.flatMap { it.lines }
            val characters = lines.sumOf { it.text.length }.coerceAtLeast(1)
            val confidence = lines.sumOf { it.confidence.toDouble() * it.text.length } / characters
            var lineId = 0
            val words = result.textBlocks.flatMapIndexed { blockId, block ->
                block.lines.flatMap { line ->
                    val id = lineId++
                    line.elements.mapNotNull { element ->
                        val points = element.cornerPoints?.toList() ?: element.boundingBox?.let { box ->
                            listOf(android.graphics.Point(box.left, box.top), android.graphics.Point(box.right, box.top),
                                android.graphics.Point(box.right, box.bottom), android.graphics.Point(box.left, box.bottom))
                        }
                        if (points == null || points.size != 4 || element.text.isBlank()) null else OcrWord(element.text,
                            points.map { OcrGeometry.fromRotated(it.x.toFloat(), it.y.toFloat(), bitmap.width, bitmap.height, rotation) }, id, blockId)
                    }
                }
            }
            best = OcrRules.best(best, OcrReading(result.text, confidence.toFloat(), OcrDocument(result.text, words)))
            // Automatic detection is cheap for clear upright text, but partial
            // status-bar text must not prevent checking the other orientations.
            if (if (detailed) OcrRules.isDetailedReadingComplete(best) else OcrRules.isReliable(best)) break
        }
        return best
    }

    private fun hasDarkBackground(bitmap: Bitmap): Boolean {
        val pixels = IntArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            pixels[y * 32 + x] = bitmap.getPixel((x * bitmap.width / 32).coerceAtMost(bitmap.width - 1),
                (y * bitmap.height / 32).coerceAtMost(bitmap.height - 1))
        }
        return OcrRules.isDarkBackground(pixels)
    }

    private fun invert(bitmap: Bitmap): Bitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
        val colors = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
        Canvas(it).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(colors) })
        it.setHasAlpha(false)
    }

    private fun normalize(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.hasAlpha()) return bitmap
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            it.setHasAlpha(false)
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri, detailed: Boolean): Bitmap {
        if (Build.VERSION.SDK_INT >= 28 && !ImageRotation.isEditedPng(context, uri)) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val size = OcrRules.targetSize(info.size.width, info.size.height, detailed)
                decoder.setTargetSize(size.first, size.second)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
            val target = OcrRules.targetSize(bounds.outWidth, bounds.outHeight, detailed)
            while (bounds.outWidth / inSampleSize > target.first || bounds.outHeight / inSampleSize > target.second) inSampleSize *= 2
        }
        val bitmap = requireNotNull(context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) })
        return ImageRotation.orientDecodedBitmap(context, uri, bitmap)
    }
}
