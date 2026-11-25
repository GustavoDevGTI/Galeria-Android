package com.galeria.android

import java.net.URLDecoder
import java.net.URLEncoder

/** One photo or video chosen for a montage, with its upright size. */
internal data class JoinMedia(
    val uri: String,
    val mime: String,
    val name: String,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L,
    val size: Long = 0L
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * A media in the montage. A video keeps the parts in [ranges] (source milliseconds, in order);
 * cutting the middle leaves two ranges of the same media, which always stay together. A photo
 * shows for its own [photoMs], or for the montage's photo time when it has none.
 */
internal data class JoinClip(
    val media: JoinMedia,
    val ranges: List<LongRange> = defaultRanges(media),
    val photoMs: Long? = null
) {
    companion object {
        fun defaultRanges(media: JoinMedia): List<LongRange> =
            if (media.isVideo && media.durationMs > 0L) listOf(0L..media.durationMs) else emptyList()
    }

    /** True when the video lost any part, so separating it later gives a new video. */
    val trimmed: Boolean get() = media.isVideo && ranges != defaultRanges(media)
}

/** How one media hands over to the next. */
internal enum class JoinTransition { NONE, DISSOLVE, FADE, SLIDE, ZOOM }

/**
 * What the editor hands over to be written: the montage, its photo time, quality and the
 * transition at each junction ([transitions] index i sits between clip i and clip i + 1).
 */
internal data class JoinRequest(
    val clips: List<JoinClip>,
    val photoMs: Long,
    val quality: JoinQuality,
    val transitions: List<JoinTransition> = emptyList(),
    /** Keeps the original photos inside the video, so separating gives them back intact. */
    val keepPhotos: Boolean = false
)

/** A junction being crossed: both media show together from [startMs] to [endMs]. */
internal data class JoinWindow(val junction: Int, val type: JoinTransition, val startMs: Long, val endMs: Long)

/** How one layer looks at a moment: opacity, horizontal shift (in frame widths) and size. */
internal data class JoinLook(val alpha: Float, val shift: Float = 0f, val scale: Float = 1f)

/**
 * The output size: [maxShortSide] caps the short side of the video, null keeps the native one.
 * Only sizes below the native one are offered ([MediaJoinRules.qualityOptions]).
 */
internal data class JoinQuality(val maxShortSide: Int?) {
    companion object {
        val NATIVE = JoinQuality(null)
        /** Usual short sides, largest first. */
        val STEPS = listOf(2160, 1440, 1080, 720, 540, 480, 360)
    }
}

internal data class JoinCanvas(val width: Int, val height: Int)

/** Where one kept part lands in the joined video. */
internal data class JoinSegment(
    val clipIndex: Int,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val startMs: Long,
    val endMs: Long
)

/** What the app writes inside a joined video, so it can be separated later. */
internal data class JoinRecord(val photoMs: Long, val clips: List<JoinClip>, val transitions: List<JoinTransition> = emptyList())

internal object MediaJoinRules {
    const val MIN_PHOTO_SECONDS = 1
    const val MAX_PHOTO_SECONDS = 10
    const val DEFAULT_PHOTO_SECONDS = 3
    /** Largest short side for a montage made only of photos; video encoders stop around 4K. */
    const val MAX_PHOTO_ONLY_SHORT_SIDE = 2160
    /** Shortest part a cut may leave, so the timeline never holds an invisible sliver. */
    const val MIN_PART_MS = 500L
    const val METADATA_KEY = "com.galeria.android.join"
    private const val HEADER = "galeria-join 1"
    private const val TRANSITIONS = "transitions="

    fun photoSeconds(value: Int): Int = value.coerceIn(MIN_PHOTO_SECONDS, MAX_PHOTO_SECONDS)

    fun clipDurationMs(clip: JoinClip, photoMs: Long): Long =
        if (clip.media.isVideo) clip.ranges.sumOf { it.last - it.first } else clip.photoMs ?: photoMs

    /** Whole seconds a photo shows, for its own time control. */
    fun photoSecondsOf(clip: JoinClip, photoMs: Long): Int = (clipDurationMs(clip, photoMs) / 1000L).toInt()

    /** The photo at [index] with its time moved by [delta] seconds, kept within the limits. */
    fun withPhotoSeconds(clips: List<JoinClip>, index: Int, delta: Int, photoMs: Long): List<JoinClip> {
        val clip = clips.getOrNull(index)?.takeUnless { it.media.isVideo } ?: return clips
        val current = photoSecondsOf(clip, photoMs)
        val seconds = photoSeconds(current + delta)
        if (seconds == current) return clips
        return clips.toMutableList().apply { set(index, clip.copy(photoMs = seconds * 1000L)) }
    }

    /** Longest overlap of a transition; shorter media get a shorter one, at most half of each. */
    const val TRANSITION_MS = 600L

    fun transitionAt(transitions: List<JoinTransition>, junction: Int): JoinTransition = transitions.getOrElse(junction) { JoinTransition.NONE }

    /** How long clip [junction] and the next one play together. */
    fun overlapMs(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition>, junction: Int): Long {
        if (junction !in 0 until clips.lastIndex || transitionAt(transitions, junction) == JoinTransition.NONE) return 0L
        return minOf(TRANSITION_MS, clipDurationMs(clips[junction], photoMs) / 2, clipDurationMs(clips[junction + 1], photoMs) / 2)
    }

    /** Where each clip starts in the joined video; a transition pulls the next clip in by its overlap. */
    fun clipStarts(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition> = emptyList()): List<Long> {
        var at = 0L
        return clips.mapIndexed { index, clip ->
            val start = at
            at += clipDurationMs(clip, photoMs) - overlapMs(clips, photoMs, transitions, index)
            start
        }
    }

    fun totalMs(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition> = emptyList()): Long {
        if (clips.isEmpty()) return 0L
        return clipStarts(clips, photoMs, transitions).last() + clipDurationMs(clips.last(), photoMs)
    }

    /** The junctions with a transition, as the stretches where both media show. */
    fun windows(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition>): List<JoinWindow> {
        val starts = clipStarts(clips, photoMs, transitions)
        return (0 until maxOf(0, clips.lastIndex)).mapNotNull { junction ->
            val overlap = overlapMs(clips, photoMs, transitions, junction)
            if (overlap <= 0L) null
            else JoinWindow(junction, transitionAt(transitions, junction), starts[junction + 1], starts[junction + 1] + overlap)
        }
    }

    /**
     * For the editor's preview, which plays one layer: each transition becomes a cut in its middle,
     * so clip i loses half of the overlap at its start and half at its end. The preview then keeps
     * the same length as the joined video. Returns (cut at start, cut at end) per clip.
     */
    fun previewTrims(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition>): List<Pair<Long, Long>> =
        clips.indices.map { index ->
            overlapMs(clips, photoMs, transitions, index - 1) / 2 to
                (overlapMs(clips, photoMs, transitions, index) - overlapMs(clips, photoMs, transitions, index) / 2)
        }

    /** Clips alternate between two layers, so the two sides of any junction can overlap. */
    fun layerOf(clipIndex: Int): Int = clipIndex % 2

    /**
     * How [layer] looks at [timeMs]. Outside a transition only the clip playing is visible; inside
     * one, the outgoing and incoming clips blend, slide or zoom. Layer [TOP_LAYER] is drawn on top,
     * so a dissolve fades whichever side is on top and keeps the one below opaque.
     */
    fun look(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition>, layer: Int, timeMs: Long): JoinLook {
        val window = windows(clips, photoMs, transitions).firstOrNull { timeMs in it.startMs until it.endMs }
        if (window == null) {
            val starts = clipStarts(clips, photoMs, transitions)
            val playing = starts.indexOfLast { it <= timeMs }.coerceAtLeast(0)
            return JoinLook(if (layerOf(playing) == layer) 1f else 0f)
        }
        val p = ((timeMs - window.startMs).toFloat() / (window.endMs - window.startMs)).coerceIn(0f, 1f)
        val incoming = layerOf(window.junction + 1) == layer
        val onTop = layer == TOP_LAYER
        return when (window.type) {
            JoinTransition.NONE -> JoinLook(if (incoming) 1f else 0f)
            JoinTransition.DISSOLVE -> JoinLook(blend(incoming, onTop, p))
            JoinTransition.FADE -> JoinLook(if (incoming) (2f * p - 1f).coerceAtLeast(0f) else (1f - 2f * p).coerceAtLeast(0f))
            JoinTransition.SLIDE -> JoinLook(1f, if (incoming) 1f - p else -p)
            JoinTransition.ZOOM -> JoinLook(blend(incoming, onTop, p), scale = if (incoming) 0.85f + 0.15f * p else 1f + 0.15f * p)
        }
    }

    /** Cross-fade: only the layer on top changes opacity, the one below stays solid. */
    private fun blend(incoming: Boolean, onTop: Boolean, p: Float): Float = when {
        !onTop -> 1f
        incoming -> p
        else -> 1f - p
    }

    /** The compositor draws its first input on top. */
    const val TOP_LAYER = 0

    fun segments(clips: List<JoinClip>, photoMs: Long, transitions: List<JoinTransition> = emptyList()): List<JoinSegment> {
        val result = ArrayList<JoinSegment>()
        val starts = clipStarts(clips, photoMs, transitions)
        clips.forEachIndexed { index, clip ->
            var at = starts[index]
            if (clip.media.isVideo) {
                clip.ranges.forEach { range ->
                    val length = range.last - range.first
                    result += JoinSegment(index, range.first, range.last, at, at + length)
                    at += length
                }
            } else {
                val shown = clipDurationMs(clip, photoMs)
                result += JoinSegment(index, 0L, shown, at, at + shown)
                at += shown
            }
        }
        return result
    }

    /** Moves the clip at [from] to [to]; the other clips keep their order. */
    fun moved(clips: List<JoinClip>, from: Int, to: Int): List<JoinClip> {
        if (from !in clips.indices) return clips
        val target = to.coerceIn(0, clips.lastIndex)
        if (target == from) return clips
        return clips.toMutableList().apply { add(target, removeAt(from)) }
    }

    /** Splits the kept part of a video at [sourceMs]; both sides must keep [MIN_PART_MS]. */
    fun split(clip: JoinClip, sourceMs: Long): JoinClip {
        val index = clip.ranges.indexOfFirst { sourceMs - it.first >= MIN_PART_MS && it.last - sourceMs >= MIN_PART_MS }
        if (index < 0) return clip
        val range = clip.ranges[index]
        return clip.copy(ranges = clip.ranges.toMutableList().apply {
            set(index, range.first..sourceMs)
            add(index + 1, sourceMs..range.last)
        })
    }

    /**
     * Moves one edge of a kept part. A part never shrinks below [MIN_PART_MS], never crosses
     * its neighbour and never leaves the source video.
     */
    fun trimmed(clip: JoinClip, rangeIndex: Int, start: Boolean, sourceMs: Long): JoinClip {
        val range = clip.ranges.getOrNull(rangeIndex) ?: return clip
        val updated = if (start) {
            val floor = clip.ranges.getOrNull(rangeIndex - 1)?.last ?: 0L
            sourceMs.coerceIn(floor, range.last - MIN_PART_MS)..range.last
        } else {
            val ceiling = clip.ranges.getOrNull(rangeIndex + 1)?.first ?: clip.media.durationMs
            range.first..sourceMs.coerceIn(range.first + MIN_PART_MS, ceiling)
        }
        return clip.copy(ranges = clip.ranges.toMutableList().apply { set(rangeIndex, updated) })
    }

    /**
     * Upright output size. Only landscape media give a landscape video; any portrait or square
     * one makes it portrait, and the rest fit inside with black borders. Native keeps the
     * largest video's short side; photos alone stop at 4K. A smaller quality only lowers it.
     */
    fun canvas(medias: List<JoinMedia>, quality: JoinQuality): JoinCanvas {
        val usable = medias.filter { it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return JoinCanvas(1080, 1920)
        val landscape = usable.all { it.width > it.height }
        fun fits(media: JoinMedia) = if (landscape) media.width > media.height else media.height >= media.width
        val reference = usable.firstOrNull { it.isVideo && fits(it) } ?: usable.first { fits(it) }
        val videos = usable.filter { it.isVideo }
        var shortSide = if (videos.isNotEmpty()) videos.maxOf { minOf(it.width, it.height) }
        else minOf(usable.maxOf { minOf(it.width, it.height) }, MAX_PHOTO_ONLY_SHORT_SIDE)
        quality.maxShortSide?.let { shortSide = minOf(shortSide, it) }
        val aspect = maxOf(reference.width, reference.height).toDouble() / minOf(reference.width, reference.height)
        val short = even(shortSide)
        val long = even(Math.round(short * aspect).toInt())
        return if (landscape) JoinCanvas(long, short) else JoinCanvas(short, long)
    }

    private fun even(value: Int): Int = maxOf(2, value - value % 2)

    /** Native, then every usual size smaller than it: a choice never makes the video bigger. */
    fun qualityOptions(medias: List<JoinMedia>): List<JoinQuality> {
        val native = canvas(medias, JoinQuality.NATIVE)
        val short = minOf(native.width, native.height)
        return listOf(JoinQuality.NATIVE) + JoinQuality.STEPS.filter { it < short }.map { JoinQuality(it) }
    }

    /** [quality] when it still lowers the size, otherwise Native (it would give the same video). */
    fun effectiveQuality(medias: List<JoinMedia>, quality: JoinQuality): JoinQuality =
        if (quality in qualityOptions(medias)) quality else JoinQuality.NATIVE

    /** Lowest bitrate asked for a smaller video, so a tiny size still plays cleanly. */
    const val MIN_BITRATE = 250_000

    /**
     * Bits per second asked of the encoder; 0 lets it choose (no video to go by). Native keeps
     * the source's. A smaller size gets the source's scaled by the pixel ratio to the power 0.75:
     * smaller frames need a little more per pixel to look as clean, and the file still shrinks
     * (1080p to 720p keeps about 54%, 4K to 1080p about 35%).
     */
    fun videoBitrate(sourceBitrate: Int, native: JoinCanvas, output: JoinCanvas): Int {
        if (sourceBitrate <= 0) return 0
        val ratio = output.width.toDouble() * output.height / (native.width.toDouble() * native.height)
        if (ratio >= 1.0) return sourceBitrate
        return minOf(sourceBitrate, maxOf(MIN_BITRATE, Math.round(sourceBitrate * Math.pow(ratio, 0.75)).toInt()))
    }

    /**
     * Space kept after the file header for the video's index, so it can sit at the start. The
     * muxer would keep 400 KB in every file, which outweighs a short or small video; this keeps
     * about what the index needs (60 frames and 50 sound packets a second, 16 bytes each, plus
     * the montage record of [recordBytes]). A longer index simply goes to the end of the file.
     */
    fun indexReserveBytes(totalMs: Long, recordBytes: Int = 0): Int =
        (16_000L + recordBytes.coerceAtLeast(0) + totalMs.coerceAtLeast(0L) * 2_000L / 1_000L).coerceAtMost(400_000L).toInt()

    /** A video's average bitrate from its file size and length, sound included; 0 if unknown. */
    fun fileBitrate(media: JoinMedia): Int =
        if (!media.isVideo || media.size <= 0L || media.durationMs <= 0L) 0
        else (media.size * 8_000L / media.durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * About how big the saved video will be, from the videos' own bitrates; null with no video to
     * go by. Encoders do not hit the bitrate exactly, so this is a guide, not a promise.
     */
    fun estimatedBytes(request: JoinRequest): Long? {
        val medias = request.clips.map { it.media }
        val source = medias.maxOfOrNull { fileBitrate(it) } ?: 0
        if (source <= 0) return null
        val bitrate = videoBitrate(source, canvas(medias, JoinQuality.NATIVE), canvas(medias, request.quality))
        return bitrate.toLong() * totalMs(request.clips, request.photoMs, request.transitions) / 8_000L
    }

    /**
     * One MP4 video with only cuts, at native quality, is copied sample by sample: nothing is
     * encoded again and every audio and subtitle track stays. Anything else is encoded.
     */
    fun copiesWithoutEncoding(request: JoinRequest): Boolean {
        val media = request.clips.singleOrNull()?.media ?: return false
        return media.isVideo && request.quality == JoinQuality.NATIVE && VideoTrimRules.copyable(media.name, media.mime)
    }

    /** A merge is named after its first media, with " Merged" at the end ("Gustavo.mp4" gives "Gustavo Merged.mp4"). */
    fun outputName(firstName: String): String {
        val leaf = firstName.substringAfterLast('/').substringAfterLast('\\')
        val base = leaf.substringBeforeLast('.', leaf).replace(Regex("[^\\p{L}\\p{N} _()-]"), "_").trim()
        return "${base.ifBlank { "video" }} Merged.mp4"
    }

    fun encode(record: JoinRecord): String = buildString {
        append(HEADER).append('\n')
        append("photoMs=").append(record.photoMs).append('\n')
        if (record.transitions.any { it != JoinTransition.NONE }) {
            append(TRANSITIONS).append(record.transitions.joinToString(",") { it.name }).append('\n')
        }
        record.clips.forEach { clip ->
            val media = clip.media
            append(listOf(
                media.uri, media.mime, media.name, media.width, media.height, media.durationMs, media.size,
                clip.ranges.joinToString(",") { "${it.first}-${it.last}" },
                // A photo's own time; older records have no such field and use photoMs.
                clip.photoMs ?: ""
            ).joinToString("\t") { URLEncoder.encode(it.toString(), "UTF-8") }).append('\n')
        }
    }

    fun decode(text: String): JoinRecord? = runCatching {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != HEADER) return null
        val photoMs = lines[1].removePrefix("photoMs=").toLong()
        val transitions = lines.firstOrNull { it.startsWith(TRANSITIONS) }?.removePrefix(TRANSITIONS)
            ?.split(',')?.map { JoinTransition.valueOf(it) }.orEmpty()
        val clips = lines.drop(2).filterNot { it.startsWith(TRANSITIONS) }.map { line ->
            val f = line.split('\t').map { URLDecoder.decode(it, "UTF-8") }
            val media = JoinMedia(f[0], f[1], f[2], f[3].toInt(), f[4].toInt(), f[5].toLong(), f[6].toLong())
            val ranges = if (f[7].isEmpty()) emptyList() else f[7].split(',').map {
                val (s, e) = it.split('-')
                s.toLong()..e.toLong()
            }
            JoinClip(media, ranges, f.getOrNull(8)?.toLongOrNull())
        }
        JoinRecord(photoMs, clips, transitions)
    }.getOrNull()
}
