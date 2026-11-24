package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Builds the joined video with Media3, writes the montage inside it and saves it beside the first media. */
@OptIn(UnstableApi::class)
internal object MediaJoinExporter {
    private const val FRAME_RATE = 30

    /** Reads the upright size and length of a photo or video. */
    fun probe(context: Context, uri: Uri, mime: String, name: String, size: Long): JoinMedia? = runCatching {
        if (mime.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val turned = rotation % 180 != 0
                JoinMedia(uri.toString(), mime, name, if (turned) height else width, if (turned) width else height, duration, size)
            } finally {
                retriever.release()
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val turned = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees % 180 != 0 } ?: false
            JoinMedia(uri.toString(), mime, name,
                if (turned) bounds.outHeight else bounds.outWidth, if (turned) bounds.outWidth else bounds.outHeight, 0L, size)
        }
    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 && (!it.isVideo || it.durationMs > 0L) }

    /** The same montage as a Media3 composition: used for the preview and for the export. */
    fun composition(context: Context, request: JoinRequest): Composition {
        val canvas = MediaJoinRules.canvas(request.clips.map { it.media }, request.quality)
        val fit = Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT)))
        fun itemsOf(clip: JoinClip): List<EditedMediaItem> = run {
            val media = clip.media
            if (media.isVideo) {
                clip.ranges.map { range ->
                    EditedMediaItem.Builder(PlayerMediaItem.Builder().setUri(media.uri)
                        .setClipStartPositionMs(range.first).setClipEndPositionMs(range.last).build())
                        // The preview player needs the length of the whole source, before clipping.
                        .setDurationUs(media.durationMs * 1000L).setEffects(fit).build()
                }
            } else {
                listOf(EditedMediaItem.Builder(PlayerMediaItem.Builder().setUri(media.uri).setMimeType(media.mime)
                    .setImageDurationMs(request.photoMs).build())
                    .setFrameRate(FRAME_RATE).setDurationUs(request.photoMs * 1000L).setEffects(fit).build())
            }
        }
        val windows = MediaJoinRules.windows(request.clips, request.photoMs, request.transitions)
        if (windows.isEmpty()) {
            // Photos are silent; the forced track keeps the videos' sound in step around them.
            val sequence = EditedMediaItemSequence.Builder(request.clips.flatMap(::itemsOf)).experimentalSetForceAudioTrack(true).build()
            return Composition.Builder(sequence).build()
        }
        // With transitions, clips alternate between two layers that overlap at each junction;
        // gaps keep every clip at its place in the joined video.
        val starts = MediaJoinRules.clipStarts(request.clips, request.photoMs, request.transitions)
        val layers = (0..1).map { layer ->
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            var at = 0L
            request.clips.forEachIndexed { index, clip ->
                if (MediaJoinRules.layerOf(index) != layer) return@forEachIndexed
                if (starts[index] > at) builder.addGap((starts[index] - at) * 1000L)
                builder.addItems(itemsOf(clip))
                at = starts[index] + MediaJoinRules.clipDurationMs(clip, request.photoMs)
            }
            // Declaring audio and video already fills gaps and photos with silence.
            builder.build()
        }
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvas.width, canvas.height)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                val look = MediaJoinRules.look(request.clips, request.photoMs, request.transitions, inputId, presentationTimeUs / 1000L)
                // Anchors run from -1 to 1, so a whole frame width (2) is split between them: the
                // layer's point at -shift is placed on the frame's point at +shift.
                return StaticOverlaySettings.Builder()
                    .setAlphaScale(look.alpha)
                    .setBackgroundFrameAnchor(look.shift, 0f)
                    .setOverlayFrameAnchor(-look.shift, 0f)
                    .setScale(look.scale, look.scale)
                    .build()
            }
        }
        return Composition.Builder(layers).setVideoCompositorSettings(settings).build()
    }

    /**
     * The editor's preview: one layer with each transition turned into a cut at its middle, same
     * length as the joined video. The editor draws the transition itself over the player.
     */
    fun previewComposition(context: Context, request: JoinRequest): Composition {
        if (MediaJoinRules.windows(request.clips, request.photoMs, request.transitions).isEmpty()) return composition(context, request)
        val canvas = MediaJoinRules.canvas(request.clips.map { it.media }, request.quality)
        val fit = Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT)))
        val trims = MediaJoinRules.previewTrims(request.clips, request.photoMs, request.transitions)
        val items = request.clips.flatMapIndexed { index, clip ->
            val (head, tail) = trims[index]
            val media = clip.media
            if (media.isVideo) {
                val ranges = clip.ranges.toMutableList()
                ranges[0] = (ranges[0].first + head)..ranges[0].last
                ranges[ranges.lastIndex] = ranges.last().first..(ranges.last().last - tail)
                ranges.filter { it.last > it.first }.map { range ->
                    EditedMediaItem.Builder(PlayerMediaItem.Builder().setUri(media.uri)
                        .setClipStartPositionMs(range.first).setClipEndPositionMs(range.last).build())
                        .setDurationUs(media.durationMs * 1000L).setEffects(fit).build()
                }
            } else {
                val ms = request.photoMs - head - tail
                listOf(EditedMediaItem.Builder(PlayerMediaItem.Builder().setUri(media.uri).setMimeType(media.mime)
                    .setImageDurationMs(ms).build())
                    .setFrameRate(FRAME_RATE).setDurationUs(ms * 1000L).setEffects(fit).build())
            }
        }
        return Composition.Builder(EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(true).build()).build()
    }

    /**
     * Writes the joined video and returns its address. [progress] gets 0 to 100; when
     * [cancelled] turns true the export stops and nothing is saved.
     */
    fun export(context: Context, request: JoinRequest, progress: (Int) -> Unit, cancelled: () -> Boolean): Uri {
        require(request.clips.isNotEmpty())
        if (MediaJoinRules.copiesWithoutEncoding(request)) {
            val clip = request.clips.single()
            try {
                return VideoTrimExporter.export(context, Uri.parse(clip.media.uri), clip.media.name, clip.ranges, progress, cancelled)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A file the copier cannot handle (odd tracks, protected samples) is encoded instead.
            }
        }
        return encode(context, request, Uri.parse(request.clips.first().media.uri), defaultName(request), progress, cancelled)
    }

    /** A join is named by its date; a single edited video keeps its name, marked as cut. */
    private fun defaultName(request: JoinRequest): String =
        if (request.clips.size > 1) MediaJoinRules.outputName(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        else VideoTrimRules.outputName(request.clips.single().media.name, System.currentTimeMillis())

    /** Encodes [request] exactly, frame by frame, and saves it as [name] in the folder of [beside]. */
    internal fun encode(context: Context, request: JoinRequest, beside: Uri, name: String,
        progress: (Int) -> Unit, cancelled: () -> Boolean): Uri {
        val temp = File(File(context.cacheDir, "media_join").apply { mkdirs() }, "join-${System.nanoTime()}.mp4")
        try {
            render(context, request, temp, progress, cancelled)
            if (cancelled()) throw CancellationException()
            if (request.keepPhotos) keepPhotos(context, request.clips, temp)
            return save(context, beside, temp, name, "video/mp4")
        } finally {
            temp.delete()
        }
    }

    /** Copies each photo of the montage, once, after the video boxes. */
    private fun keepPhotos(context: Context, clips: List<JoinClip>, file: File) {
        val seen = HashSet<String>()
        clips.forEachIndexed { index, clip ->
            val media = clip.media
            if (media.isVideo || !seen.add(MediaIdentityRules.canonicalKey(media.uri))) return@forEachIndexed
            val bytes = context.contentResolver.openInputStream(Uri.parse(media.uri))?.use { it.readBytes() } ?: return@forEachIndexed
            JoinPhotoBoxes.append(file, index, media.mime, bytes)
        }
    }

    /** The montage positions of the original photos kept inside a joined video. */
    fun keptPhotos(context: Context, uri: Uri): Set<Int> = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { JoinPhotoBoxes.indices(it.channel) }
        }
    }.getOrNull().orEmpty()

    fun keptPhoto(context: Context, uri: Uri, clipIndex: Int): EmbeddedPhoto? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { JoinPhotoBoxes.read(it.channel, clipIndex) }
        }
    }.getOrNull()

    private fun render(context: Context, request: JoinRequest, output: File, progress: (Int) -> Unit, cancelled: () -> Boolean) {
        val canvas = MediaJoinRules.canvas(request.clips.map { it.media }, request.quality)
        val record = MediaJoinRules.encode(JoinRecord(request.photoMs, request.clips,
            request.transitions.takeIf { list -> list.any { it != JoinTransition.NONE } }.orEmpty())).toByteArray(Charsets.UTF_8)
        val bitrate = if (request.quality == JoinQuality.NATIVE) sourceBitrate(context, request.clips) else 0
        val thread = HandlerThread("media-join").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        var transformer: Transformer? = null
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress.coerceIn(0, 99))
                handler.postDelayed(this, 250L)
            }
        }
        handler.post {
            try {
                val encoders = DefaultEncoderFactory.Builder(context).setEnableFallback(true).apply {
                    if (bitrate > 0) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                }.build()
                val bitmaps = DataSourceBitmapLoader.Builder(context)
                    .setMaximumOutputDimension(maxOf(canvas.width, canvas.height)).build()
                transformer = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    // H.264 plays everywhere and its encoders accept the largest sizes.
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, bitmaps))
                    // Only a join remembers its montage; a single cut video is an ordinary video.
                    .setMuxerFactory(InAppMp4Muxer.Factory { entries ->
                        if (request.clips.size > 1) entries.add(MdtaMetadataEntry(MediaJoinRules.METADATA_KEY, record, MdtaMetadataEntry.TYPE_INDICATOR_STRING))
                    })
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            done.countDown()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            failure = exportException
                            done.countDown()
                        }
                    })
                    .build()
                    .also { it.start(composition(context, request), output.absolutePath) }
                handler.post(poll)
            } catch (error: Throwable) {
                failure = error
                done.countDown()
            }
        }
        try {
            while (!done.await(200L, TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    val stopped = CountDownLatch(1)
                    handler.post { transformer?.cancel(); handler.removeCallbacks(poll); stopped.countDown() }
                    stopped.await(5L, TimeUnit.SECONDS)
                    throw CancellationException()
                }
            }
        } finally {
            thread.quitSafely()
        }
        failure?.let { throw IOException(it.message ?: it.javaClass.simpleName, it) }
        progress(100)
    }

    /** The highest bitrate among the videos, so Native never squeezes a clip below its source. */
    private fun sourceBitrate(context: Context, clips: List<JoinClip>): Int = clips.filter { it.media.isVideo }.maxOfOrNull { clip ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.media.uri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { retriever.release() }
    } ?: 0

    /** Copies a finished file into the folder of [source], dated now. */
    internal fun save(context: Context, source: Uri, file: File, name: String, mime: String): Uri {
        val video = mime.startsWith("video/")
        val fallback = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (source.scheme == "file" || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val folder = (if (source.scheme == "file") File(source.path.orEmpty()) else MediaActions.fileFromMediaStore(context, source))
                ?.parentFile ?: File(Environment.getExternalStoragePublicDirectory(fallback), "Galeria Editada")
            folder.mkdirs()
            val target = File(folder, name)
            file.copyTo(target, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            announce(context)
            return Uri.fromFile(target)
        }
        val resolver = context.contentResolver
        val folder = relativePath(context, source)
        val now = System.currentTimeMillis()
        fun values(path: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // A folder the system does not accept for videos falls back to the editor's own folder.
        val uri = folder?.let { runCatching { resolver.insert(collection, values(it)) }.getOrNull() }
            ?: resolver.insert(collection, values("$fallback/Galeria Editada/"))
            ?: throw IOException("Não foi possível criar o arquivo")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Não foi possível gravar o arquivo")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        announce(context)
        return uri
    }

    private fun relativePath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun announce(context: Context) {
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context)
    }

    /** The montage written inside a video joined by the app, or null for any other video. */
    fun readRecord(context: Context, uri: Uri): JoinRecord? = runCatching {
        val groups = MetadataRetriever.Builder(context, PlayerMediaItem.fromUri(uri)).build().use {
            it.retrieveTrackGroups().get(10L, TimeUnit.SECONDS)
        }
        for (group in 0 until groups.length) {
            val format = groups[group].getFormat(0)
            val metadata = format.metadata ?: continue
            for (entry in 0 until metadata.length()) {
                val item = metadata[entry]
                if (item is MdtaMetadataEntry && item.key == MediaJoinRules.METADATA_KEY) {
                    return@runCatching MediaJoinRules.decode(String(item.value, Charsets.UTF_8))
                }
            }
        }
        null
    }.getOrNull()
}
