package com.galeria.android

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigrationInstrumentedTest {
    private val databaseName = "gallery-migration-regression-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java
    )

    @After
    fun removeTestDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun migrationPreservesMediaAndCustomOrderWhileAddingDuration() {
        helper.createDatabase(databaseName, 1).use { database ->
            database.execSQL("""
                INSERT INTO cached_media VALUES
                ('visible', 'content://media/external/file/1', 1, 'photo.jpg', 'image/jpeg',
                 100, 4096, 'Pictures/Test/', 'Pictures/Test/', 'Test')
            """.trimIndent())
            database.execSQL("INSERT INTO custom_media_order VALUES ('Pictures/Test/', 'content://media/external/file/1', 0)")
        }
        helper.runMigrationsAndValidate(databaseName, 3, true, GalleryDatabase.MIGRATION_1_2, GalleryDatabase.MIGRATION_2_3).use { database ->
            database.query("SELECT name, duration FROM cached_media").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("photo.jpg", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
            }
            database.query("SELECT uri, position FROM custom_media_order").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://media/external/file/1", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun versionTwoUpgradeKeepsDurationAndUsesCompoundCoverIndex() {
        helper.createDatabase(databaseName, 2).use { database ->
            database.execSQL("INSERT INTO cached_media VALUES ('visible', 'content://media/external/file/2', 2, 'movie.mp4', 'video/mp4', 100, 4096, 'Movies/Test/', 'Movies/Test/', 'Test', 12345)")
            database.execSQL("INSERT INTO custom_media_order VALUES ('Movies/Test/', 'content://media/external/file/2', 7)")
        }
        helper.runMigrationsAndValidate(databaseName, 3, true, GalleryDatabase.MIGRATION_2_3).use { database ->
            database.query("SELECT duration FROM cached_media").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(12345L, cursor.getLong(0))
            }
            database.query("SELECT position FROM custom_media_order").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(7, cursor.getInt(0))
            }
            database.query("EXPLAIN QUERY PLAN SELECT uri FROM cached_media WHERE scope = 'visible' AND albumKey = 'Movies/Test/' ORDER BY dateAdded DESC LIMIT 1").use { cursor ->
                val details = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
                assertTrue(details, details.contains("index_cached_media_scope_albumKey_dateAdded"))
                assertTrue(details, !details.contains("TEMP B-TREE"))
            }
        }
    }
}
