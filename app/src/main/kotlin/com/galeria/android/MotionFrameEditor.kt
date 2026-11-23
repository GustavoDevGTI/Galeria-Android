package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Reads a Motion Photo, takes a frame from its clip and writes the chosen or original photo back. */
internal object MotionFrameEditor {
    private const val MAX_FILE_BYTES = 96L * 1024L * 1024L
    private const val JPEG_QUALITY = 95

    class Source(val bytes: ByteArray, val layout: MotionFrameRules.Layout, val clip: File, val durationMs: Long)

    /** Camera fields the chosen frame keeps from the original photo (dates, place, camera). */
    private val keptTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL
    )

    fun read(context: Context, uri: Uri): Source? {
        val clip = MotionPhotoSupport.detect(context, uri) ?: return null
        val bytes = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val size = descriptor.statSize
                if (size > MAX_FILE_BYTES) return null
                input.readBytes()
            }
        } ?: return null
        val layout = MotionFrameRules.layout(bytes) ?: return null
        val file = MotionPhotoSupport.cachedClip(context, uri, clip)
        val duration = MediaMetadataRetriever().useRetriever(file) {
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        return Source(bytes, layout, file, duration)
    }

    /** The exact frame at [timeUs]; scaled down to [maxSide] for previews, full size when null. */
    fun frame(clip: File, timeUs: Long, maxSide: Int? = null, exact: Boolean = true): Bitmap? =
        MediaMetadataRetriever().useRetriever(clip) {
            val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (maxSide != null && android.os.Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(timeUs, option, maxSide, maxSide)
            else getFrameAtTime(timeUs, option)
        }

    /** The original photo, as the camera stored it. */
    fun originalPhoto(source: Source): ByteArray =
        source.bytes.copyOfRange(source.layout.originalStart, source.layout.originalEnd)

    fun saveFrame(context: Context, uri: Uri, source: Source, timeUs: Long) {
        val bitmap = frame(source.clip, timeUs) ?: throw IOException("Não foi possível ler o quadro")
        val jpeg = try { encodeWithCameraFields(context, bitmap, originalPhoto(source)) } finally { bitmap.recycle() }
        write(context, uri, source.bytes, MotionFrameRules.withChosenFrame(source.bytes, source.layout, jpeg, timeUs))
    }

    fun restoreOriginal(context: Context, uri: Uri, source: Source) {
        check(source.layout.edited)
        write(context, uri, source.bytes, MotionFrameRules.restored(source.bytes, source.layout))
    }

    /** Tells the rest of the app the photo changed: caches, catalog and the system media index. */
    fun announceChange(context: Context, uri: Uri, mime: String) {
        MediaContentRevision.changed(context, uri)
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context)
        runCatching {
            MediaActions.fileFromMediaStore(context, uri)?.let { file ->
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, _ ->
                    MediaStoreRepository.invalidateCache()
                    GalleryCatalogStore.markCatalogDirty(context)
                }
            }
        }
    }

    private fun encodeWithCameraFields(context: Context, bitmap: Bitmap, original: ByteArray): ByteArray {
        val folder = File(context.cacheDir, "motion_frames").apply { mkdirs() }
        val temp = File.createTempFile("frame-", ".jpg", folder)
        try {
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            runCatching {
                val from = ExifInterface(ByteArrayInputStream(original))
                val to = ExifInterface(temp.absolutePath)
                keptTags.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
                // The frame is already upright; the original's rotation flag no longer applies.
                to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                to.saveAttributes()
            }
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    /**
     * Writes [next] over the photo. The current bytes are kept in a private backup until the
     * write is complete, and put back if it fails halfway.
     */
    private fun write(context: Context, uri: Uri, current: ByteArray, next: ByteArray) {
        val folder = File(context.cacheDir, "motion_frames").apply { mkdirs() }
        val backup = File(folder, "backup-${uri.toString().hashCode()}.jpg")
        backup.writeBytes(current)
        try {
            overwrite(context, uri, next)
        } catch (error: Exception) {
            runCatching { overwrite(context, uri, current) }
            throw error
        }
        backup.delete()
    }

    private fun overwrite(context: Context, uri: Uri, bytes: ByteArray) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Não foi possível abrir a foto para gravação")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).channel.use { channel ->
                channel.truncate(0L)
                channel.position(0L)
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
    }

    private inline fun <T> MediaMetadataRetriever.useRetriever(file: File, block: MediaMetadataRetriever.() -> T): T =
        try { setDataSource(file.absolutePath); block() } finally { release() }
}
