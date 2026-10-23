package com.galeria.android

import android.content.Context
import android.annotation.SuppressLint
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import androidx.core.content.edit

/** Retained across rotation. Only this module owns queue mutation and serial file work. */
internal class OrganizerController(context: Context,
    private val move: (Context, MediaItem, String, String) -> MediaItem? = OrganizerMediaMover::move) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var onChange: (() -> Unit)? = null
    var onSettled: (() -> Unit)? = null
    var albums: List<AlbumItem> = emptyList(); private set
    var source: AlbumItem? = null; private set
    val destinations = ArrayList<AlbumItem>()
    var media = OrganizerQueue<MediaItem>(emptyList()); private set
    var stage = 0; private set // source, destinations, organizing
    var loading = false; private set
    var message: String? = null
    var pending = 0; private set
    var reserveOpen = false
    private var generation = 0
    private data class Action(var ticket: OrganizerQueue.Ticket<MediaItem>, val album: AlbumItem?,
        var result: MediaItem? = null, var working: Boolean = false)
    private val history = ArrayList<Action>()
    private val sent = HashMap<String, Int>()
    fun sentCount(albumKey: String): Int = sent[albumKey] ?: 0
    val lastAlbum: String? get() = history.lastOrNull { it.album != null }?.album?.key
    val canUndo: Boolean get() = history.lastOrNull()?.working == false

    fun loadAlbums(exposed: List<String>?, includeHidden: Boolean, preferred: String?) {
        loading = true; notifyChange()
        worker.execute {
            val result = runCatching {
                val available = MediaStoreRepository.loadTargetAlbums(app, exposed, emptySet(), includeHidden)
                val markers = AutomaticHiddenAlbums.keys(app, available,
                    HiddenDirectoryMarkers(Environment.getExternalStorageDirectory()))
                val visible = AlbumTargetRules.orderedTargets(available, exposed,
                    prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty() + markers,
                    emptySet(), AlbumRules.SORT_NAME, false)
                    .filter { MediaStoreRepository.isPhysicalAlbum(it.key) }
                val root = Environment.getExternalStorageDirectory().canonicalFile
                val hidden = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty()
                val remembered = prefs.getStringSet("organizer_created_folders", emptySet()).orEmpty()
                    .filter { key -> key !in hidden && (key in exposed.orEmpty() || (!AlbumRules.isHidden(key, key) && !HiddenDirectoryMarkers(root).containsNomedia(key))) }
                    .mapNotNull { key ->
                        val folder = File(root, key).canonicalFile
                        if (visible.any { it.key == key } || !folder.isDirectory || !folder.path.startsWith(root.path + File.separator)) null
                        else AlbumItem(key, folder.name, 0, null, 0, 0, 0, key)
                    }
                visible + remembered
            }
            main.post {
                loading = false
                result.onSuccess {
                    albums = it
                    // Remember a useful starting choice, but keep the explicit source selection step.
                    source = it.firstOrNull { album -> album.key == preferred }
                }.onFailure { message = app.getString(R.string.album_load_failed) }
                notifyChange()
            }
        }
    }

    fun addFolder(album: AlbumItem) {
        if (albums.none { it.key == album.key }) albums = albums + album
        prefs.edit { putStringSet("organizer_created_folders",
            prefs.getStringSet("organizer_created_folders", emptySet()).orEmpty() + album.key) }
        notifyChange()
    }

    fun chooseSource(album: AlbumItem) {
        source = album
        val saved = readArray(prefs.getString("organizer_targets_${album.key}", null))
        destinations.clear()
        saved.take(OrganizerRules.MAX_DESTINATIONS).mapNotNullTo(destinations) { key -> albums.firstOrNull { it.key == key && it.key != album.key } }
        stage = 1; notifyChange()
    }

    fun toggleDestination(album: AlbumItem) {
        if (album.key == source?.key) return
        val old = destinations.indexOfFirst { it.key == album.key }
        if (old >= 0) destinations.removeAt(old)
        else if (destinations.size < OrganizerRules.MAX_DESTINATIONS) destinations.add(album)
        notifyChange()
    }

    fun begin() {
        val origin = source ?: return
        if (loading || !OrganizerRules.validSelection(origin.key, destinations.map { it.key })) return
        saveOrder()
        loading = true; notifyChange()
        val request = ++generation
        worker.execute {
            val result = runCatching { MediaStoreRepository.queryAlbumMedia(app, origin.key, true) }
            main.post {
                if (request != generation) return@post
                loading = false
                result.onSuccess { items ->
                    val reserved = readArray(prefs.getString("organizer_reserve_${origin.key}", null))
                    val byIdentity = items.associateBy(::identity)
                    val held = reserved.mapNotNull(byIdentity::get)
                    media = OrganizerQueue(items.filter { identity(it) !in reserved }, held)
                    history.clear(); sent.clear(); stage = 2
                }.onFailure { message = app.getString(R.string.album_load_failed) }
                notifyChange()
            }
        }
    }

    fun swap(from: Int, to: Int) {
        if (OrganizerRules.swap(destinations, from, to)) { saveOrder(); notifyChange() }
    }

    fun reserve() {
        val ticket = media.reserve() ?: return
        history.add(Action(ticket, null)); saveReserve(); notifyChange()
    }

    fun send(item: MediaItem, fromReserve: Boolean, index: Int): Boolean {
        val album = destinations.getOrNull(index) ?: return false
        val ticket = media.take(item, fromReserve) ?: return false
        val action = Action(ticket, album, working = true)
        history.add(action); pending++; saveReserve(); notifyChange()
        worker.execute {
            val result = runCatching { move(app, item, album.path.ifBlank { album.key }, item.name) }.getOrNull()
            main.post {
                pending--; action.working = false
                if (result == null) {
                    media.restore(ticket); history.remove(action)
                    message = app.getString(R.string.organizer_move_failed)
                } else {
                    action.result = result
                    sent[album.key] = sentCount(album.key) + 1
                }
                saveReserve(); notifyChange()
            }
        }
        return true
    }

    fun undo() {
        val action = history.lastOrNull()?.takeUnless { it.working } ?: return
        if (action.album == null) {
            media.reserved.remove(action.ticket.item); media.restore(action.ticket)
            history.remove(action); saveReserve(); notifyChange(); return
        }
        val moved = action.result ?: return
        action.working = true; pending++; notifyChange()
        worker.execute {
            val result = runCatching { move(app, moved,
                action.ticket.item.relativePath, action.ticket.item.name) }.getOrNull()
            main.post {
                pending--; action.working = false
                if (result != null) {
                    media.restore(action.ticket, result); history.remove(action)
                    sent[action.album.key] = (sentCount(action.album.key) - 1).coerceAtLeast(0)
                    // An earlier reservation must undo the restored identity, which can
                    // have a new URI/name after a direct move or a filename collision.
                    history.filter { it.ticket.item === action.ticket.item }.forEach {
                        it.ticket = it.ticket.copy(item = result)
                    }
                } else message = app.getString(R.string.organizer_undo_failed)
                saveReserve(); notifyChange()
            }
        }
    }

    fun chooseAgain(): Boolean {
        if (pending != 0) return false
        generation++; stage = 0; notifyChange(); return true
    }

    fun close() { onChange = null; worker.shutdown() } // already queued file operations finish safely
    private fun saveOrder() { prefs.edit { putString("organizer_targets_${source?.key}", JSONArray(destinations.map { it.key }).toString()) } }
    private fun saveReserve() {
        val pendingReserved = history.filter { it.working && it.ticket.reserved }.map { it.ticket.item }
        prefs.edit { putString("organizer_reserve_${source?.key}", JSONArray((media.reserved + pendingReserved).map(::identity).distinct()).toString()) }
    }
    private fun notifyChange() { onChange?.invoke(); if (pending == 0) onSettled?.invoke() }
    private fun identity(item: MediaItem) = item.relativePath + item.name
    private fun readArray(value: String?): List<String> = runCatching {
        val array = JSONArray(value ?: "[]"); (0 until array.length()).map(array::getString).distinct()
    }.getOrDefault(emptyList())
}

/** Reopening while files are still moving resumes the same queue, avoiding two
 * screens dispatching the same source item. No Activity is retained here. */
internal object OrganizerSessions {
    @SuppressLint("StaticFieldLeak") // Controller stores applicationContext; Activity callbacks detach in onDestroy.
    private var current: OrganizerController? = null
    private var attached = false
    fun acquire(context: Context): OrganizerController {
        attached = true
        return current ?: OrganizerController(context).also { session ->
            current = session
            session.onSettled = { if (current === session && !attached) release(session) }
        }
    }
    fun release(session: OrganizerController) {
        if (current !== session) return
        attached = false; session.onChange = null
        if (session.pending == 0) { session.onSettled = null; session.close(); current = null }
    }
}
