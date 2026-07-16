package com.galeria.android

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryDatabaseInstrumentedTest {
    private lateinit var database: GalleryDatabase
    private lateinit var dao: GalleryDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.galleryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun albumSummaryUsesNewestCoverAndAggregatesMetadata() {
        dao.replaceMedia(
            "visible",
            listOf(
                media("content://media/1", "camera", "Camera", 10, 100),
                media("content://media/2", "camera", "Camera", 30, 300),
                media("content://media/3", "screens", "Screenshots", 20, 200)
            ),
            CatalogStateEntity("visible", 1234, false)
        )

        val summaries = dao.albumSummaries("visible")
        val camera = summaries.first { it.albumKey == "camera" }

        assertEquals(2, camera.itemCount)
        assertEquals(30L, camera.latestDate)
        assertEquals(10L, camera.firstDate)
        assertEquals(400L, camera.totalSize)
        assertEquals("content://media/2", camera.coverUri)
        assertEquals(2, summaries.size)
    }

    @Test
    fun replacingOneScopeDoesNotEraseTheOtherScope() {
        dao.replaceMedia("visible", listOf(media("content://visible/1", "camera", "Camera", 1, 10)), CatalogStateEntity("visible", 1, false))
        dao.replaceMedia(
            "complete",
            listOf(media("file:///hidden/1", "hidden", "Hidden", 2, 20).copy(scope = "complete")),
            CatalogStateEntity("complete", 2, true)
        )
        dao.replaceMedia("visible", listOf(media("content://visible/2", "screens", "Screenshots", 3, 30)), CatalogStateEntity("visible", 3, false))

        assertEquals(listOf("content://visible/2"), dao.media("visible").map { it.uri })
        assertEquals(listOf("file:///hidden/1"), dao.media("complete").map { it.uri })
        assertTrue(dao.state("complete")!!.allFilesAccess)
    }

    @Test
    fun pagedQueryHonorsCustomOrderBeforeModificationDate() = runBlocking {
        dao.replaceMedia(
            "visible",
            listOf(
                media("content://media/a", "camera", "Camera", 30, 10),
                media("content://media/b", "camera", "Camera", 20, 10),
                media("content://media/c", "camera", "Camera", 10, 10)
            ),
            CatalogStateEntity("visible", 1, false)
        )
        dao.replaceCustomOrder("camera", listOf("content://media/c", "content://media/a"))

        val result = dao.pagedMedia("visible", "camera", "camera", "").load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false)
        )
        val page = result as PagingSource.LoadResult.Page<Int, CachedMediaEntity>

        assertEquals(
            listOf("content://media/c", "content://media/a", "content://media/b"),
            page.data.map { it.uri }
        )
    }

    private fun media(
        uri: String,
        albumKey: String,
        albumName: String,
        date: Long,
        size: Long
    ) = CachedMediaEntity(
        scope = "visible",
        uri = uri,
        mediaId = date,
        name = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        dateAdded = date,
        size = size,
        relativePath = "Pictures/$albumName/",
        albumKey = albumKey,
        albumName = albumName
    )
}
