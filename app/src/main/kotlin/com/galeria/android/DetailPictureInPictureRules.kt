package com.galeria.android

/**
 * When the viewer turns into a floating thumbnail (picture-in-picture) and with which shape.
 * Only a video that is playing — in the normal viewer or in cinema mode — goes to the thumbnail;
 * photos and paused videos keep the usual behavior of closing on back.
 */
internal object DetailPictureInPictureRules {
    // Android accepts aspect ratios between 1:2.39 and 2.39:1 for the floating window.
    private const val MAX_RATIO = 2.39f
    private const val MIN_RATIO = 1f / MAX_RATIO

    fun shouldEnter(
        supported: Boolean,
        isVideo: Boolean,
        playing: Boolean,
        alreadyInPictureInPicture: Boolean,
        finishing: Boolean
    ): Boolean = supported && isVideo && playing && !alreadyInPictureInPicture && !finishing

    /**
     * Size of the video as it is shown: a phone video recorded upright is stored sideways with a
     * rotation tag, and some videos use non-square pixels. Both are applied before choosing the shape.
     */
    fun orientedSize(width: Int, height: Int, pixelWidthHeightRatio: Float, rotationDegrees: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val shownWidth = (width * (if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f)).toInt().coerceAtLeast(1)
        val quarterTurn = ((rotationDegrees % 360) + 360) % 180 == 90
        return if (quarterTurn) height to shownWidth else shownWidth to height
    }

    /** Width and height for the thumbnail, following the video and kept within Android's limits. */
    fun aspectRatio(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return 16 to 9
        val ratio = videoWidth.toFloat() / videoHeight
        return when {
            ratio > MAX_RATIO -> 239 to 100
            ratio < MIN_RATIO -> 100 to 239
            else -> videoWidth to videoHeight
        }
    }
}
