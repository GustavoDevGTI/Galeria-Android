package com.galeria.android

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.Normalizer
import java.util.Locale

/** Text read from one photo, for the search. The signature tells when the photo changed. */
@Entity(tableName = "media_text")
data class MediaTextEntity(
    @PrimaryKey val uri: String,
    val signature: String,
    val text: String,
    val indexedAt: Long
)

data class MediaTextSignature(val uri: String, val signature: String)

@Dao
abstract class MediaTextDao {
    @Query("SELECT uri, signature FROM media_text")
    abstract fun signatures(): List<MediaTextSignature>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insert(items: List<MediaTextEntity>)

    @Query("DELETE FROM media_text WHERE uri IN (:uris)")
    abstract fun delete(uris: List<String>)

    /** [pattern] is a LIKE pattern built by [MediaTextRules.likePattern]. */
    @Query("SELECT uri FROM media_text WHERE text LIKE :pattern ESCAPE '\\'")
    abstract fun matching(pattern: String): List<String>

    @Query("SELECT text FROM media_text WHERE uri = :uri")
    abstract fun text(uri: String): String?

    @Query("SELECT COUNT(*) FROM media_text")
    abstract fun count(): Int

    /** Photos of the visible catalog the index reads, counted without loading the catalog. */
    @Query("SELECT COUNT(*) FROM cached_media WHERE scope = 'visible' AND mimeType LIKE 'image/%' AND mimeType != 'image/svg+xml'")
    abstract fun readableImages(): Int

    @Query("DELETE FROM media_text")
    abstract fun clear()
}

/** Text rules shared by the index and the search: case, accents and spacing do not matter. */
internal object MediaTextRules {
    const val MIN_TERM = 2

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Words of the search, each of at least [MIN_TERM] letters; every one must appear in the photo. */
    fun terms(query: String): List<String> =
        normalize(query).split(' ').filter { it.length >= MIN_TERM }.distinct()

    fun likePattern(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    fun signature(dateAdded: Long, size: Long): String = "$dateAdded:$size"

    /** Photos to read: new or changed ones, newest first. */
    fun <T> pending(items: List<T>, known: Map<String, String>, uriOf: (T) -> String, signatureOf: (T) -> String): List<T> =
        items.filter { known[uriOf(it)] != signatureOf(it) }

    /** Entries of photos that no longer exist. */
    fun removed(known: Set<String>, current: Set<String>): List<String> = known.filterNot(current::contains)
}

/** Reads the index; searching all words of a query. */
internal object MediaTextSearch {
    /** Canonical keys (MediaIdentityRules) of the photos whose text has every word of [query]. */
    fun matchingUris(context: Context, query: String): Set<String> {
        val terms = MediaTextRules.terms(query)
        if (terms.isEmpty()) return emptySet()
        val dao = GalleryDatabase.get(context).mediaTextDao()
        var result: Set<String>? = null
        for (term in terms) {
            val found = dao.matching(MediaTextRules.likePattern(term)).mapTo(HashSet(), MediaIdentityRules::canonicalKey)
            result = result?.intersect(found) ?: found
            if (result.isEmpty()) break
        }
        return result.orEmpty()
    }

    /** Search results: photos of the visible catalog whose text has every word, newest first. */
    fun results(context: Context, query: String): List<MediaItem> {
        val uris = matchingUris(context, query)
        if (uris.isEmpty()) return emptyList()
        val secure = SecurityStore(context).secureFolderKeys()
        return GalleryCatalogStore.readMedia(context, false)
            .filter { MediaIdentityRules.canonicalKey(it.uri.toString()) in uris }
            // Folders protected by their own password never show up in the search.
            .filterNot { SecurityRules.insideSecureFolder(it.albumKey, secure) }
            .sortedByDescending { it.dateAdded }
    }

    fun progress(context: Context): Pair<Int, Int> {
        val dao = GalleryDatabase.get(context).mediaTextDao()
        val indexed = dao.count()
        val total = dao.readableImages()
        return indexed.coerceAtMost(total) to total
    }
}

/** "Pesquisar no texto das fotos": on by default, reading in the background at any time. */
internal object MediaTextSettings {
    const val ENABLED = "text_index_enabled"

    fun enabled(context: Context): Boolean = GalleryPreferences.prefs(context).getBoolean(ENABLED, true)
}

/**
 * Keeps the index of the photos' text: removes photos that no longer exist and reads new or
 * changed ones, newest first, in rounds of a few minutes that continue until everything is read.
 */
class MediaTextIndexWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!MediaTextSettings.enabled(context)) return Result.success()
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val morePending = MediaTextIndexer.indexPending(context, ROUND_MS) { isStopped || !MediaTextSettings.enabled(context) }
        if (morePending && !isStopped && MediaTextSettings.enabled(context)) MediaTextIndexScheduler.continueLater(context)
        return Result.success()
    }

    private companion object {
        const val ROUND_MS = 8 * 60 * 1000L
    }
}

/** One reading round over the visible catalog; returns whether photos were left for the next round. */
internal object MediaTextIndexer {
    private const val BATCH = 20

    /** [only] (canonical keys) limits the round to some photos, used by tests; removed photos always leave the index. */
    fun indexPending(context: Context, budgetMs: Long, only: Set<String>? = null, shouldStop: () -> Boolean): Boolean {
        val started = System.currentTimeMillis()
        val dao = GalleryDatabase.get(context).mediaTextDao()
        val images = GalleryCatalogStore.readMedia(context, false)
            .filter { it.isImage() && !it.mimeType.equals("image/svg+xml", true) }
            .sortedByDescending { it.dateAdded }
        val known = dao.signatures().associate { it.uri to it.signature }
        MediaTextRules.removed(known.keys, images.mapTo(HashSet()) { it.uri.toString() })
            .chunked(500).forEach(dao::delete)
        val pending = MediaTextRules.pending(images, known, { it.uri.toString() }) {
            MediaTextRules.signature(it.dateAdded, it.size)
        }.filter { only == null || MediaIdentityRules.canonicalKey(it.uri.toString()) in only }
        val batch = ArrayList<MediaTextEntity>()
        var done = 0
        for (item in pending) {
            if (shouldStop() || System.currentTimeMillis() - started > budgetMs) break
            // A photo that cannot be read is stored empty, so it is not retried on every round.
            val text = runCatching { ImageTextRecognition.recognizeForIndex(context, item.uri) }
                .onFailure { Log.w("MediaTextIndex", "Não foi possível ler ${item.uri}", it) }
                .getOrDefault("")
            batch.add(MediaTextEntity(item.uri.toString(), MediaTextRules.signature(item.dateAdded, item.size),
                MediaTextRules.normalize(text), System.currentTimeMillis()))
            done++
            if (batch.size >= BATCH) { dao.insert(batch); batch.clear() }
        }
        if (batch.isNotEmpty()) dao.insert(batch)
        return done < pending.size
    }
}

object MediaTextIndexScheduler {
    private const val NAME = "media_text_index"

    /** Starts reading if it is not running yet (first opening, new photos, back to the app). */
    fun schedule(context: Context) {
        if (!MediaTextSettings.enabled(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaTextIndexWorker>().build())
    }

    internal fun continueLater(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MediaTextIndexWorker>().build())
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
