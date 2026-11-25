package com.galeria.android

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Trashed media of hidden folders follow the album grid: in the Lixeira while their album is exposed. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class HiddenAlbumTrashInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private val root = Environment.getExternalStorageDirectory()
    private val stamp = System.nanoTime()
    private val folders = ArrayList<File>()
    private var oldAccess = "default"
    private val saved = HashMap<String, Any?>()

    @Before fun prepare() {
        oldAccess = Regex("MANAGE_EXTERNAL_STORAGE: (\\w+)").find(shell("appops get ${context.packageName} MANAGE_EXTERNAL_STORAGE"))?.groupValues?.get(1) ?: "default"
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        for (key in listOf(TrashPreferences.ENABLED, GalleryPreferences.SHOW_HIDDEN_FOLDERS, GalleryPreferences.HIDDEN_FOLDER_KEYS,
            VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF)) saved[key] = prefs.all[key]
        prefs.edit().putBoolean(TrashPreferences.ENABLED, true).putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false).commit()
        TemporaryAlbumVisibility.clear()
    }

    @After fun cleanup() {
        TemporaryAlbumVisibility.clear()
        prefs.edit().apply {
            saved.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringSet(key, value as Set<String>)
                }
            }
        }.commit()
        folders.forEach { it.deleteRecursively() }
        File(root, ".Galeria-Lixeira").listFiles()?.filter { it.name.contains("trash-$stamp") }?.forEach { it.delete() }
        context.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%trash-$stamp%"))
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE $oldAccess")
        MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
    }

    @Test fun mediaDeletedFromAShownHiddenFolderShowInTheLixeiraUntilTheFolderIsHiddenAgain() {
        // Hidden folders shown in the grid, as when the user ticks a ".nomedia" album as visible.
        prefs.edit().putBoolean(GalleryPreferences.SHOW_HIDDEN_FOLDERS, true).commit()
        val shown = hiddenFolder("Exposta", nomedia = true)
        val dotted = hiddenFolder("Ponto", nomedia = false)
        val userHidden = hiddenFolder("Recusada", nomedia = true)
        prefs.edit().putStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS,
            prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, emptySet()).orEmpty() + userHidden.second).commit()
        listOf(shown, dotted, userHidden).forEach { delete(it) }

        assertEquals("Pastas ocultas expostas mostram na Lixeira o que foi apagado delas; a que o usuário ocultou, não",
            setOf(shown.first.name, dotted.first.name), trashNames())
        // Hidden folders hidden again: their trashed items leave the Lixeira.
        prefs.edit().putBoolean(GalleryPreferences.SHOW_HIDDEN_FOLDERS, false).commit()
        assertEquals(emptySet<String>(), trashNames())
        // The eye on one of them brings back only that one.
        TemporaryAlbumVisibility.toggle(shown.second, true)
        assertEquals(setOf(shown.first.name), trashNames())
    }

    /** A folder hidden on disk with one photo; returns the photo and the album key. */
    private fun hiddenFolder(name: String, nomedia: Boolean): Pair<File, String> {
        val key = if (nomedia) "Pictures/Galeria $name $stamp/" else "Pictures/.Galeria $name $stamp/"
        val dir = File(root, key).apply { mkdirs() }
        folders += dir
        if (nomedia) File(dir, ".nomedia").createNewFile()
        val photo = File(dir, "trash-$stamp-$name.png")
        photo.outputStream().use {
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return photo to key
    }

    private fun delete(folder: Pair<File, String>) {
        MediaStoreRepository.invalidateCache(); GalleryCatalogStore.markCatalogDirty(context)
        val item = MediaStoreRepository.loadMediaForAlbum(context, folder.second, includeHiddenFilesystem = true)
            .single { it.name == folder.first.name }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(MediaActions.RESULT_DONE, MediaActions.requestDelete(it, item.uri, 701)) }
        }
    }

    private fun trashNames(): Set<String> =
        MediaStoreRepository.loadMediaForAlbum(context, VirtualAlbumRules.TRASH_KEY)
            .map { it.name }.filter { it.contains("trash-$stamp") }.toSet()

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }
}
