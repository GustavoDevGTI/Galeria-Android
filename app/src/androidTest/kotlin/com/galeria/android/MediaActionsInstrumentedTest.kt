package com.galeria.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class MediaActionsInstrumentedTest {
    @Test
    fun movesMediaToTheExactSelectedAlbumPath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("initial_all_files_requested", true)
            .putBoolean("all_files_prompted", true)
            .commit()

        val resolver = context.contentResolver
        val suffix = System.currentTimeMillis().toString()
        val sourcePath = "Pictures/GaleriaMoveSource-$suffix/"
        val targetPath = "DCIM/GaleriaMoveTarget-$suffix/"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "move-test-$suffix.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, sourcePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        val mediaUri = requireNotNull(uri)

        try {
            resolver.openOutputStream(mediaUri)?.use { output ->
                output.write(ONE_PIXEL_PNG)
            }
            resolver.update(
                mediaUri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            val item = MediaItem(
                ContentUris.parseId(mediaUri),
                mediaUri,
                "move-test-$suffix.png",
                "image/png",
                System.currentTimeMillis() / 1000,
                ONE_PIXEL_PNG.size.toLong(),
                sourcePath,
                sourcePath,
                "Source"
            )

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(
                        MediaActions.RESULT_DONE,
                        MediaActions.moveToFolder(activity, item, targetPath)
                    )
                }
            }

            val actualPath = resolver.query(
                mediaUri,
                arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            assertEquals(targetPath, actualPath)
        } finally {
            resolver.delete(mediaUri, null, null)
        }
    }

    private companion object {
        val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
