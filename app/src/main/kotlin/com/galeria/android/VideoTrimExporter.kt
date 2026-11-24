package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

internal object VideoTrimRules {
    fun minimumDuration(durationMs: Long): Long = minOf(500L, maxOf(1L, durationMs / 4))

    fun range(durationMs: Long, startProgress: Int, endProgress: Int): LongRange {
        require(durationMs > 0L)
        val minimum = minimumDuration(durationMs)
        val start = (durationMs * startProgress.coerceIn(0, 1000) / 1000L).coerceAtMost(durationMs - minimum)
        val end = durationMs * endProgress.coerceIn(0, 1000) / 1000L
        return start..end.coerceIn(start + minimum, durationMs)
    }

    /** The copy's name: the original's, then [mark] (what was done to it) and [timestamp]. */
    fun outputName(sourceName: String, timestamp: Long, mark: String = "cortado"): String {
        val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val base = leaf.substringBeforeLast('.', leaf).replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim()
        return "${base.ifBlank { "video" }}-$mark-$timestamp.mp4"
    }

    /** True when the source can be cut by copying its samples, without encoding again. */
    fun copyable(name: String, mime: String): Boolean =
        name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".m4v", ignoreCase = true) || mime.equals("video/mp4", ignoreCase = true)
}

/**
 * Copies encoded samples into a new MP4, without changing the original or dropping known tracks
 * (every audio language and subtitle stays). Several kept parts are written one after the other;
 * each part starts on the key frame at or before its start, so nothing is encoded again.
 */
internal object VideoTrimExporter {
    fun export(context: Context, source: Uri, name: String, startMs: Long, endMs: Long): Uri =
        export(context, source, name, listOf(startMs..endMs))

    fun export(
        context: Context,
        source: Uri,
        name: String,
        ranges: List<LongRange>,
        progress: (Int) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Uri {
        require(ranges.isNotEmpty() && ranges.all { it.first >= 0 && it.last > it.first })
        val resolver = context.contentResolver
        require(VideoTrimRules.copyable(name, resolver.getType(source).orEmpty())) {
            context.getString(R.string.trim_error_mp4_only)
        }
        val probe = MediaExtractor()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputUri: Uri? = null
        var outputFile: File? = null
        var outputDescriptor: android.os.ParcelFileDescriptor? = null
        var successful = false
        try {
            probe.setDataSource(context, source, null)
            val formats = (0 until probe.trackCount).map(probe::getTrackFormat)
            val videoTrack = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            require(videoTrack >= 0) { context.getString(R.string.trim_error_no_video) }
            require(formats.all {
                val mime = it.getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }) { context.getString(R.string.trim_error_tracks) }
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || formats.size <= 2) {
                context.getString(R.string.trim_error_android)
            }
            // Where each kept part really starts: the key frame at or before the chosen start.
            probe.selectTrack(videoTrack)
            val parts = ranges.map { range ->
                probe.seekTo(range.first * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val start = probe.sampleTime.coerceAtLeast(0L)
                require(range.last * 1000L > start) { context.getString(R.string.trim_error_short_range) }
                start to range.last * 1000L
            }
            val totalUs = parts.sumOf { it.second - it.first }.coerceAtLeast(1L)

            val outputName = VideoTrimRules.outputName(name, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outputUri = insertBeside(context, source, outputName)
                outputDescriptor = resolver.openFileDescriptor(outputUri, "rw")
                    ?: throw IllegalStateException(context.getString(R.string.trim_error_destination))
                muxer = MediaMuxer(outputDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val folder = editedFolder(context, source)
                if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException(context.getString(R.string.trim_error_no_access))
                outputFile = File(folder, outputName)
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            extractor.setDataSource(context, source, null)
            val trackMap = IntArray(formats.size)
            for (index in formats.indices) {
                trackMap[index] = muxer.addTrack(formats[index])
                extractor.selectTrack(index)
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()?.let(muxer::setOrientationHint)
            } finally {
                retriever.release()
            }
            val maxSample = formats.mapNotNull {
                if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null
            }.maxOrNull() ?: 0
            val buffer = ByteBuffer.allocateDirect(maxOf(4 * 1024 * 1024, maxSample).coerceAtMost(64 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            muxer.start()
            muxerStarted = true
            var wroteVideo = false
            var offsetUs = 0L
            var lastPercent = -1
            for ((startUs, endUs) in parts) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Corte cancelado")
                    if (cancelled()) throw CancellationException()
                    val track = extractor.sampleTrackIndex
                    val sampleTime = extractor.sampleTime
                    if (track < 0 || sampleTime < 0 || sampleTime >= endUs) break
                    if (sampleTime >= startUs) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        if (size == buffer.capacity()) throw IllegalStateException(context.getString(R.string.trim_error_sample_too_big))
                        val sampleFlags = extractor.sampleFlags
                        require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                            context.getString(R.string.trim_error_protected)
                        }
                        var codecFlags = 0
                        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        }
                        info.set(0, size, offsetUs + sampleTime - startUs, codecFlags)
                        muxer.writeSampleData(trackMap[track], buffer, info)
                        if (track == videoTrack) wroteVideo = true
                        val percent = ((offsetUs + sampleTime - startUs) * 100L / totalUs).toInt().coerceIn(0, 99)
                        if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                    }
                    if (!extractor.advance()) break
                }
                offsetUs += endUs - startUs
            }
            require(wroteVideo) { context.getString(R.string.trim_error_no_frames) }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            val result = if (outputUri != null) {
                resolver.update(outputUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                outputUri
            } else {
                val file = requireNotNull(outputFile)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                Uri.fromFile(file)
            }
            successful = true
            progress(100)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
            return requireNotNull(result)
        } finally {
            probe.release()
            extractor.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            outputDescriptor?.close()
            if (!successful) {
                outputUri?.let { runCatching { resolver.delete(it, null, null) } }
                outputFile?.delete()
            }
        }
    }

    /** The copy goes into the original's folder; a folder the system refuses falls back to the editor's own. */
    private fun insertBeside(context: Context, source: Uri, name: String): Uri {
        val resolver = context.contentResolver
        fun values(path: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Files in the app's hidden area have no MediaStore folder; their copy goes to the public one.
        val folder = if (source.scheme != "content") null else runCatching {
            resolver.query(source, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return folder?.let { runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values(it)) }.getOrNull() }
            ?: resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values("${Environment.DIRECTORY_MOVIES}/Galeria Editada/"))
            ?: throw IllegalStateException(context.getString(R.string.trim_error_create))
    }

    /**
     * Without MediaStore the copy goes beside a public original; a file in the app's hidden area
     * goes to the public "Galeria Editada" folder, as the viewer warns before opening the editor.
     */
    private fun editedFolder(context: Context, source: Uri): File {
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Galeria Editada")
        if (source.scheme == "file") return public
        return MediaActions.fileFromMediaStore(context, source)?.parentFile ?: public
    }
}
