package com.galeria.android

/** Pure arithmetic shared by the catalog and JVM regression tests. */
internal object CatalogFingerprintRules {
    const val INITIAL = 1125899906842597L

    fun append(
        previous: Long, uri: String, id: Long, name: String, mimeType: String,
        relativePath: String, albumKey: String, albumName: String,
        dateAdded: Long, size: Long, duration: Long
    ): Long {
        var value = previous
        value = value * 31 + uri.hashCode()
        value = value * 31 + id
        value = value * 31 + name.hashCode()
        value = value * 31 + mimeType.hashCode()
        value = value * 31 + relativePath.hashCode()
        value = value * 31 + albumKey.hashCode()
        value = value * 31 + albumName.hashCode()
        value = value * 31 + dateAdded
        value = value * 31 + size
        value = value * 31 + duration
        return value
    }
}
