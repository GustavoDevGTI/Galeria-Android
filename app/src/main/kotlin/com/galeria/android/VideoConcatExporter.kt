package com.galeria.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** What a track must share with the same track of every other video for samples to be copied. */
internal data class TrackSignature(
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    /** Codec setup (csd-0, csd-1, csd-2) as hex: the same camera settings give the same bytes. */
    val setup: List<String> = emptyList()
)

internal object VideoConcatRules {
    /** Position given to the montage record kept in a `uuid` box, so it never clashes with a photo. */
    const val RECORD_INDEX = -1
    const val RECORD_MIME = "text/x-galeria-join"

    /**
     * A merge can be written by copying samples, much faster than encoding, when every media is a
     * whole MP4 video, at Native, without transitions. The files must also match track by track
     * ([compatible]); otherwise the merge is encoded as usual.
     */
    fun eligible(request: JoinRequest): Boolean =
        request.clips.size > 1 && request.quality == JoinQuality.NATIVE &&
            request.transitions.all { it == JoinTransition.NONE } &&
            request.clips.all { it.media.isVideo && !it.trimmed && it.media.durationMs > 0L && VideoTrimRules.copyable(it.media.name, it.media.mime) }

    /** Same tracks, in the same order, with the same codec setup and the same rotation. */
    fun compatible(tracks: List<List<TrackSignature>>, rotations: List<Int>): Boolean =
        tracks.isNotEmpty() && tracks.all { it == tracks.first() } && rotations.all { it == rotations.first() } &&
            tracks.first().any { it.mime.startsWith("video/") } &&
            tracks.first().all { it.mime.startsWith("video/") || it.mime.startsWith("audio/") }

    /** Where each video starts in the merged one, in microseconds: one after the other. */
    fun offsetsUs(durationsMs: List<Long>): List<Long> {
        var at = 0L
        return durationsMs.map { duration -> at.also { at += duration * 1000L } }
    }
}

/** Writes a merge of whole, matching MP4 videos by copying their samples one after the other. */
internal object VideoConcatExporter {
    /** Thrown when the videos do not match; the caller encodes instead. */
    class NotCopyable : Exception()

    fun export(context: Context, request: JoinRequest, name: String, record: ByteArray,
        progress: (Int) -> Unit, cancelled: () -> Boolean): Uri {
        val medias = request.clips.map { it.media }
        val extractors = medias.map { MediaExtractor() }
        val temp = File(File(context.cacheDir, "media_join").apply { mkdirs() }, "merge-${System.nanoTime()}.mp4")
        var muxer: MediaMuxer? = null
        var started = false
        try {
            medias.forEachIndexed { index, media -> extractors[index].setDataSource(context, Uri.parse(media.uri), null) }
            val formats = extractors.map { extractor -> (0 until extractor.trackCount).map(extractor::getTrackFormat) }
            val rotations = medias.map { rotation(context, Uri.parse(it.uri)) }
            if (!VideoConcatRules.compatible(formats.map { list -> list.map(::signature) }, rotations)) throw NotCopyable()
            if (formats.flatten().any { it.getString(MediaFormat.KEY_MIME).isNullOrEmpty() }) throw NotCopyable()

            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val tracks = formats.first().map { muxer.addTrack(it) }
            muxer.setOrientationHint(rotations.first())
            val maxSample = formats.flatten().mapNotNull {
                if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null
            }.maxOrNull() ?: 0
            val buffer = ByteBuffer.allocateDirect(maxOf(4 * 1024 * 1024, maxSample).coerceAtMost(64 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val offsets = VideoConcatRules.offsetsUs(medias.map { it.durationMs })
            val totalUs = medias.sumOf { it.durationMs * 1000L }.coerceAtLeast(1L)
            muxer.start()
            started = true
            var lastPercent = -1
            extractors.forEachIndexed { index, extractor ->
                val endUs = medias[index].durationMs * 1000L
                for (track in 0 until extractor.trackCount) extractor.selectTrack(track)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val track = extractor.sampleTrackIndex
                    val time = extractor.sampleTime
                    if (track < 0 || time < 0L) break
                    // A track that runs past the video's length would overlap the next video.
                    if (time < endUs) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        if (size == buffer.capacity()) throw NotCopyable()
                        val flags = extractor.sampleFlags
                        if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw NotCopyable()
                        var codecFlags = 0
                        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        info.set(0, size, offsets[index] + time, codecFlags)
                        muxer.writeSampleData(tracks[track], buffer, info)
                        val percent = ((offsets[index] + time) * 100L / totalUs).toInt().coerceIn(0, 99)
                        if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                    }
                    if (!extractor.advance()) break
                }
            }
            muxer.stop()
            started = false
            muxer.release()
            muxer = null
            // The montage record, so Separar works on this merge like on an encoded one.
            JoinPhotoBoxes.append(temp, VideoConcatRules.RECORD_INDEX, VideoConcatRules.RECORD_MIME, record)
            if (cancelled()) throw CancellationException()
            val saved = MediaJoinExporter.save(context, Uri.parse(medias.first().uri), temp, name, "video/mp4")
            progress(100)
            return saved
        } finally {
            extractors.forEach { runCatching { it.release() } }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            temp.delete()
        }
    }

    private fun signature(format: MediaFormat): TrackSignature {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else 0
        val setup = listOf("csd-0", "csd-1", "csd-2").mapNotNull { key ->
            format.getByteBuffer(key)?.let { buffer ->
                val copy = buffer.duplicate().apply { rewind() }
                buildString { while (copy.hasRemaining()) append("%02x".format(copy.get())) }
            }
        }
        return if (mime.startsWith("video/")) TrackSignature(mime, int(MediaFormat.KEY_WIDTH), int(MediaFormat.KEY_HEIGHT), setup = setup)
        else TrackSignature(mime, sampleRate = int(MediaFormat.KEY_SAMPLE_RATE), channels = int(MediaFormat.KEY_CHANNEL_COUNT), setup = setup)
    }

    private fun rotation(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { retriever.release() }
    }
}
