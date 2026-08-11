package com.galeria.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(
    tableName = "cached_media",
    primaryKeys = ["scope", "uri"],
    indices = [Index("scope"), Index("albumKey"), Index("dateAdded")]
)
data class CachedMediaEntity(
    val scope: String,
    val uri: String,
    val mediaId: Long,
    val name: String,
    val mimeType: String,
    val dateAdded: Long,
    val size: Long,
    val relativePath: String,
    val albumKey: String,
    val albumName: String
)

@Entity(tableName = "catalog_state")
data class CatalogStateEntity(
    @androidx.room.PrimaryKey val scope: String,
    val scannedAt: Long,
    val allFilesAccess: Boolean
)

@Entity(
    tableName = "custom_media_order",
    primaryKeys = ["albumKey", "uri"],
    indices = [Index("albumKey")]
)
data class CustomMediaOrderEntity(
    val albumKey: String,
    val uri: String,
    val position: Int
)

data class CachedAlbumSummary(
    val albumKey: String,
    val albumName: String,
    val itemCount: Int,
    val latestDate: Long,
    val firstDate: Long,
    val totalSize: Long,
    val relativePath: String,
    val coverUri: String,
    val coverMimeType: String
)

@Dao
abstract class GalleryDao {
    @Query("SELECT * FROM cached_media WHERE scope = :scope ORDER BY dateAdded DESC")
    abstract fun media(scope: String): List<CachedMediaEntity>

    @Query(
        "SELECT * FROM cached_media " +
            "WHERE scope = :scope AND albumKey = :albumKey " +
            "ORDER BY dateAdded DESC"
    )
    abstract fun mediaForAlbum(scope: String, albumKey: String): List<CachedMediaEntity>

    @Query(
        """
        SELECT
            media.albumKey AS albumKey,
            MAX(media.albumName) AS albumName,
            COUNT(*) AS itemCount,
            MAX(media.dateAdded) AS latestDate,
            COALESCE(MIN(CASE WHEN media.dateAdded > 0 THEN media.dateAdded END), 0) AS firstDate,
            SUM(CASE WHEN media.size > 0 THEN media.size ELSE 0 END) AS totalSize,
            MAX(media.relativePath) AS relativePath,
            (
                SELECT cover.uri
                FROM cached_media AS cover
                WHERE cover.scope = :scope AND cover.albumKey = media.albumKey
                ORDER BY cover.dateAdded DESC
                LIMIT 1
            ) AS coverUri,
            (
                SELECT cover.mimeType
                FROM cached_media AS cover
                WHERE cover.scope = :scope AND cover.albumKey = media.albumKey
                ORDER BY cover.dateAdded DESC
                LIMIT 1
            ) AS coverMimeType
        FROM cached_media AS media
        WHERE media.scope = :scope
        GROUP BY media.albumKey
        ORDER BY latestDate DESC
        """
    )
    abstract fun albumSummaries(scope: String): List<CachedAlbumSummary>

    @Query(
        """
        SELECT cached_media.*
        FROM cached_media
        LEFT JOIN custom_media_order
            ON custom_media_order.uri = cached_media.uri
            AND custom_media_order.albumKey = :customOrderAlbumKey
        WHERE cached_media.scope = :scope
            AND (:albumKey = '__all__' OR cached_media.albumKey = :albumKey)
            AND (
                :query = ''
                OR cached_media.name LIKE '%' || :query || '%'
                OR cached_media.relativePath LIKE '%' || :query || '%'
            )
        ORDER BY
            CASE WHEN custom_media_order.position IS NULL THEN 1 ELSE 0 END,
            custom_media_order.position ASC,
            cached_media.dateAdded DESC
        """
    )
    abstract fun pagedMedia(
        scope: String,
        albumKey: String,
        customOrderAlbumKey: String,
        query: String
    ): PagingSource<Int, CachedMediaEntity>

    @Query("DELETE FROM cached_media WHERE scope = :scope")
    abstract fun deleteMedia(scope: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertMedia(items: List<CachedMediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveState(state: CatalogStateEntity)

    @Query("SELECT * FROM catalog_state WHERE scope = :scope LIMIT 1")
    abstract fun state(scope: String): CatalogStateEntity?

    @Query("SELECT uri FROM custom_media_order WHERE albumKey = :albumKey ORDER BY position")
    abstract fun customOrder(albumKey: String): List<String>

    @Query("DELETE FROM custom_media_order WHERE albumKey = :albumKey")
    abstract fun deleteCustomOrder(albumKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertCustomOrder(items: List<CustomMediaOrderEntity>)

    @Transaction
    open fun replaceMedia(scope: String, items: List<CachedMediaEntity>, state: CatalogStateEntity) {
        deleteMedia(scope)
        if (items.isNotEmpty()) insertMedia(items)
        saveState(state)
    }

    @Transaction
    open fun replaceCustomOrder(albumKey: String, uris: List<String>) {
        deleteCustomOrder(albumKey)
        if (uris.isNotEmpty()) {
            insertCustomOrder(uris.mapIndexed { index, uri -> CustomMediaOrderEntity(albumKey, uri, index) })
        }
    }
}

@Database(
    entities = [CachedMediaEntity::class, CatalogStateEntity::class, CustomMediaOrderEntity::class],
    version = 1,
    exportSchema = true
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao

    companion object {
        @Volatile private var instance: GalleryDatabase? = null

        fun get(context: Context): GalleryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GalleryDatabase::class.java,
                "gallery_catalog.db"
            ).build().also { instance = it }
        }
    }
}

object GalleryCatalogStore {
    private const val VISIBLE_SCOPE = "visible"
    private const val COMPLETE_SCOPE = "complete"
    private const val CATALOG_META_PREFS = "gallery_catalog_meta"
    @Volatile private var visibleSnapshot: List<MediaItem> = emptyList()
    @Volatile private var completeSnapshot: List<MediaItem> = emptyList()

    fun readMedia(context: Context, includeHidden: Boolean): List<MediaItem> {
        val scope = scope(includeHidden)
        val result = GalleryDatabase.get(context).galleryDao().media(scope).map { it.toMediaItem() }
        updateSnapshot(includeHidden, result)
        return result
    }

    fun readAlbumMedia(context: Context, includeHidden: Boolean, albumKey: String): List<MediaItem> =
        GalleryDatabase.get(context).galleryDao()
            .mediaForAlbum(scope(includeHidden), albumKey)
            .map { it.toMediaItem() }

    fun readAlbums(context: Context, includeHidden: Boolean): List<AlbumItem> =
        GalleryDatabase.get(context).galleryDao().albumSummaries(scope(includeHidden)).map { summary ->
            val cover = summary.coverUri.takeIf { it.isNotEmpty() }?.let { uri ->
                MediaItem(
                    0L,
                    Uri.parse(uri),
                    "",
                    summary.coverMimeType,
                    summary.latestDate,
                    0L,
                    summary.relativePath,
                    summary.albumKey,
                    summary.albumName
                )
            }
            AlbumItem(
                summary.albumKey,
                summary.albumName,
                summary.itemCount,
                cover,
                summary.latestDate,
                summary.firstDate,
                summary.totalSize,
                summary.relativePath
            )
        }

    fun snapshot(includeHidden: Boolean): List<MediaItem> =
        ArrayList(if (includeHidden) completeSnapshot else visibleSnapshot)

    fun pagedMedia(
        context: Context,
        includeHidden: Boolean,
        albumKey: String?,
        query: String,
        config: PagingConfig
    ): Flow<PagingData<MediaItem>> {
        val dao = GalleryDatabase.get(context).galleryDao()
        val requestedAlbum = if (albumKey == null || albumKey == "all_media") "__all__" else albumKey
        val customOrderAlbum = albumKey ?: "all"
        return Pager(config) {
            dao.pagedMedia(scope(includeHidden), requestedAlbum, customOrderAlbum, query.trim())
        }.flow.map { page -> page.map { it.toMediaItem() } }
    }

    fun writeMedia(context: Context, items: List<MediaItem>, includeHidden: Boolean, allFilesAccess: Boolean) {
        val scope = scope(includeHidden)
        val dao = GalleryDatabase.get(context).galleryDao()
        val preferences = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
        val fingerprint = catalogFingerprint(items)
        val previousFingerprint = preferences.getLong(fingerprintKey(includeHidden), Long.MIN_VALUE)
        val previousState = dao.state(scope)
        val state = CatalogStateEntity(scope, System.currentTimeMillis(), allFilesAccess)
        if (previousFingerprint == fingerprint && previousState?.allFilesAccess == allFilesAccess) {
            dao.saveState(state)
            preferences.edit()
                .putString(versionKey(includeHidden), currentMediaStoreVersion(context))
                .apply()
            updateSnapshot(includeHidden, items)
            return
        }
        val entities = items.map { item ->
            CachedMediaEntity(
                scope, item.uri.toString(), item.id, item.name, item.mimeType, item.dateAdded,
                item.size, item.relativePath, item.albumKey, item.albumName
            )
        }
        dao.replaceMedia(scope, entities, state)
        preferences.edit()
            .putLong(fingerprintKey(includeHidden), fingerprint)
            .putString(versionKey(includeHidden), currentMediaStoreVersion(context))
            .apply()
        updateSnapshot(includeHidden, items)
    }

    private fun catalogFingerprint(items: List<MediaItem>): Long {
        var fingerprint = 1125899906842597L
        for (item in items) {
            fingerprint = fingerprint * 31 + item.uri.toString().hashCode()
            fingerprint = fingerprint * 31 + item.dateAdded
            fingerprint = fingerprint * 31 + item.size
        }
        return fingerprint
    }

    private fun fingerprintKey(includeHidden: Boolean): String =
        "catalog_fingerprint_${if (includeHidden) "complete" else "visible"}"

    fun customOrder(context: Context, albumKey: String): List<String> =
        GalleryDatabase.get(context).galleryDao().customOrder(albumKey)

    fun hasFreshCatalog(context: Context, includeHidden: Boolean, allFilesAccess: Boolean, maxAgeMs: Long): Boolean {
        val state = GalleryDatabase.get(context).galleryDao().state(scope(includeHidden)) ?: return false
        if (state.allFilesAccess != allFilesAccess) return false
        val age = System.currentTimeMillis() - state.scannedAt
        if (includeHidden) return age <= maxAgeMs
        val currentVersion = currentMediaStoreVersion(context)
        val storedVersion = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
            .getString(versionKey(false), "")
            .orEmpty()
        return if (currentVersion.isNotEmpty() && storedVersion.isNotEmpty()) {
            currentVersion == storedVersion
        } else {
            age <= maxAgeMs
        }
    }

    fun saveCustomOrder(context: Context, albumKey: String, items: List<MediaItem>) {
        GalleryDatabase.get(context).galleryDao().replaceCustomOrder(albumKey, items.map { it.uri.toString() })
    }

    fun migrateLegacyOrder(context: Context, albumKey: String): List<String> {
        val existing = customOrder(context, albumKey)
        if (existing.isNotEmpty()) return existing
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val key = "custom_order_$albumKey"
        val legacy = prefs.getString(key, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        if (legacy.isNotEmpty()) {
            GalleryDatabase.get(context).galleryDao().replaceCustomOrder(albumKey, legacy)
            prefs.edit().remove(key).apply()
        }
        return legacy
    }

    private fun scope(includeHidden: Boolean) = if (includeHidden) COMPLETE_SCOPE else VISIBLE_SCOPE

    private fun versionKey(includeHidden: Boolean) = "media_store_version_${scope(includeHidden)}"

    private fun currentMediaStoreVersion(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL) }.getOrDefault("")
        } else {
            ""
        }

    private fun updateSnapshot(includeHidden: Boolean, items: List<MediaItem>) {
        val copy = ArrayList(items)
        if (includeHidden) completeSnapshot = copy else visibleSnapshot = copy
    }

    private fun CachedMediaEntity.toMediaItem() = MediaItem(
        mediaId, Uri.parse(uri), name, mimeType, dateAdded, size, relativePath, albumKey, albumName
    )
}
