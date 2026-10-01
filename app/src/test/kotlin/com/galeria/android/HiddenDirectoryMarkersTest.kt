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

    @Test
    fun rememberedCacheStaysHiddenWhenBucketsGrowAndOnlyOneRemainsIndexed() {
        val markers = HiddenDirectoryMarkers(temporary.newFolder("storage"))
        for (count in listOf(4, 50, 500)) {
            val bucket = AlbumItem("1d", "1d", count, null, 1, 1, 10, "Documents/Cache/1d/")
            assertEquals(setOf("1d"), AutomaticHiddenAlbums.keys(listOf(bucket), markers,
                rememberedParents = setOf("Documents/Cache")))
        }
    }

    @Test
    fun newlyDetectedCacheAlsoHidesItsLargerBuckets() {
        val markers = HiddenDirectoryMarkers(temporary.newFolder("storage"))
        val small = (0 until 8).map {
            val name = "%02x".format(it)
            AlbumItem(name, name, 1, null, 1, 1, 10, "Documents/Cache/$name/")
        }
        val large = AlbumItem("ff", "ff", 25, null, 1, 1, 10, "Documents/Cache/ff/")
        assertEquals((small + large).mapTo(HashSet()) { it.key },
            AutomaticHiddenAlbums.keys(small + large, markers))
    }

    @Test
    fun knownHashSubfoldersRemainHiddenWithoutHidingAdjacentOrdinaryFolders() {
        val markers = HiddenDirectoryMarkers(temporary.newFolder("storage"))
        val source = listOf(
            AlbumItem("nested", "Pages", 30, null, 1, 1, 10, "Documents\\Cache\\1d\\Pages\\"),
            AlbumItem("notes", "Notes", 1, null, 1, 1, 10, "Documents/Cache/Notes/"),
            AlbumItem("other", "1d", 1, null, 1, 1, 10, "Documents/CacheOther/1d/"),
            AlbumItem("camera", "1d", 1, null, 1, 1, 10, "DCIM/1d/")
        )
        assertEquals(setOf("nested"), AutomaticHiddenAlbums.keys(source, markers,
            rememberedParents = setOf("Documents/Cache/")))
    }

    @Test
    fun largeHexAlbumsAloneAreNotEnoughToClassifyOrdinaryFoldersAsCaches() {
        val markers = HiddenDirectoryMarkers(temporary.newFolder("storage"))
        val source = (0 until 8).map {
            val name = "%02x".format(it)
            AlbumItem(name, name, 10, null, 1, 1, 10, "Pictures/Albums/$name/")
        }
        assertTrue(AutomaticHiddenAlbums.keys(source, markers).isEmpty())
    }

    @Test
    fun temporaryRevealStillIsolatesKnownCacheFromAllMediaSummary() {
        val markers = HiddenDirectoryMarkers(temporary.newFolder("storage"))
        val source = listOf(
            AlbumItem("cache", "1d", 50, null, 1, 1, 10, "Documents/Cache/1d/"),
            AlbumItem("camera", "Camera", 2, null, 1, 1, 10, "DCIM/Camera/")
        )
        val hidden = AutomaticHiddenAlbums.keys(source, markers, setOf("Documents/Cache"))
        assertEquals(listOf("camera"), AlbumCatalogRules.prepare(source, emptySet(), false, false,
            naturallyHiddenKeys = hidden).map { it.key })
        val revealed = AlbumCatalogRules.prepare(source, emptySet(), false, true, setOf("cache"), hidden)
        assertEquals(listOf("all_media", "cache"), revealed.map { it.key })
        assertEquals(2, revealed.first().count)
    }
}
