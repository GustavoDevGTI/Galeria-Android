package com.galeria.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogFingerprintInstrumentedTest {
    @Test
    fun renameInvalidatesSnapshotEvenWhenSizeAndDateAreUnchanged() {
        assertNotEquals(fingerprint(item("before.jpg")), fingerprint(item("after.jpg")))
    }

    @Test
    fun moveInvalidatesSnapshotEvenWhenUriAndFileContentsAreUnchanged() {
        assertNotEquals(
            fingerprint(item("photo.jpg", "Pictures/Before/")),
            fingerprint(item("photo.jpg", "Pictures/After/"))
        )
    }

    private fun fingerprint(item: MediaItem): Long {
        // Exercise the exact predicate that decides whether Room is rewritten.
        val method = GalleryCatalogStore::class.java.getDeclaredMethod("catalogFingerprint", List::class.java)
        method.isAccessible = true
        return method.invoke(GalleryCatalogStore, listOf(item)) as Long
    }

    private fun item(name: String, path: String = "Pictures/Before/") = MediaItem(
        1L, Uri.parse("content://media/external/file/1"), name, "image/jpeg",
        100L, 4096L, path, path, path.trimEnd('/').substringAfterLast('/')
    )
}
