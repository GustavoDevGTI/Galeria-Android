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
        helper.runMigrationsAndValidate(databaseName, 2, true, GalleryDatabase.MIGRATION_1_2).use { database ->
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
}
