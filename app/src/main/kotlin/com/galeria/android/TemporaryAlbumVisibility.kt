package com.galeria.android

import android.os.SystemClock

/** A revelação existe apenas neste processo; não é gravada nas preferências. */
internal class TemporaryAlbumRevealStore(private val nowMillis: () -> Long) {
    private data class Reveal(val expiresAt: Long, val requiresFilesystem: Boolean)
    private val expiresAt = LinkedHashMap<String, Reveal>()

    @Synchronized fun toggle(key: String, requiresFilesystem: Boolean = false): Boolean {
        prune()
        if (expiresAt.remove(key) != null) return false
        expiresAt[key] = Reveal(nowMillis() + DURATION_MILLIS, requiresFilesystem)
        return true
    }

    @Synchronized fun activeKeys(): Set<String> {
        prune()
        return expiresAt.keys.toSet()
    }

    @Synchronized fun hide(key: String) {
        expiresAt.remove(key)
    }

    @Synchronized fun nextExpiryDelay(): Long? {
        prune()
        return expiresAt.values.minOfOrNull { it.expiresAt }?.let { (it - nowMillis()).coerceAtLeast(1L) }
    }

    @Synchronized fun requiresHiddenFilesystem(): Boolean {
        prune()
        return expiresAt.values.any { it.requiresFilesystem }
    }

    @Synchronized fun clear() = expiresAt.clear()

    private fun prune() {
        val now = nowMillis()
        expiresAt.entries.removeAll { it.value.expiresAt <= now }
    }

    companion object {
        const val DURATION_MILLIS = 30 * 60 * 1000L
    }
}

internal object TemporaryAlbumVisibility {
    private val store = TemporaryAlbumRevealStore(SystemClock::elapsedRealtime)

    fun toggle(key: String, requiresFilesystem: Boolean = false): Boolean = store.toggle(key, requiresFilesystem)
    fun activeKeys(): Set<String> = store.activeKeys()
    fun hide(key: String) = store.hide(key)
    fun nextExpiryDelay(): Long? = store.nextExpiryDelay()
    fun requiresHiddenFilesystem(): Boolean = store.requiresHiddenFilesystem()
    fun clear() = store.clear()
}
