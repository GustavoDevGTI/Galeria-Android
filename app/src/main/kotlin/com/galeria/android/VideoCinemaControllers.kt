package com.galeria.android

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import kotlin.math.abs

internal enum class CinemaGestureKind { BRIGHTNESS, VOLUME }

internal data class CinemaGestureUpdate(
    val consumed: Boolean,
    val kind: CinemaGestureKind? = null,
    val fraction: Float = 0f
)

internal class VideoCinemaGestureController {
    private enum class State { IDLE, UNDECIDED, ADJUSTING, PASS_THROUGH }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var viewportWidth = 1f
    private var viewportHeight = 1f
    private var initialBrightness = 0f
    private var initialVolume = 0f
    private var kind = CinemaGestureKind.BRIGHTNESS

    fun start(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        brightnessFraction: Float,
        volumeFraction: Float
    ) {
        state = State.UNDECIDED
        downX = x
        downY = y
        viewportWidth = width.coerceAtLeast(1f)
        viewportHeight = height.coerceAtLeast(1f)
        initialBrightness = brightnessFraction.coerceIn(0f, 1f)
        initialVolume = volumeFraction.coerceIn(0f, 1f)
        kind = if (x < viewportWidth / 2f) CinemaGestureKind.BRIGHTNESS else CinemaGestureKind.VOLUME
    }

    fun update(x: Float, y: Float, touchSlop: Float): CinemaGestureUpdate {
        if (state == State.IDLE || state == State.PASS_THROUGH) return CinemaGestureUpdate(false)
        val deltaX = x - downX
        val deltaY = y - downY
        if (state == State.UNDECIDED) {
            if (abs(deltaX) < touchSlop && abs(deltaY) < touchSlop) {
                return CinemaGestureUpdate(true)
            }
            if (abs(deltaX) >= abs(deltaY)) {
                state = State.PASS_THROUGH
                return CinemaGestureUpdate(false)
            }
            state = State.ADJUSTING
        }
        val initial = if (kind == CinemaGestureKind.BRIGHTNESS) initialBrightness else initialVolume
        val fraction = (initial - deltaY / viewportHeight).coerceIn(0f, 1f)
        return CinemaGestureUpdate(true, kind, fraction)
    }

    fun finish(): Boolean {
        val consumed = state == State.ADJUSTING
        state = State.IDLE
        return consumed
    }

    fun cancel() {
        state = State.IDLE
    }
}

internal class VideoTrackChoice internal constructor(
    val label: String,
    val preferenceToken: String,
    val selected: Boolean,
    internal val group: Tracks.Group,
    internal val trackIndex: Int
)

@OptIn(UnstableApi::class)
internal class VideoTrackController(
    private val preferences: CinemaModePreferences,
    private val displayLocale: Locale = Locale.getDefault()
) {
    private var player: ExoPlayer? = null
    private var albumKey: String? = null
    private var preferencesApplied = false
    private var listener: Player.Listener? = null
    private var sessionAudioPreference: String? = null
    private var sessionSubtitlePreference: String? = null
    private var sessionSubtitlesEnabled = false

    fun bind(nextPlayer: ExoPlayer, albumKey: String?) {
        unbind()
        player = nextPlayer
        this.albumKey = albumKey
        sessionAudioPreference = preferences.audioPreference(albumKey)
        sessionSubtitlePreference = preferences.subtitlePreference(albumKey)
        sessionSubtitlesEnabled = preferences.subtitlesEnabled(albumKey)
        preferencesApplied = false
        listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                applySavedPreferencesOnce()
            }
        }.also(nextPlayer::addListener)
        if (nextPlayer.currentTracks.groups.isNotEmpty()) applySavedPreferencesOnce()
    }

    fun unbind() {
        val current = player
        val currentListener = listener
        if (current != null && currentListener != null) current.removeListener(currentListener)
        player = null
        listener = null
        albumKey = null
        preferencesApplied = false
    }

    fun audioChoices(): List<VideoTrackChoice> = choices(C.TRACK_TYPE_AUDIO)

    fun subtitleChoices(): List<VideoTrackChoice> = choices(C.TRACK_TYPE_TEXT)

    fun audioPreference(): String? = sessionAudioPreference

    fun subtitlePreference(): String? = sessionSubtitlePreference

    fun subtitlesEnabled(): Boolean = sessionSubtitlesEnabled

    fun selectAutomaticAudio() {
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        preferences.setAudioPreference(albumKey, null)
        sessionAudioPreference = null
    }

    fun selectAudio(choice: VideoTrackChoice) {
        select(choice, C.TRACK_TYPE_AUDIO)
        preferences.setAudioPreference(albumKey, choice.preferenceToken)
        sessionAudioPreference = choice.preferenceToken
    }

    fun disableSubtitles() {
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        preferences.setSubtitlesEnabled(albumKey, false)
        sessionSubtitlesEnabled = false
    }

    fun selectAutomaticSubtitles() {
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        preferences.setSubtitlesEnabled(albumKey, true)
        preferences.setSubtitlePreference(albumKey, null)
        sessionSubtitlesEnabled = true
        sessionSubtitlePreference = null
    }

    fun selectSubtitle(choice: VideoTrackChoice) {
        select(choice, C.TRACK_TYPE_TEXT)
        preferences.setSubtitlesEnabled(albumKey, true)
        preferences.setSubtitlePreference(albumKey, choice.preferenceToken)
        sessionSubtitlesEnabled = true
        sessionSubtitlePreference = choice.preferenceToken
    }

    private fun select(choice: VideoTrackChoice, trackType: Int) {
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(trackType)
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndex))
            .build()
    }

    private fun applySavedPreferencesOnce() {
        if (preferencesApplied) return
        preferencesApplied = true
        val current = player ?: return
        sessionAudioPreference?.let { token ->
            audioChoices().firstOrNull { it.preferenceToken == token }?.let(::selectAudio)
        }
        if (!sessionSubtitlesEnabled) {
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        sessionSubtitlePreference?.let { token ->
            subtitleChoices().firstOrNull { it.preferenceToken == token }?.let(::selectSubtitle)
        }
    }

    private fun choices(trackType: Int): List<VideoTrackChoice> {
        val tracks = player?.currentTracks ?: return emptyList()
        val usedLabels = HashMap<String, Int>()
        return buildList {
            tracks.groups.filter { it.type == trackType }.forEach { group ->
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    val baseLabel = trackLabel(format, trackType)
                    val occurrence = (usedLabels[baseLabel] ?: 0) + 1
                    usedLabels[baseLabel] = occurrence
                    add(
                        VideoTrackChoice(
                            label = if (occurrence == 1) baseLabel else "$baseLabel $occurrence",
                            preferenceToken = preferenceToken(format),
                            selected = group.isTrackSelected(index),
                            group = group,
                            trackIndex = index
                        )
                    )
                }
            }
        }
    }

    private fun trackLabel(format: Format, trackType: Int): String {
        val explicit = format.label?.trim()?.takeIf(String::isNotEmpty)
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(displayLocale) }
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString() }
        val details = if (trackType == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
            when (format.channelCount) {
                1 -> "Mono"
                2 -> "Estéreo"
                else -> "${format.channelCount} canais"
            }
        } else null
        return listOfNotNull(explicit, language, details).distinct().joinToString(" · ")
            .ifBlank { if (trackType == C.TRACK_TYPE_AUDIO) "Áudio" else "Legenda" }
    }

    private fun preferenceToken(format: Format): String {
        val language = format.language?.trim()?.lowercase(Locale.ROOT)
        if (!language.isNullOrBlank() && language != "und") return "language:$language"
        val label = format.label?.trim()?.lowercase(Locale.ROOT)
        return if (label.isNullOrBlank()) "id:${format.id.orEmpty()}" else "label:$label"
    }
}
