package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.Executors

internal object ImageTextRecognition {
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun recognize(context: Context, uri: Uri, isRelevant: () -> Boolean = { true }, detailed: Boolean = true, onComplete: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        decodeExecutor.execute {
            if (!isRelevant()) return@execute
            val result = runCatching {
                val bitmap = decode(appContext, uri, if (detailed) 4096 else 2560)
                try {
                    if (!isRelevant()) return@execute
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        // Serial processing avoids simultaneous decodes while quickly paging through photos.
                        var text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
                        // Scanned documents may have pixels rotated without EXIF orientation.
                        if (text.isBlank() && detailed) {
                            for (rotation in listOf(90, 270, 180)) {
                                if (!isRelevant()) break
                                text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation))).text
                                if (text.isNotBlank()) break
                            }
                        }
                        text
                    } finally { recognizer.close() }
                } finally { bitmap.recycle() }
            }
            mainHandler.post { if (isRelevant()) onComplete(result) }
        }
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maxSide) inSampleSize *= 2
        }
        val bitmap = requireNotNull(context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) })
        val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        if (exif == null || (!exif.isFlipped && exif.rotationDegrees == 0)) return bitmap
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
