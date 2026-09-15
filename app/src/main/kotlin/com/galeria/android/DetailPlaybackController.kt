package com.galeria.android

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max

internal data class DetailPlaybackTimeline(
    val positionMs: Long,
    val durationMs: Long,
    val progress: Int
)

internal object DetailPlaybackRules {
    private const val FINISHED_TOLERANCE_MS = 750L

    fun rememberedPosition(positionMs: Long, durationMs: Long, ended: Boolean): Long {
        if (ended) return 0L
        val position = max(0L, positionMs)
        return if (durationMs > 0L && position >= durationMs - FINISHED_TOLERANCE_MS) 0L else position
    }

    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val maximum = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return (positionMs + deltaMs).coerceIn(0L, maximum)
    }

    fun timelineProgress(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (max(0L, positionMs).coerceAtMost(durationMs) * 1000L / durationMs).toInt()
    }

    fun speedLabel(speed: Float): String = when {
        kotlin.math.abs(speed - 0.5f) < 0.01f -> "0,5x"
        kotlin.math.abs(speed - 1.5f) < 0.01f -> "1,5x"
        kotlin.math.abs(speed - 2f) < 0.01f -> "2x"
        else -> "1x"
    }
}

internal class DetailPlaybackController(
    context: Context,
    private val prefs: SharedPreferences,
    initialSpeed: Float = 1f,
    initiallyMuted: Boolean = false,
    restoredPositionMs: Long? = null,
    restoredPlayWhenReady: Boolean? = null
) {
    internal interface Listener {
        fun onPlaybackChanged()
        fun onPlaybackEnded()
        fun onFirstFrame()
    }

    private val appContext = context.applicationContext
    private var currentVideoKey: String? = null
    private var positionRestored = false
    private var pendingRestoredPositionMs = restoredPositionMs
    private var pendingRestoredPlayWhenReady = restoredPlayWhenReady
    private var rememberPositionForCurrent = false
    private var playWhenReadyBeforePause: Boolean? = null

    private var currentPlayer: ExoPlayer? = null

    var playbackSpeed: Float = initialSpeed
        private set

    var muted: Boolean = initiallyMuted
        private set

    fun start(
        uri: Uri,
        loop: Boolean,
        defaultPlayWhenReady: Boolean,
        rememberPosition: Boolean,
        listener: Listener
    ): ExoPlayer {
        val player = ExoPlayer.Builder(appContext).build()
        currentPlayer = player
        currentVideoKey = "video_pos_${uri.hashCode()}"
        positionRestored = false
        rememberPositionForCurrent = rememberPosition
        player.setMediaItem(MediaItem.fromUri(uri))
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        player.volume = if (muted) 0f else 1f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== currentPlayer) return
                if (playbackState == Player.STATE_READY && !positionRestored) {
                    restorePosition(player)
                    positionRestored = true
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (rememberPositionForCurrent) saveCurrentPosition() else listener.onPlaybackEnded()
                }
                listener.onPlaybackChanged()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === currentPlayer) listener.onPlaybackChanged()
            }

            override fun onRenderedFirstFrame() {
                if (player === currentPlayer) listener.onFirstFrame()
            }
        })
        player.prepare()
        player.playWhenReady = pendingRestoredPlayWhenReady ?: defaultPlayWhenReady
        pendingRestoredPlayWhenReady = null
        return player
    }

    fun togglePlayback() {
        val player = currentPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val player = currentPlayer ?: return
        player.seekTo(DetailPlaybackRules.seekTarget(player.currentPosition, deltaMs, player.duration))
    }

    fun seekToProgress(progress: Int) {
        val player = currentPlayer ?: return
        if (player.duration > 0L) player.seekTo(player.duration * progress.coerceIn(0, 1000) / 1000L)
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        currentPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleMuted(): Boolean {
        muted = !muted
        currentPlayer?.volume = if (muted) 0f else 1f
        return muted
    }

    fun setLooping(enabled: Boolean) {
        currentPlayer?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun isLooping(): Boolean = currentPlayer?.repeatMode == Player.REPEAT_MODE_ONE

    fun isPlaying(): Boolean = currentPlayer?.isPlaying == true

    fun durationMs(): Long? = currentPlayer?.duration?.takeIf { it > 0L }

    fun timeline(): DetailPlaybackTimeline? {
        val player = currentPlayer ?: return null
        val duration = max(0L, player.duration)
        val position = max(0L, player.currentPosition)
        return DetailPlaybackTimeline(position, duration, DetailPlaybackRules.timelineProgress(position, duration))
    }

    fun rememberedPosition(): Long {
        val player = currentPlayer ?: return 0L
        return DetailPlaybackRules.rememberedPosition(
            player.currentPosition,
            player.duration,
            player.playbackState == Player.STATE_ENDED
        )
    }

    fun saveCurrentPosition() {
        val key = currentVideoKey ?: return
        if (rememberPositionForCurrent && prefs.getBoolean("remember_video_position", true)) {
            prefs.edit { putLong(key, rememberedPosition()) }
        }
    }

    fun detachCurrent(): ExoPlayer? {
        saveCurrentPosition()
        val player = currentPlayer
        currentPlayer = null
        currentVideoKey = null
        rememberPositionForCurrent = false
        return player
    }

    fun releaseCurrent() {
        val player = currentPlayer
        currentPlayer = null
        currentVideoKey = null
        rememberPositionForCurrent = false
        player?.release()
    }

    fun releaseDetached(player: ExoPlayer?) {
        if (player != null && player !== currentPlayer) player.release()
    }

    fun pauseForLifecycle() {
        saveCurrentPosition()
        playWhenReadyBeforePause = currentPlayer?.playWhenReady
        currentPlayer?.pause()
    }

    fun resumeAfterLifecycle() {
        if (playWhenReadyBeforePause == true) currentPlayer?.play()
        playWhenReadyBeforePause = null
    }

    fun savedPlayWhenReady(): Boolean? {
        val player = currentPlayer ?: return null
        return playWhenReadyBeforePause ?: player.playWhenReady
    }

    fun resetSpeed() {
        playbackSpeed = 1f
    }

    private fun restorePosition(player: ExoPlayer) {
        val restored = pendingRestoredPositionMs
        if (restored != null) {
            if (restored > 0L) player.seekTo(restored)
            pendingRestoredPositionMs = null
            return
        }
        if (!rememberPositionForCurrent || !prefs.getBoolean("remember_video_position", true)) return
        val key = currentVideoKey ?: return
        prefs.getLong(key, 0L).takeIf { it > 0L }?.let(player::seekTo)
    }
}
