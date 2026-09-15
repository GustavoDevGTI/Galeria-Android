package com.galeria.android

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

internal data class DetailImageMetadata(
    val width: Int? = null,
    val height: Int? = null,
    val make: String? = null,
    val model: String? = null,
    val capturedAt: String? = null,
    val iso: String? = null,
    val aperture: String? = null,
    val exposure: String? = null,
    val focalLength: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

internal data class DetailVideoMetadata(
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int = 0,
    val bitRate: Long? = null,
    val frameRate: Float? = null,
    val detectedMime: String? = null,
    val codec: String? = null
)

internal class DetailMetadataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    fun readImage(uri: Uri): DetailImageMetadata {
        var exifWidth: Int? = null
        var exifHeight: Int? = null
        var make: String? = null
        var model: String? = null
        var capturedAt: String? = null
        var iso: String? = null
        var aperture: String? = null
        var exposure: String? = null
        var focalLength: String? = null
        var latitude: Double? = null
        var longitude: Double? = null
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                exifWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                exifHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                capturedAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                exif.latLong?.let { coordinates ->
                    latitude = coordinates[0]
                    longitude = coordinates[1]
                }
            }
        } catch (_: Exception) {
        }
        val bounds = readImageBounds(uri)
        return DetailImageMetadata(
            width = exifWidth ?: bounds.first,
            height = exifHeight ?: bounds.second,
            make = make,
            model = model,
            capturedAt = capturedAt,
            iso = iso,
            aperture = aperture,
            exposure = exposure,
            focalLength = focalLength,
            latitude = latitude,
            longitude = longitude
        )
    }

    fun readVideo(uri: Uri, fallbackDurationMs: Long?): DetailVideoMetadata {
        var duration = fallbackDurationMs
        var width: Int? = null
        var height: Int? = null
        var rotation = 0
        var bitRate: Long? = null
        var frameRate: Float? = null
        var detectedMime: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: duration
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            detectedMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return DetailVideoMetadata(
            durationMs = duration,
            width = width,
            height = height,
            rotationDegrees = rotation,
            bitRate = bitRate,
            frameRate = frameRate,
            detectedMime = detectedMime,
            codec = detectVideoCodec(uri)
        )
    }

    fun resolveMediaSize(uri: Uri, declaredSize: Long): Long? {
        if (declaredSize > 0L) return declaredSize
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readImageBounds(uri: Uri): Pair<Int?, Int?> = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        options.outWidth.takeIf { it > 0 } to options.outHeight.takeIf { it > 0 }
    } catch (_: Exception) {
        null to null
    }

    private fun detectVideoCodec(uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, uri, null)
            var videoMime: String? = null
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoMime = mime
                    break
                }
            }
            videoCodecLabel(videoMime)
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    companion object {
        internal fun videoCodecLabel(videoMime: String?): String? = when (videoMime) {
            "video/avc" -> "H.264 / AVC"
            "video/hevc" -> "H.265 / HEVC"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4 Visual"
            null -> null
            else -> videoMime.substringAfter("video/").uppercase(Locale.US)
        }
    }
}
