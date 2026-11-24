package com.galeria.android

internal data class PixelSize(val width: Int, val height: Int)

/** The photo editor's resize choices: the same usual sizes as the video editor, only smaller ones. */
internal object ImageResizeRules {
    /** Short sides below the photo's own, largest first; a choice never makes it bigger. */
    fun options(width: Int, height: Int): List<Int> = JoinQuality.STEPS.filter { it < minOf(width, height) }

    /** The photo with [shortSide] as its short side, keeping its shape. */
    fun scaled(width: Int, height: Int, shortSide: Int): PixelSize {
        val ratio = shortSide.toDouble() / minOf(width, height)
        return PixelSize(maxOf(1, Math.round(width * ratio).toInt()), maxOf(1, Math.round(height * ratio).toInt()))
    }

    /**
     * About how big [target] gets, from the bytes per pixel of the original file; null when the
     * file's size or dimensions are unknown. Saving encodes again, so this is a guide.
     */
    fun estimatedBytes(fileBytes: Long, fileWidth: Int, fileHeight: Int, target: PixelSize): Long? {
        if (fileBytes <= 0L || fileWidth <= 0 || fileHeight <= 0) return null
        return Math.round(fileBytes.toDouble() * target.width * target.height / (fileWidth.toDouble() * fileHeight))
    }

    /**
     * The sizes to scale through: halves while the photo is at least twice the target, then the
     * target. Each step blends neighbouring pixels, so a big reduction stays smooth instead of
     * skipping pixels and looking jagged.
     */
    fun steps(from: PixelSize, to: PixelSize): List<PixelSize> {
        val result = ArrayList<PixelSize>()
        var width = from.width
        var height = from.height
        while (width / 2 >= to.width && height / 2 >= to.height && width / 2 > 0 && height / 2 > 0) {
            width /= 2; height /= 2
            result += PixelSize(width, height)
        }
        if (result.lastOrNull() != to) result += to
        return result
    }
}
