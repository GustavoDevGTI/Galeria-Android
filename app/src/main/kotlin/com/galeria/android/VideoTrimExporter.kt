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

internal object VideoTrimRules {
    fun minimumDuration(durationMs: Long): Long = minOf(500L, maxOf(1L, durationMs / 4))

    fun range(durationMs: Long, startProgress: Int, endProgress: Int): LongRange {
        require(durationMs > 0L)
        val minimum = minimumDuration(durationMs)
        val start = (durationMs * startProgress.coerceIn(0, 1000) / 1000L).coerceAtMost(durationMs - minimum)
        val end = durationMs * endProgress.coerceIn(0, 1000) / 1000L
        return start..end.coerceIn(start + minimum, durationMs)
    }

    fun outputName(sourceName: String, timestamp: Long): String {
        val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val base = leaf.substringBeforeLast('.', leaf).replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim()
        return "${base.ifBlank { "video" }}-cortado-$timestamp.mp4"
    }
}

/** Copia amostras codificadas para MP4, sem alterar o original nem descartar trilhas conhecidas. */
internal object VideoTrimExporter {
    fun export(context: Context, source: Uri, name: String, startMs: Long, endMs: Long): Uri {
        require(startMs >= 0 && endMs > startMs)
        val resolver = context.contentResolver
        require(name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".m4v", ignoreCase = true) ||
            resolver.getType(source)?.equals("video/mp4", ignoreCase = true) == true) {
            "Para não perder trilhas, o corte está disponível apenas para arquivos MP4."
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
            require(videoTrack >= 0) { "Este arquivo não contém vídeo editável." }
            require(formats.all {
                val mime = it.getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }) { "Este vídeo contém legendas ou trilhas que não podem ser preservadas no corte." }
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || formats.size <= 2) {
                "Este Android não permite preservar todas as trilhas deste vídeo."
            }
            probe.selectTrack(videoTrack)
            probe.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = probe.sampleTime.coerceAtLeast(0L)
            val endUs = endMs * 1000L
            require(endUs > actualStartUs) { "Escolha um intervalo maior para cortar." }

            val outputName = VideoTrimRules.outputName(name, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Galeria Editada/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: throw IllegalStateException("Não foi possível criar o vídeo editado.")
                outputDescriptor = resolver.openFileDescriptor(outputUri, "rw")
                    ?: throw IllegalStateException("Não foi possível abrir o destino do vídeo.")
                muxer = MediaMuxer(outputDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Galeria Editada")
                if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Sem acesso para salvar o vídeo editado.")
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
            extractor.seekTo(actualStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var wroteVideo = false
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Corte cancelado")
                val track = extractor.sampleTrackIndex
                val sampleTime = extractor.sampleTime
                if (track < 0 || sampleTime < 0 || sampleTime >= endUs) break
                if (sampleTime >= actualStartUs) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size == buffer.capacity()) throw IllegalStateException("Amostra maior que o limite suportado.")
                    val sampleFlags = extractor.sampleFlags
                    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                        "Não é possível cortar vídeo com amostras protegidas."
                    }
                    var codecFlags = 0
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    }
                    info.set(0, size, sampleTime - actualStartUs, codecFlags)
                    muxer.writeSampleData(trackMap[track], buffer, info)
                    if (track == videoTrack) wroteVideo = true
                }
                if (!extractor.advance()) break
            }
            require(wroteVideo) { "Nenhum quadro de vídeo foi encontrado no intervalo." }
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
}
