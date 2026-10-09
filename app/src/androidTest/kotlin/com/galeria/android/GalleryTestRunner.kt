package com.galeria.android

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import androidx.test.runner.AndroidJUnitRunner

/** Two-process fixture: seed with the old APK, verify after adb install -r. Not production code. */
class GalleryTestRunner : AndroidJUnitRunner() {
    private var phase: String? = null

    override fun onCreate(arguments: Bundle?) {
        phase = arguments?.getString("upgradePhase")
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (phase == null) { super.onStart(); return }
        val result = Bundle()
        try {
            check(android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk")) { "Fixture exclusiva para emulador" }
            when (phase) {
                "seed" -> seed()
                "verify" -> verify()
                else -> error("Fase inválida")
            }
            result.putString("stream", "\nUPGRADE_$phase: PASSED\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "\nUPGRADE_$phase: FAILED\n${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun seed() {
        val app = targetContext
        @Suppress("DEPRECATION")
        check(app.packageManager.getPackageInfo(app.packageName, 0).versionCode == 8062)
        // Use Android APIs only here: this phase executes against the historical APK.
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "upgrade-probe.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UpgradeProbe/")
            }) ?: error("Não foi possível criar a mídia da fixture")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            app.contentResolver.openOutputStream(uri)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
            }
        } finally { bitmap.recycle() }
        app.getSharedPreferences("gallery_upgrade_probe", Context.MODE_PRIVATE).edit()
            .putString("media_uri", uri.toString()).commit()
        app.getSharedPreferences("gallery_albums", Context.MODE_PRIVATE).edit()
            .putStringSet("favorites", setOf(uri.toString()))
            .putStringSet("hidden_folder_keys", setOf("Pictures/UpgradeHidden/"))
            .putStringSet("pinned_album_keys", setOf("Pictures/UpgradeProbe/"))
            .putString("album_cover_Pictures/UpgradeProbe/", uri.toString())
            .putBoolean("initial_all_files_requested", true)
            .putBoolean("all_files_prompted", true).commit()
        app.getSharedPreferences("gallery_automatic_hidden_albums", Context.MODE_PRIVATE).edit()
            .putStringSet("hash_bucket_parents", setOf("documents/generated-cache")).commit()
        app.getSharedPreferences("gallery_catalog_meta", Context.MODE_PRIVATE).edit()
            .putInt("catalog_model_version_visible", 3)
            .putInt("catalog_model_version_complete", 3)
            .putLong("catalog_fingerprint_visible", 123)
            .putLong("catalog_fingerprint_complete", 456)
            .putBoolean("catalog_dirty_after_media_action", true).commit()
        File(app.noBackupFilesDir, "upgrade-probe-thumbnail").writeBytes(byteArrayOf(7, 8, 9))
        val dbFile = app.getDatabasePath("gallery_catalog.db")
        dbFile.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            if (db.version == 0) {
                val schema = JSONObject(context.assets.open("com.galeria.android.GalleryDatabase/2.json")
                    .bufferedReader().use { it.readText() }).getJSONObject("database")
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.version = 2
            }
            check(db.version == 2)
            db.execSQL("INSERT OR REPLACE INTO custom_media_order VALUES (?, ?, 0)",
                arrayOf("Pictures/UpgradeProbe/", uri.toString()))
            db.execSQL("INSERT OR REPLACE INTO cached_media VALUES ('visible', 'content://media/external/file/99999999', 99999999, 'stale.jpg', 'image/jpeg', 1, 1, 'Pictures/Stale/', 'Pictures/Stale/', 'Stale', 0)")
        }
    }

    private fun verify() {
        val app = targetContext
        GalleryUpgradeCoordinator.ensureReady(app)
        val uri = app.getSharedPreferences("gallery_upgrade_probe", Context.MODE_PRIVATE)
            .getString("media_uri", null) ?: error("Dados da instalação anterior desapareceram")
        val prefs = app.getSharedPreferences("gallery_albums", Context.MODE_PRIVATE)
        check(prefs.getStringSet("favorites", emptySet()) == setOf(uri))
        check(prefs.getStringSet("hidden_folder_keys", emptySet()) == setOf("Pictures/UpgradeHidden/"))
        check(prefs.getStringSet("pinned_album_keys", emptySet()) == setOf("Pictures/UpgradeProbe/"))
        check(prefs.getString("album_cover_Pictures/UpgradeProbe/", null) == uri)
        check(app.getSharedPreferences("gallery_automatic_hidden_albums", Context.MODE_PRIVATE)
            .getStringSet("hash_bucket_parents", emptySet()) == setOf("documents/generated-cache"))
        check(File(app.noBackupFilesDir, "upgrade-probe-thumbnail").readBytes().contentEquals(byteArrayOf(7, 8, 9)))
        check(GalleryCatalogStore.customOrder(app, "Pictures/UpgradeProbe/") == listOf(uri))
        val media = MediaStoreRepository.refreshMedia(app, false, force = true)
        check(media.any { MediaIdentityRules.sameUri(it.uri.toString(), uri) }) { "Mídia real não apareceu após atualizar" }
        check(media.none { it.name == "stale.jpg" }) { "Catálogo obsoleto foi reutilizado" }
        GalleryDatabase.get(app).openHelper.readableDatabase.query("PRAGMA user_version").use {
            check(it.moveToFirst() && it.getInt(0) == 3)
        }
        check(GalleryCatalogStore.customOrder(app, "Pictures/UpgradeProbe/") == listOf(uri))
        val loaded = CountDownLatch(1)
        var failure: Throwable? = null
        val controller = AlbumCatalogController(app)
        try {
            controller.load(AlbumCatalogOptions(false, false, setOf("Pictures/UpgradeHidden/"), "",
                MediaFilterOptions(), AlbumRules.SORT_NAME, false), { albums, _ ->
                try {
                    check(albums.any { it.key == "Pictures/UpgradeProbe/" })
                    check(albums.none { it.key == "Pictures/Stale/" })
                } catch (error: Throwable) { failure = error }
                loaded.countDown()
            }, {}, { failure = IllegalStateException("Catálogo falhou"); loaded.countDown() })
            check(loaded.await(15, TimeUnit.SECONDS)) { "Carregamento ficou sem conclusão" }
            failure?.let { throw it }
        } finally { controller.close() }
    }
}
