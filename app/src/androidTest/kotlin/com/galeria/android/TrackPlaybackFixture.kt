package com.galeria.android

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.File
import java.nio.ByteBuffer

/** Real decoder input, private files only: no MediaStore insert/scan/delete. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TrackPlaybackFixture(context: Context, assets: AssetManager) : AutoCloseable {
    private val directory = File(context.cacheDir, "track-test-${System.nanoTime()}").apply { mkdirs() }
    val mediaItem: MediaItem

    init {
        try {
            val source = copy(assets, "playback-sample.mp4")
            val dual = File(directory, "dual.mp4")
            duplicateAudio(source, dual)
            val subtitles = listOf("en" to "tracks-english.srt", "pt" to "tracks-portuguese.srt").map { (language, asset) ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(copy(assets, asset)))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(language).build()
            }
            mediaItem = MediaItem.Builder().setUri(Uri.fromFile(dual)).setSubtitleConfigurations(subtitles).build()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun copy(assets: AssetManager, name: String) = File(directory, name).also { file ->
        assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }

    private fun duplicateAudio(source: File, target: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.path)
            val audio = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val writer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val outputs = (0 until extractor.trackCount).map { index ->
                writer.addTrack(extractor.getTrackFormat(index).apply {
                    if (index == audio) setString(MediaFormat.KEY_LANGUAGE, "por")
                })
            }
            val duplicate = writer.addTrack(extractor.getTrackFormat(audio).apply {
                setString(MediaFormat.KEY_LANGUAGE, "eng")
            })
            outputs.indices.forEach(extractor::selectTrack)
            writer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (extractor.sampleTrackIndex >= 0) {
                val track = extractor.sampleTrackIndex
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                writer.writeSampleData(outputs[track], buffer, info)
                if (track == audio) writer.writeSampleData(duplicate, buffer, info)
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
            try { if (started) muxer?.stop() } finally { muxer?.release() }
        }
    }

    override fun close() {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }
}
