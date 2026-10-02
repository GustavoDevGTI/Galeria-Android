package com.galeria.android

import org.junit.Assert.assertNotEquals
import org.junit.Test

class CatalogFingerprintRulesTest {
    @Test fun renameInvalidatesSnapshotEvenWhenSizeAndDateAreUnchanged() {
        assertNotEquals(fingerprint("before.jpg"), fingerprint("after.jpg"))
    }

    @Test fun moveInvalidatesSnapshotEvenWhenUriAndFileContentsAreUnchanged() {
        assertNotEquals(fingerprint("photo.jpg", "Pictures/Before/"), fingerprint("photo.jpg", "Pictures/After/"))
    }

    private fun fingerprint(name: String, path: String = "Pictures/Before/") = CatalogFingerprintRules.append(
        CatalogFingerprintRules.INITIAL, "content://media/external/file/1", 1L, name,
        "image/jpeg", path, path, path.trimEnd('/').substringAfterLast('/'), 100L, 4096L, 0L
    )
}
