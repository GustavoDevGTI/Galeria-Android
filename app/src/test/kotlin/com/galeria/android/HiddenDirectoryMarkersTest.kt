package com.galeria.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HiddenDirectoryMarkersTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun nomediaHidesItsDirectoryAndDescendantsButNotOtherAlbums() {
        val root = temporary.newFolder("storage")
        val parent = File(root, "Pictures/Family").apply { assertTrue(mkdirs()) }
        assertTrue(File(parent, ".nomedia").createNewFile())

        val markers = HiddenDirectoryMarkers(root)
        assertTrue(markers.containsNomedia("Pictures/Family/"))
        assertTrue(markers.containsNomedia("Pictures/Family/Trips/"))
        assertFalse(markers.containsNomedia("Pictures/Other/"))
        assertFalse(markers.containsNomedia("../Family/"))
    }

    @Test
    fun albumMarkerIsNaturallyHiddenWithoutChangingManualHiddenKeys() {
        val root = temporary.newFolder("storage")
        val folder = File(root, "Pictures/Shared").apply { assertTrue(mkdirs()) }
        assertTrue(File(folder, ".nomedia").createNewFile())
        val source = listOf(
            AlbumItem("shared", "Shared", 1, null, 1, 1, 10, "Pictures/Shared/"),
            AlbumItem("camera", "Câmera", 1, null, 2, 2, 10, "DCIM/Camera/")
        )
        val markerKeys = HiddenDirectoryMarkers(root).hiddenAlbumKeys(source)
        assertEquals(setOf("shared"), markerKeys)
        assertEquals(listOf("camera"), AlbumCatalogRules.prepare(source, emptySet(), false, false,
            naturallyHiddenKeys = markerKeys).map { it.key })
        assertEquals(setOf("camera", "shared"), AlbumCatalogRules.prepare(source, emptySet(), true, false,
            naturallyHiddenKeys = markerKeys).map { it.key }.toSet())
    }

    @Test
    fun manyTinyHexBucketsAreHiddenButOrdinarySmallAlbumsRemain() {
        val markers = HiddenDirectoryMarkers(temporary.newFolder("storage"))
        val shards = (0 until 8).map { index ->
            val name = "%02x".format(index)
            AlbumItem(name, name, 2, null, 1, 1, 10, "Documents/Cache/$name/")
        }
        val normal = AlbumItem("notes", "Anotações", 1, null, 1, 1, 10, "Pictures/Notes/")
        val unrelated = AlbumItem("ff", "ff", 1, null, 1, 1, 10, "Pictures/ff/")
        val hidden = AutomaticHiddenAlbums.keys(shards + normal + unrelated, markers)
        assertEquals(shards.mapTo(HashSet()) { it.key }, hidden)
        assertEquals(emptySet<String>(), AutomaticHiddenAlbums.keys(shards.take(7) + normal + unrelated, markers))
        assertEquals(setOf("00"), AutomaticHiddenAlbums.keys(shards.take(1), markers,
            rememberedParents = setOf("Documents/Cache")))
    }
}
