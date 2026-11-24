package com.galeria.android

import kotlin.math.max

/** A media used in a join that leaves the gallery once [expiresAt] passes. */
internal data class MediaClock(val uri: String, val expiresAt: Long) {
    val key: String get() = MediaIdentityRules.canonicalKey(uri)
}

/** Where a media goes when its clock runs out. */
internal enum class ClockOutcome { TRASH, DELETE }

internal object MediaClockRules {
    const val DURATION_MS = 48L * 60L * 60L * 1000L

    /**
     * The media of a join that get a clock: every video, and the photos only when the user asked.
     * A single media being saved is a plain edit, so it gets none.
     */
    fun targets(clips: List<JoinClip>, clockPhotos: Boolean): List<String> {
        if (clips.size < 2) return emptyList()
        return clips.map { it.media }
            .filter { it.isVideo || clockPhotos }
            .distinctBy { MediaIdentityRules.canonicalKey(it.uri) }
            .map { it.uri }
    }

    /** The clocks after a join: a media used again starts counting from the newest join. */
    fun started(existing: List<MediaClock>, uris: List<String>, now: Long): List<MediaClock> {
        val byKey = LinkedHashMap<String, MediaClock>()
        existing.forEach { byKey[it.key] = it }
        uris.forEach { uri ->
            val clock = MediaClock(uri, now + DURATION_MS)
            val previous = byKey[clock.key]
            byKey[clock.key] = if (previous != null && previous.expiresAt > clock.expiresAt) previous else clock
        }
        return byKey.values.toList()
    }

    fun cancelled(existing: List<MediaClock>, uri: String): List<MediaClock> {
        val key = MediaIdentityRules.canonicalKey(uri)
        return existing.filterNot { it.key == key }
    }

    /**
     * The Trash keeps what the clock removes, even when the Trash is off. It is deleted for good
     * only when the user turned that on and has no Trash.
     */
    fun outcome(trashEnabled: Boolean, deletePermanently: Boolean): ClockOutcome =
        if (deletePermanently && !trashEnabled) ClockOutcome.DELETE else ClockOutcome.TRASH

    fun due(clocks: List<MediaClock>, now: Long): List<MediaClock> = clocks.filter { it.expiresAt <= now }

    /** When the next clock runs out; overdue clocks are left out unless [includeOverdue]. */
    fun nextRun(clocks: List<MediaClock>, now: Long, includeOverdue: Boolean): Long? =
        clocks.map { it.expiresAt }.filter { includeOverdue || it > now }.minOrNull()

    /** The time left, rounded up: whole hours from one hour on, then minutes. */
    fun remaining(expiresAt: Long, now: Long): Remaining {
        val left = max(0L, expiresAt - now)
        val minutes = (left + MINUTE - 1) / MINUTE
        return if (minutes >= 60) Remaining((minutes + 59) / 60, true) else Remaining(max(minutes, if (left > 0) 1 else 0), false)
    }

    data class Remaining(val amount: Long, val hours: Boolean)

    fun encode(clock: MediaClock): String = "${clock.expiresAt}\t${clock.uri}"

    fun decode(line: String): MediaClock? {
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.length - 1) return null
        val at = line.substring(0, tab).toLongOrNull() ?: return null
        return MediaClock(line.substring(tab + 1), at)
    }

    private const val MINUTE = 60_000L
}
