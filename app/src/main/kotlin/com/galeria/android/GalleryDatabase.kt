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
import androidx.core.content.edit
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(
    tableName = "cached_media",
    primaryKeys = ["scope", "uri"],
    indices = [Index("scope"), Index("albumKey"), Index("dateAdded"),
        Index(value = ["scope", "albumKey", "dateAdded"]), Index(value = ["scope", "dateAdded"])]
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
    val albumName: String,
    val duration: Long = 0L
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

    @Query("SELECT EXISTS(SELECT 1 FROM cached_media WHERE scope = :scope AND albumKey = :albumKey)")
    abstract fun hasAlbumMedia(scope: String, albumKey: String): Boolean

    @Query(
        """
        WITH albums AS (
        SELECT
            media.albumKey AS albumKey,
            MAX(media.albumName) AS albumName,
            COUNT(*) AS itemCount,
            MAX(media.dateAdded) AS latestDate,
            COALESCE(MIN(CASE WHEN media.dateAdded > 0 THEN media.dateAdded END), 0) AS firstDate,
            SUM(CASE WHEN media.size > 0 THEN media.size ELSE 0 END) AS totalSize,
            MAX(media.relativePath) AS relativePath
        FROM cached_media AS media
        WHERE media.scope = :scope
        GROUP BY media.albumKey
        )
        SELECT albums.*, cover.uri AS coverUri, cover.mimeType AS coverMimeType
        FROM albums
        LEFT JOIN cached_media AS cover ON cover.scope = :scope AND cover.uri = (
            SELECT candidate.uri FROM cached_media AS candidate
            WHERE candidate.scope = :scope AND candidate.albumKey = albums.albumKey
            ORDER BY candidate.dateAdded DESC LIMIT 1
        )
        ORDER BY latestDate DESC
        """
    )
    abstract fun albumSummaries(scope: String): List<CachedAlbumSummary>

    @RawQuery(observedEntities = [CachedMediaEntity::class, CustomMediaOrderEntity::class])
    abstract fun pagedQuery(query: SupportSQLiteQuery): PagingSource<Int, CachedMediaEntity>

    fun pagedMedia(
        scope: String,
        albumKey: String,
        customOrderAlbumKey: String,
        query: String,
        sortMode: String,
        sortDescending: Int
    ): PagingSource<Int, CachedMediaEntity> = pagedQuery(
        CatalogPagingQuery.build(scope, albumKey, customOrderAlbumKey, query, sortMode, sortDescending != 0))

    @Query("DELETE FROM cached_media WHERE scope = :scope")
    abstract fun deleteMedia(scope: String)

    @Query("DELETE FROM cached_media WHERE scope = :scope AND albumKey = :albumKey")
    abstract fun deleteAlbumMedia(scope: String, albumKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertMedia(items: List<CachedMediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveState(state: CatalogStateEntity)

    @Query("UPDATE cached_media SET duration = :duration WHERE uri = :uri AND duration <= 0")
    abstract fun updateMediaDuration(uri: String, duration: Long): Int

    @Query("SELECT * FROM catalog_state WHERE scope = :scope LIMIT 1")
    abstract fun state(scope: String): CatalogStateEntity?

    @Query("SELECT uri FROM custom_media_order WHERE albumKey = :albumKey ORDER BY position")
    abstract fun customOrder(albumKey: String): List<String>

    @Query("SELECT * FROM custom_media_order ORDER BY albumKey, position")
    abstract fun allCustomOrders(): List<CustomMediaOrderEntity>

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
    open fun replaceAlbumMedia(scope: String, albumKey: String, items: List<CachedMediaEntity>) {
        deleteAlbumMedia(scope, albumKey)
        if (items.isNotEmpty()) insertMedia(items)
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
    version = 3,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_media ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_media_scope_albumKey_dateAdded ON cached_media(scope, albumKey, dateAdded)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_media_scope_dateAdded ON cached_media(scope, dateAdded)")
            }
        }
    }
}

object GalleryCatalogStore {
    private const val VISIBLE_SCOPE = "visible"
    private const val COMPLETE_SCOPE = "complete"
    private const val CATALOG_META_PREFS = "gallery_catalog_meta"
    private const val PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION = "catalog_dirty_after_media_action"
    private val mutationLock = Any()
    private val mutationRevision = java.util.concurrent.atomic.AtomicLong()
    private val metadataLock = Any()
    private const val PREF_CATALOG_MODEL_VERSION_PREFIX = "catalog_model_version_"
    private const val CATALOG_MODEL_VERSION = 3
    @Volatile private var visibleSnapshot: List<MediaItem> = emptyList()
    @Volatile private var completeSnapshot: List<MediaItem> = emptyList()

    fun readMedia(context: Context, includeHidden: Boolean): List<MediaItem> = synchronized(mutationLock) {
        val scope = scope(includeHidden)
        val result = GalleryDatabase.get(context).galleryDao().media(scope).map { it.toMediaItem() }
        updateSnapshot(includeHidden, result)
        result
    }

    fun readAlbumMedia(context: Context, includeHidden: Boolean, albumKey: String): List<MediaItem> =
        GalleryDatabase.get(context).galleryDao()
            .mediaForAlbum(scope(includeHidden), albumKey)
            .map { it.toMediaItem() }

    internal fun hasAlbumMedia(context: Context, includeHidden: Boolean, albumKey: String): Boolean =
        GalleryDatabase.get(context).galleryDao().hasAlbumMedia(scope(includeHidden), albumKey)

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

    internal fun invalidateSnapshots() {
        visibleSnapshot = emptyList()
        completeSnapshot = emptyList()
    }

    fun markCatalogDirty(context: Context) {
        synchronized(metadataLock) {
            mutationRevision.incrementAndGet()
            context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("${PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION}_visible", true)
                .putBoolean("${PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION}_complete", true)
                .remove(PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION)
                .apply()
        }
    }

    fun isCatalogDirty(context: Context, includeHidden: Boolean? = null): Boolean {
        val preferences = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
        return preferences.getBoolean(PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION, false) ||
            listOf(false, true).filter { includeHidden == null || it == includeHidden }.any {
                preferences.getBoolean("${PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION}_${scope(it)}", false)
            }
    }

    fun currentMutationRevision(): Long = mutationRevision.get()

    fun clearCatalogDirty(context: Context, includeHidden: Boolean? = null, expectedRevision: Long? = null) {
        synchronized(metadataLock) {
            if (expectedRevision != null && mutationRevision.get() != expectedRevision) return
            val preferences = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
            preferences.edit().apply {
                if (includeHidden != null && preferences.getBoolean(PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION, false)) {
                    putBoolean("${PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION}_${scope(!includeHidden)}", true)
                }
                remove(PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION)
                listOf(false, true).filter { includeHidden == null || it == includeHidden }.forEach {
                    remove("${PREF_CATALOG_DIRTY_AFTER_MEDIA_ACTION}_${scope(it)}")
                }
            }.apply()
        }
    }

    fun pagedMedia(
        context: Context,
        includeHidden: Boolean,
        albumKey: String?,
        query: String,
        sortMode: String,
        sortDescending: Boolean,
        config: PagingConfig
    ): Flow<PagingData<MediaItem>> {
        val dao = GalleryDatabase.get(context).galleryDao()
        val requestedAlbum = if (
            albumKey == null || albumKey == "all_media" || albumKey == VirtualAlbumRules.RECENT_KEY
        ) "__all__" else albumKey
        val customOrderAlbum = albumKey ?: "all"
        return Pager(config) {
            dao.pagedMedia(
                scope(includeHidden),
                requestedAlbum,
                customOrderAlbum,
                query.trim(),
                sortMode,
                if (sortDescending) 1 else 0
            )
        }.flow.map { page -> page.map { it.toMediaItem() } }
    }

    fun writeMedia(context: Context, items: List<MediaItem>, includeHidden: Boolean, allFilesAccess: Boolean,
        changeToken: String? = null) {
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
                .putString(generationKey(includeHidden), changeToken ?: mediaStoreChangeToken(context))
                .putInt(modelVersionKey(includeHidden), CATALOG_MODEL_VERSION)
                .apply()
            updateSnapshot(includeHidden, items)
            return
        }
        val entities = items.map { item ->
            CachedMediaEntity(
                scope, item.uri.toString(), item.id, item.name, item.mimeType, item.dateAdded,
                item.size, item.relativePath, item.albumKey, item.albumName, item.duration
            )
        }
        dao.replaceMedia(scope, entities, state)
        preferences.edit()
            .putLong(fingerprintKey(includeHidden), fingerprint)
            .putString(versionKey(includeHidden), currentMediaStoreVersion(context))
            .putString(generationKey(includeHidden), changeToken ?: mediaStoreChangeToken(context))
            .putInt(modelVersionKey(includeHidden), CATALOG_MODEL_VERSION)
            .apply()
        updateSnapshot(includeHidden, items)
    }

    private fun catalogFingerprint(items: List<MediaItem>): Long {
        var fingerprint = CatalogFingerprintRules.INITIAL
        for (item in items) {
            fingerprint = CatalogFingerprintRules.append(
                fingerprint, item.uri.toString(), item.id, item.name, item.mimeType,
                item.relativePath, item.albumKey, item.albumName, item.dateAdded, item.size, item.duration
            )
        }
        return fingerprint
    }

    private fun fingerprintKey(includeHidden: Boolean): String =
        "catalog_fingerprint_${if (includeHidden) "complete" else "visible"}"

    private fun modelVersionKey(includeHidden: Boolean): String =
        "$PREF_CATALOG_MODEL_VERSION_PREFIX${scope(includeHidden)}"

    fun customOrder(context: Context, albumKey: String): List<String> =
        GalleryDatabase.get(context).galleryDao().customOrder(albumKey)

    fun allCustomOrders(context: Context): Map<String, List<String>> =
        GalleryDatabase.get(context).galleryDao().allCustomOrders()
            .groupBy({ it.albumKey }, { it.uri })

    fun hasFreshCatalog(context: Context, includeHidden: Boolean, allFilesAccess: Boolean, maxAgeMs: Long): Boolean {
        if (isCatalogDirty(context, includeHidden)) return false
        val preferences = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
        if (preferences.getInt(modelVersionKey(includeHidden), 0) < CATALOG_MODEL_VERSION) return false
        val state = GalleryDatabase.get(context).galleryDao().state(scope(includeHidden)) ?: return false
        if (state.allFilesAccess != allFilesAccess) return false
        val age = System.currentTimeMillis() - state.scannedAt
        val generation = mediaStoreChangeToken(context)
        if (generation.isNotEmpty()) {
            val unchanged = generation == preferences.getString(generationKey(includeHidden), "")
            return unchanged && (!includeHidden || age <= maxAgeMs)
        }
        if (includeHidden) return age <= maxAgeMs
        val currentVersion = currentMediaStoreVersion(context)
        val storedVersion = preferences.getString(versionKey(false), "")
            .orEmpty()
        return if (currentVersion.isNotEmpty() && storedVersion.isNotEmpty()) {
            currentVersion == storedVersion && age <= maxAgeMs
        } else {
            age <= maxAgeMs
        }
    }

    fun writeAlbumMedia(context: Context, items: List<MediaItem>, includeHidden: Boolean, albumKey: String) {
        synchronized(mutationLock) {
            val dao = GalleryDatabase.get(context).galleryDao()
            val entities = items.map { item -> CachedMediaEntity(scope(includeHidden), item.uri.toString(), item.id,
                item.name, item.mimeType, item.dateAdded, item.size, item.relativePath,
                item.albumKey, item.albumName, item.duration) }
            if (dao.mediaForAlbum(scope(includeHidden), albumKey).associateBy { it.uri } ==
                entities.associateBy { it.uri }) return
            // A targeted update is not proof that the entire device is reconciled.
            mutationRevision.incrementAndGet()
            dao.replaceAlbumMedia(scope(includeHidden), albumKey, entities)
            val previousSnapshot = snapshot(includeHidden)
            if (previousSnapshot.isEmpty()) {
                // An invalidated/cold snapshot is not a complete empty catalog.
                readMedia(context, includeHidden)
            } else {
                updateSnapshot(includeHidden, previousSnapshot.filterNot { it.albumKey == albumKey } + items)
            }
            context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE).edit {
                remove(fingerprintKey(includeHidden))
            }
        }
    }

    internal fun writeMediaIfCurrent(context: Context, items: List<MediaItem>, includeHidden: Boolean,
        allFilesAccess: Boolean, expectedRevision: Long, changeToken: String? = null): Boolean = synchronized(mutationLock) {
        if (mutationRevision.get() != expectedRevision) return@synchronized false
        writeMedia(context, items, includeHidden, allFilesAccess, changeToken)
        if (mutationRevision.get() != expectedRevision) return@synchronized false
        clearCatalogDirty(context, includeHidden, expectedRevision)
        true
    }

    fun saveResolvedDuration(context: Context, uri: String, duration: Long) {
        if (duration <= 0L) return
        synchronized(mutationLock) {
            if (GalleryDatabase.get(context).galleryDao().updateMediaDuration(uri, duration) == 0) return
            // Invalidate in O(1), rather than mapping the full catalog for every
            // resolved video. A subsequent read reloads the updated Room rows.
            // The revision also prevents an older scan from overwriting them.
            mutationRevision.incrementAndGet()
            visibleSnapshot = emptyList()
            completeSnapshot = emptyList()
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

    private fun generationKey(includeHidden: Boolean) = "media_store_generation_${scope(includeHidden)}"

    internal fun mediaStoreChangeToken(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.getExternalVolumeNames(context).sorted().joinToString("|") { volume ->
                    "$volume:${MediaStore.getVersion(context, volume)}:${MediaStore.getGeneration(context, volume)}"
                }
            }.getOrDefault("")
        } else ""

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
        mediaId, Uri.parse(uri), name, mimeType, dateAdded, size, relativePath, albumKey, albumName, duration
    )
}
