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

object ExternalMediaRules {
    private val imageMimeByExtension = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "svg" to "image/svg+xml",
        "dng" to "image/x-adobe-dng",
        "raw" to "image/x-raw",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef",
        "nrw" to "image/x-nikon-nrw",
        "arw" to "image/x-sony-arw",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "raf" to "image/x-fuji-raf"
    )
    private val videoMimeByExtension = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp",
        "ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg"
    )

    fun normalizedMime(name: String?, declaredMime: String?): String {
        val declared = declaredMime.orEmpty().substringBefore(';').trim().lowercase(Locale.US)
        if (declared.startsWith("image/") || declared.startsWith("video/")) return declared
        val extension = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.US)
        return imageMimeByExtension[extension]
            ?: videoMimeByExtension[extension]
            ?: declared
    }

    fun isSupported(mimeType: String): Boolean =
        mimeType.startsWith("image/") || mimeType.startsWith("video/")
}

object HiddenAlbumDialogRules {
    fun keysForInitialDialog(
        currentlyVisible: Collection<String>,
        previouslyVisible: Collection<String>
    ): Set<String> = LinkedHashSet<String>().apply {
        addAll(previouslyVisible)
        addAll(currentlyVisible)
        remove("all_media")
    }

    fun rememberVisible(
        previouslyVisible: Collection<String>,
        visibleNow: Collection<String>
    ): Set<String> = keysForInitialDialog(visibleNow, previouslyVisible)
}

object GridColumnRules {
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 8
    const val SCALE_STEP = 1.12f

    fun normalized(columns: Int, fallback: Int): Int =
        (if (columns > 0) columns else fallback).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun columnDelta(horizontalScale: Float): Int = when {
        horizontalScale >= SCALE_STEP -> -1
        horizontalScale <= 1f / SCALE_STEP -> 1
        else -> 0
    }

    fun changed(columns: Int, delta: Int): Int =
        (columns + delta).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
}

object MediaIdentityRules {
    fun sameUri(first: String, second: String): Boolean {
        if (first == second) return true
        val firstId = mediaStoreId(first) ?: return false
        val secondId = mediaStoreId(second) ?: return false
        return firstId == secondId
    }

    private fun mediaStoreId(uri: String): Long? =
        uri.takeIf { it.startsWith("content://media/") }
            ?.substringAfterLast('/')
            ?.toLongOrNull()
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

object AlbumTargetRules {
    const val EXTRA_EXPOSED_ALBUM_KEYS = "exposed_album_keys"

    fun exposedTargets(
        source: List<AlbumItem>,
        exposedKeys: Set<String>?,
        hiddenKeys: Set<String>,
        excludedKeys: Set<String>
    ): List<AlbumItem> {
        val targets = LinkedHashMap<String, AlbumItem>()
        for (album in source) {
            val exposed = if (exposedKeys != null) {
                exposedKeys.contains(album.key)
            } else {
                !hiddenKeys.contains(album.key) && !AlbumRules.isHidden(album.path, album.key)
            }
            if (
                exposed &&
                album.key != "all_media" &&
                !excludedKeys.contains(album.key) &&
                album.name.isNotBlank()
            ) {
                val targetKey = album.path.ifBlank { album.key }
                if (!targets.containsKey(targetKey)) targets[targetKey] = album
            }
        }
        return targets.values.toList()
    }
}

object ViewerStateRules {
    fun shuffledFromCurrent(availableUris: List<String>, currentUri: String, seed: Long): List<String> {
        if (availableUris.isEmpty()) return emptyList()
        val shuffled = availableUris.distinct().sorted().toMutableList()
        Collections.shuffle(shuffled, Random(seed))
        val currentIndex = shuffled.indexOfFirst { MediaIdentityRules.sameUri(it, currentUri) }
        if (currentIndex <= 0) return shuffled
        return shuffled.drop(currentIndex) + shuffled.take(currentIndex)
    }
}

object ViewerMenuRules {
    const val RENAME = "Renomear"
    const val OPEN_WITH = "Abrir com"
    const val COPY_TO = "Copiar para"
    const val MOVE_TO = "Mover para"
    const val HIDE = "Ocultar"
    const val INFORMATION = "Informações"
    const val ENABLE_LOOP = "Reproduzir em loop"
    const val DISABLE_LOOP = "Desativar repetição"
    const val SET_AS = "Definir como"
    const val ROTATE = "Alterar orientação"
    const val EXPORT_PDF = "Exportar como PDF"
    const val RESIZE = "Redimensionar"
    const val SHOW_ON_MAP = "Exibir no mapa"
    const val PRESENTATION = "Apresentação"

    fun options(
        isVideo: Boolean,
        loopEnabled: Boolean,
        shuffleMode: Boolean,
        hasLocation: Boolean = false
    ): List<String> {
        if (!isVideo) {
            return buildList {
                add(RENAME)
                add(OPEN_WITH)
                add(INFORMATION)
                add(HIDE)
                add(COPY_TO)
                add(MOVE_TO)
                add(SET_AS)
                add(ROTATE)
                add(EXPORT_PDF)
                add(RESIZE)
                if (hasLocation) add(SHOW_ON_MAP)
                if (!shuffleMode) add(PRESENTATION)
            }
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

    fun normalizedRename(requestedName: String, currentName: String): String? {
        val cleaned = requestedName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.', ' ')
        if (cleaned.isEmpty()) return null
        val currentExtension = currentName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        return if (currentExtension != null && !cleaned.contains('.')) {
            "$cleaned.$currentExtension"
        } else {
            cleaned
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
