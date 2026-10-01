package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.ExifOrientationStrategy
import coil3.request.ImageRequest
import java.io.IOException
import java.util.Locale

internal object ImageRotation {
    private val rotatedPngDecoder = BitmapFactoryDecoder.Factory(
        exifOrientationStrategy = ExifOrientationStrategy.RESPECT_ALL
    )

    fun configureRequest(context: Context, item: MediaItem, builder: ImageRequest.Builder) {
        // Coil's default legacy decoder ignores PNG orientation. Opt in only for
        // changed PNGs; do not add expensive EXIF parsing to every thumbnail.
        if (isEditedPng(context, item.uri, item.mimeType, item.name)) {
            builder.decoderFactory(rotatedPngDecoder)
        }
    }

    fun isEditedPng(context: Context, uri: Uri, mime: String? = null, name: String? = null): Boolean {
        if (MediaContentRevision.key(context, uri) == uri.toString()) return false
        val resolvedMime = mime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return resolvedMime.equals("image/png", true) || (name ?: uri.lastPathSegment).orEmpty().endsWith(".png", true)
    }

    /** Only use with BitmapFactory output, which has not applied EXIF itself. */
    fun orientDecodedBitmap(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        if (exif == null || (!exif.isFlipped && exif.rotationDegrees == 0)) return bitmap
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    fun supportsInPlace(item: MediaItem): Boolean {
        val mime = item.mimeType.lowercase(Locale.ROOT)
        val extension = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return mime in setOf("image/jpeg", "image/jpg", "image/png", "image/webp") ||
            extension in setOf("jpg", "jpeg", "png", "webp")
    }

    /** Update orientation rather than downsampling and recompressing the original. */
    fun clockwise(context: Context, item: MediaItem) {
        check(supportsInPlace(item))
        context.contentResolver.openFileDescriptor(item.uri, "rw")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            // Camera orientation is optional (screenshots/Motion Photos may have
            // no EXIF at all). ExifInterface.rotate does not rotate UNDEFINED.
            if (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED) == ExifInterface.ORIENTATION_UNDEFINED) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            }
            exif.rotate(90)
            exif.saveAttributes()
        } ?: throw IOException("Não foi possível abrir a imagem para gravação")
    }
}
