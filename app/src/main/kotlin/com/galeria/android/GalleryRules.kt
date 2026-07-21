package com.galeria.android

import java.util.Collections
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

data class MediaFilterOptions(
    val showImages: Boolean = true,
    val showVideos: Boolean = true,
    val showGifs: Boolean = true,
    val showRaw: Boolean = true,
    val showSvgs: Boolean = true,
    val showPortraits: Boolean = false
)

object MediaFilterRules {
    private val rawExtensions = setOf("dng", "raw", "cr2", "nef", "arw", "orf", "rw2")

    fun matches(name: String, mimeType: String, options: MediaFilterOptions): Boolean {
        val normalizedName = name.lowercase(Locale.US)
        val normalizedMime = mimeType.lowercase(Locale.US)
        if (isVideo(normalizedMime)) return options.showVideos
        if (isGif(normalizedName, normalizedMime)) return options.showGifs
        if (isSvg(normalizedName, normalizedMime)) return options.showSvgs
        if (isRaw(normalizedName, normalizedMime)) return options.showRaw
        if (isImage(normalizedMime)) return options.showImages || options.showPortraits
        return false
    }

    fun isGif(name: String, mimeType: String): Boolean =
        mimeType.equals("image/gif", ignoreCase = true) || extension(name) == "gif"

    fun isSvg(name: String, mimeType: String): Boolean =
        mimeType.equals("image/svg+xml", ignoreCase = true) || extension(name) == "svg"

    fun isRaw(name: String, mimeType: String): Boolean =
        mimeType.contains("raw", ignoreCase = true) || rawExtensions.contains(extension(name))

    private fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    private fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)
}

object AlbumRules {
    const val SORT_NAME = "name"
    const val SORT_PATH = "path"
    const val SORT_SIZE = "size"
    const val SORT_MODIFIED = "modified"
    const val SORT_CREATED = "created"
    const val SORT_RANDOM = "random"

    fun sort(albums: MutableList<AlbumItem>, mode: String, descending: Boolean, randomSeed: Long = 42L) {
        if (mode == SORT_RANDOM) {
            Collections.shuffle(albums, Random(randomSeed))
            if (descending) albums.reverse()
            return
        }
        albums.sortWith { first, second ->
            val result = when (mode) {
                SORT_NAME -> first.name.compareTo(second.name, ignoreCase = true)
                SORT_PATH -> first.path.compareTo(second.path, ignoreCase = true)
                SORT_SIZE -> first.totalSize.compareTo(second.totalSize)
                SORT_CREATED -> first.firstDate.compareTo(second.firstDate)
                else -> first.latestDate.compareTo(second.latestDate)
            }
            if (descending) -result else result
        }
    }

    fun isHidden(path: String, fallbackKey: String): Boolean {
        val normalized = path.ifEmpty { fallbackKey }.replace('\\', '/')
        if (normalized.isEmpty()) return false
        return normalized.split('/').any { part ->
            part.startsWith('.') || part.equals("Private", true) || part.equals("Hidden", true)
        }
    }
}

object ViewerStateRules {
    fun shuffledFromCurrent(availableUris: List<String>, currentUri: String, seed: Long): List<String> {
        if (availableUris.isEmpty()) return emptyList()
        val shuffled = availableUris.distinct().sorted().toMutableList()
        Collections.shuffle(shuffled, Random(seed))
        val currentIndex = shuffled.indexOf(currentUri)
        if (currentIndex <= 0) return shuffled
        return shuffled.drop(currentIndex) + shuffled.take(currentIndex)
    }
}

object ViewerMenuRules {
    const val OPEN_WITH = "Abrir com"
    const val COPY_TO = "Copiar para"
    const val MOVE_TO = "Mover para"
    const val HIDE = "Ocultar"
    const val INFORMATION = "Informações"
    const val ENABLE_LOOP = "Reproduzir em loop"
    const val DISABLE_LOOP = "Desativar repetição"
    const val SET_AS = "Definir como"
    const val ROTATE = "Alterar orientação"
    const val PRINT = "Imprimir"
    const val RESIZE = "Redimensionar"

    fun options(isVideo: Boolean, loopEnabled: Boolean, shuffleMode: Boolean): List<String> {
        if (!isVideo) {
            return listOf(HIDE, COPY_TO, MOVE_TO, SET_AS, ROTATE, PRINT, RESIZE)
        }
        return buildList {
            add(OPEN_WITH)
            add(COPY_TO)
            add(MOVE_TO)
            add(HIDE)
            add(INFORMATION)
            if (!shuffleMode) add(if (loopEnabled) DISABLE_LOOP else ENABLE_LOOP)
        }
    }
}

enum class SwipeAxis { HORIZONTAL, VERTICAL }

data class SwipeIntent(val axis: SwipeAxis, val direction: Int)

object SwipeGestureRules {
    fun intent(deltaX: Float, deltaY: Float, touchSlop: Float): SwipeIntent? {
        if (max(abs(deltaX), abs(deltaY)) < touchSlop) return null
        val horizontal = abs(deltaX) >= abs(deltaY)
        val delta = if (horizontal) deltaX else deltaY
        if (abs(delta) < touchSlop) return null
        return SwipeIntent(
            if (horizontal) SwipeAxis.HORIZONTAL else SwipeAxis.VERTICAL,
            if (delta > 0f) -1 else 1
        )
    }

    fun isTap(deltaX: Float, deltaY: Float, touchSlop: Float): Boolean =
        max(abs(deltaX), abs(deltaY)) < touchSlop

    fun shouldCommit(distance: Float, touchSlop: Float): Boolean =
        distance >= touchSlop * 1.35f
}
