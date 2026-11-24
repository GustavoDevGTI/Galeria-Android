package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** What separating a joined video did, and what still needs the user's consent. */
internal data class SplitResult(
    val written: List<Uri>,
    /** Originals in the Trash that Android lets back only after asking the user. */
    val restoreNeedsConsent: List<Uri>,
    /** True once the joined video is in the Trash; false when Android must ask first. */
    val joinedTrashed: Boolean
)

/**
 * Gives back the media of a video joined by the app: originals still around are used, those in the
 * Trash are restored, and only what no longer exists is written again. The joined video then goes
 * to the Trash.
 */
internal object MediaSplitter {
    fun state(context: Context, uri: Uri): OriginState {
        if (uri.scheme == "file") {
            val path = uri.path.orEmpty()
            return when {
                File(path).exists() -> OriginState.PRESENT
                LegacyTrashStore.trashedCopyOf(context, path) != null -> OriginState.TRASHED
                else -> OriginState.GONE
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return if (MediaClockExpiry.exists(context, uri)) OriginState.PRESENT else OriginState.GONE
        }
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        val trashed = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) else null }
        }.getOrNull()
        return when (trashed) {
            null -> OriginState.GONE
            0 -> OriginState.PRESENT
            else -> OriginState.TRASHED
        }
    }

    fun split(context: Context, joined: Uri, record: JoinRecord): SplitResult {
        val states = record.clips.map { state(context, Uri.parse(it.media.uri)) }
        val steps = MediaSplitRules.plan(record, states, MediaJoinExporter.keptPhotos(context, joined))
        val written = ArrayList<Uri>()
        val consent = ArrayList<Uri>()
        try {
            steps.forEach { step ->
                val clip = record.clips[step.clipIndex]
                when (step) {
                    is SplitStep.Keep -> Unit
                    is SplitStep.Restore -> if (!restore(context, Uri.parse(step.uri))) consent += Uri.parse(step.uri)
                    is SplitStep.CutOriginal ->
                        written += MediaJoinExporter.export(context, JoinRequest(listOf(clip), record.photoMs, JoinQuality.NATIVE), {}, { false })
                    is SplitStep.CutJoined -> written += cutJoined(context, joined, clip, step)
                    is SplitStep.PhotoEmbedded -> written += keptPhoto(context, joined, clip, step)
                    is SplitStep.PhotoFrame -> written += frame(context, joined, record, clip, step)
                }
            }
        } catch (error: Exception) {
            // Half a separation would leave copies behind: what was written goes away again.
            written.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        }
        // The montage is undone: its originals are kept instead of leaving after 48 hours.
        record.clips.forEach { MediaClocks.cancel(context, it.media.uri) }
        val trashed = consent.isEmpty() && trashJoined(context, joined)
        return SplitResult(written, consent, trashed)
    }

    /** Sends the joined video to the Trash, even with the Trash off: it is not lost. */
    fun trashJoined(context: Context, joined: Uri): Boolean = try {
        MediaClockExpiry.trash(context, joined).also { if (it) announce(context) }
    } catch (_: SecurityException) {
        false
    }

    private fun restore(context: Context, uri: Uri): Boolean = try {
        val done = if (uri.scheme == "file") {
            LegacyTrashStore.trashedCopyOf(context, uri.path.orEmpty())?.let { LegacyTrashStore.restore(context, it) } ?: false
        } else {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null) > 0
        }
        if (done) announce(context)
        done
    } catch (_: SecurityException) {
        false
    }

    /** The part of the joined video where the media played, encoded again exactly. */
    private fun cutJoined(context: Context, joined: Uri, clip: JoinClip, step: SplitStep.CutJoined): Uri {
        val media = MediaJoinExporter.probe(context, joined, "video/mp4", clip.media.name, 0L) ?: throw IOException("Vídeo juntado ilegível")
        val end = step.endMs.coerceAtMost(media.durationMs)
        val request = JoinRequest(listOf(JoinClip(media, listOf(step.startMs..end))), MediaJoinRules.DEFAULT_PHOTO_SECONDS * 1000L, JoinQuality.NATIVE)
        val name = if (step.newVideo) VideoTrimRules.outputName(clip.media.name, System.currentTimeMillis())
            else clip.media.name.substringBeforeLast('.', clip.media.name) + ".mp4"
        return MediaJoinExporter.encode(context, request, joined, name, {}, { false })
    }

    private fun keptPhoto(context: Context, joined: Uri, clip: JoinClip, step: SplitStep.PhotoEmbedded): Uri {
        val photo = MediaJoinExporter.keptPhoto(context, joined, step.clipIndex) ?: throw IOException("Foto guardada ilegível")
        val temp = File(context.cacheDir, "split-${System.nanoTime()}")
        try {
            temp.writeBytes(photo.bytes)
            return MediaJoinExporter.save(context, joined, temp, clip.media.name, photo.mime)
        } finally {
            temp.delete()
        }
    }

    /** A frame of the joined video, without the black borders around the photo. */
    private fun frame(context: Context, joined: Uri, record: JoinRecord, clip: JoinClip, step: SplitStep.PhotoFrame): Uri {
        val retriever = MediaMetadataRetriever()
        val picture = try {
            retriever.setDataSource(context, joined)
            retriever.getFrameAtTime(step.atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: throw IOException("Quadro ilegível")
        } finally {
            retriever.release()
        }
        val area = MediaSplitRules.fitted(picture.width, picture.height, clip.media.width, clip.media.height)
        val cropped = Bitmap.createBitmap(picture, area.left, area.top, area.width, area.height)
        val temp = File(context.cacheDir, "split-${System.nanoTime()}.jpg")
        try {
            temp.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            return MediaJoinExporter.save(context, joined, temp, MediaSplitRules.frameName(clip.media.name), "image/jpeg")
        } finally {
            temp.delete()
            if (cropped !== picture) cropped.recycle()
            picture.recycle()
        }
    }

    private fun announce(context: Context) {
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context.applicationContext)
    }
}
