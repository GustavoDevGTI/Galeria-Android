package com.galeria.android

import kotlin.math.roundToInt

/** Where the original of one media of a joined video is now. */
internal enum class OriginState { PRESENT, TRASHED, GONE }

/** What separating does for one media of a joined video. */
internal sealed interface SplitStep {
    val clipIndex: Int

    /** The original is still in the gallery: nothing is written. */
    data class Keep(override val clipIndex: Int, val uri: String) : SplitStep

    /** The original comes back from the Trash. */
    data class Restore(override val clipIndex: Int, val uri: String) : SplitStep

    /** A video cut in the montage comes back as a new video, cut again from its original. */
    data class CutOriginal(override val clipIndex: Int, val uri: String, val ranges: List<LongRange>) : SplitStep

    /** A video with no usable original is taken back from the joined video. */
    data class CutJoined(override val clipIndex: Int, val startMs: Long, val endMs: Long, val newVideo: Boolean) : SplitStep

    /** A deleted photo comes back from the copy kept inside the joined video. */
    data class PhotoEmbedded(override val clipIndex: Int) : SplitStep

    /** A deleted photo with no copy inside comes back as a frame of the joined video. */
    data class PhotoFrame(override val clipIndex: Int, val atMs: Long) : SplitStep
}

internal object MediaSplitRules {
    /** Only videos joined by the app, from two media or more, can be separated. */
    fun separable(record: JoinRecord?): Boolean = record != null && record.clips.size >= 2

    /**
     * One step per media of the montage, without duplicates: an original still around is used as it
     * is, one in the Trash is restored, and only what no longer exists is written again. A video cut
     * in the montage always comes back as a new video.
     */
    fun plan(record: JoinRecord, states: List<OriginState>, embeddedPhotos: Set<Int>): List<SplitStep> {
        require(states.size == record.clips.size)
        val starts = MediaJoinRules.clipStarts(record.clips, record.photoMs, record.transitions)
        val seen = HashSet<String>()
        val steps = ArrayList<SplitStep>()
        record.clips.forEachIndexed { index, clip ->
            val media = clip.media
            val state = states[index]
            val start = starts[index]
            val end = start + MediaJoinRules.clipDurationMs(clip, record.photoMs)
            if (clip.trimmed) {
                steps += if (state == OriginState.PRESENT) SplitStep.CutOriginal(index, media.uri, clip.ranges)
                    else SplitStep.CutJoined(index, start, end, newVideo = true)
                return@forEachIndexed
            }
            // The same original used twice in the montage comes back once.
            if (!seen.add(MediaIdentityRules.canonicalKey(media.uri))) return@forEachIndexed
            steps += when (state) {
                OriginState.PRESENT -> SplitStep.Keep(index, media.uri)
                OriginState.TRASHED -> SplitStep.Restore(index, media.uri)
                OriginState.GONE -> when {
                    media.isVideo -> SplitStep.CutJoined(index, start, end, newVideo = false)
                    index in embeddedPhotos -> SplitStep.PhotoEmbedded(index)
                    else -> SplitStep.PhotoFrame(index, (start + end) / 2)
                }
            }
        }
        return steps
    }

    data class Area(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Where a [mediaWidth] x [mediaHeight] media sits, fitted and centered, in a [frameWidth] x [frameHeight] frame. */
    fun fitted(frameWidth: Int, frameHeight: Int, mediaWidth: Int, mediaHeight: Int): Area {
        if (mediaWidth <= 0 || mediaHeight <= 0) return Area(0, 0, frameWidth, frameHeight)
        val scale = minOf(frameWidth.toDouble() / mediaWidth, frameHeight.toDouble() / mediaHeight)
        val width = (mediaWidth * scale).roundToInt().coerceIn(1, frameWidth)
        val height = (mediaHeight * scale).roundToInt().coerceIn(1, frameHeight)
        return Area((frameWidth - width) / 2, (frameHeight - height) / 2, width, height)
    }

    /** The name of a photo written again from a frame: the original name, as a JPEG. */
    fun frameName(original: String): String {
        val base = original.substringBeforeLast('.', original).ifBlank { "foto" }
        return "$base.jpg"
    }
}
