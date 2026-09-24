package com.galeria.android

import android.os.SystemClock

/** A revelação existe apenas neste processo; não é gravada nas preferências. */
internal class TemporaryAlbumRevealStore(private val nowMillis: () -> Long) {
    private val expiresAt = LinkedHashMap<String, Long>()

    @Synchronized fun toggle(key: String): Boolean {
        prune()
        if (expiresAt.remove(key) != null) return false
        expiresAt[key] = nowMillis() + DURATION_MILLIS
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
        return expiresAt.values.minOrNull()?.let { (it - nowMillis()).coerceAtLeast(1L) }
    }

    @Synchronized fun clear() = expiresAt.clear()

    private fun prune() {
        val now = nowMillis()
        expiresAt.entries.removeAll { it.value <= now }
    }

    companion object {
        const val DURATION_MILLIS = 30 * 60 * 1000L
    }
}

internal object TemporaryAlbumVisibility {
    private val store = TemporaryAlbumRevealStore(SystemClock::elapsedRealtime)

    fun toggle(key: String): Boolean = store.toggle(key)
    fun activeKeys(): Set<String> = store.activeKeys()
    fun hide(key: String) = store.hide(key)
    fun nextExpiryDelay(): Long? = store.nextExpiryDelay()
    fun clear() = store.clear()
}
