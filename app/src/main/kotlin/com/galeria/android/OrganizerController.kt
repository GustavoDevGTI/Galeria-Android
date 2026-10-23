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
    private val move: (Context, MediaItem, String, String) -> MediaItem? = OrganizerMediaMover::move,
    private val discard: (Context, MediaItem, Boolean) -> Boolean = OrganizerMediaMover::discard) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var onChange: (() -> Unit)? = null
    var onSettled: (() -> Unit)? = null
    var albums: List<AlbumItem> = emptyList(); private set
    var source: AlbumItem? = null; private set
    private val slots = ArrayList<AlbumItem>()
    val destinations: List<AlbumItem> get() = slots
    var media = OrganizerQueue<MediaItem>(emptyList()); private set
    var stage = 0; private set // source, destinations, organizing
    var loading = false; private set
    var message: String? = null
    var pending = 0; private set
    var reserveOpen = false
    var drawerOpen = true
    var saving = false; private set
    private var generation = 0
    private data class Action(val ticket: OrganizerQueue.Ticket<MediaItem>, val album: AlbumItem?)
    private val history = ArrayList<Action>()
    private val sent = HashMap<String, Int>()
    private var committedAlbum: String? = null
    fun sentCount(albumKey: String): Int = (sent[albumKey] ?: 0) + history.count { it.album?.key == albumKey }
    val unsavedCount: Int get() = history.count { it.album != null }
    val hasChanges: Boolean get() = unsavedCount > 0
    val availableAlbums: List<AlbumItem> get() = albums.filter { album ->
        album.key != source?.key && destinations.none { it.key == album.key }
    }
    val lastAlbum: String? get() = history.lastOrNull { it.album != null }?.album?.key ?: committedAlbum
    val canUndo: Boolean get() = !saving && history.isNotEmpty()

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

    companion object {
        const val TRASH_SLOT = 4
        const val TRASH_KEY = "@organizer.trash"
        fun emptyAlbum(index: Int) = AlbumItem("@organizer.empty.$index", "+", 0, null, 0, 0, 0, "")
        fun isEmpty(album: AlbumItem) = album.key.startsWith("@organizer.empty.")
    }

    fun chooseSource(album: AlbumItem) {
        if (saving || hasChanges) return
        source = album
        val savedSlots = prefs.getString("organizer_slots_${album.key}", null)
        val previous = readArray(prefs.getString("organizer_targets_${album.key}", null)).take(7).iterator()
        val saved = if (savedSlots != null) readArray(savedSlots) else (0..7).map { index ->
            if (index == TRASH_SLOT) TRASH_KEY else if (previous.hasNext()) previous.next() else emptyAlbum(index).key
        }
        slots.clear()
        repeat(8) { index ->
            slots.add(if (index == TRASH_SLOT) AlbumItem(TRASH_KEY, "Lixeira", 0, null, 0, 0, 0, "")
                else albums.firstOrNull { it.key == saved.getOrNull(index) && it.key != album.key } ?: emptyAlbum(index))
        }
        stage = 1; notifyChange()
    }

    fun place(album: AlbumItem, index: Int) {
        if (saving || index !in 0..7 || index == TRASH_SLOT || album.key == source?.key || albums.none { it.key == album.key }) return
        val old = destinations.indexOfFirst { it.key == album.key }
        if (old >= 0) swap(old, index)
        else { slots[index] = album; saveOrder(); notifyChange() }
    }

    fun removeDestination(index: Int) {
        if (saving || index !in destinations.indices || index == TRASH_SLOT) return
        slots[index] = emptyAlbum(index); saveOrder(); notifyChange()
    }

    fun toggleDestination(album: AlbumItem) {
        val old = destinations.indexOfFirst { it.key == album.key }
        if (old >= 0) removeDestination(old)
        else destinations.indexOfFirst { isEmpty(it) }.takeIf { it >= 0 }?.let { place(album, it) }
    }

    fun begin() {
        val origin = source ?: return
        if (loading || saving) return
        saveOrder(); loading = true; notifyChange()
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
                    history.clear(); sent.clear(); committedAlbum = null; stage = 2
                }.onFailure { message = app.getString(R.string.album_load_failed) }
                notifyChange()
            }
        }
    }

    fun swap(from: Int, to: Int) {
        if (saving || from == TRASH_SLOT || to == TRASH_SLOT) return
        if (OrganizerRules.swap(slots, from, to)) { saveOrder(); notifyChange() }
    }

    fun reserve() {
        if (saving) return
        val ticket = media.reserve() ?: return
        history.add(Action(ticket, null)); saveReserve(); notifyChange()
    }

    fun send(item: MediaItem, fromReserve: Boolean, index: Int): Boolean {
        if (saving) return false
        val album = destinations.getOrNull(index)?.takeUnless(::isEmpty) ?: return false
        val ticket = media.take(item, fromReserve) ?: return false
        history.add(Action(ticket, album)); notifyChange()
        return true
    }

    fun undo() {
        if (!canUndo) return
        val action = history.removeAt(history.lastIndex)
        if (action.album == null) media.reserved.remove(action.ticket.item)
        media.restore(action.ticket); saveReserve(); notifyChange()
    }

    /** Commit a snapshot. Destinations are captured in each action, never looked up by slot. */
    fun save() {
        if (saving || !hasChanges) return
        val actions = history.filter { it.album != null }
        val permanently = !TrashPreferences.isEnabled(app)
        saving = true; pending = actions.size; notifyChange()
        worker.execute {
            actions.forEach { action ->
                val album = requireNotNull(action.album)
                val success = runCatching {
                    if (album.key == TRASH_KEY) discard(app, action.ticket.item, permanently)
                    else move(app, action.ticket.item, album.path.ifBlank { album.key }, action.ticket.item.name) != null
                }.getOrDefault(false)
                main.post {
                    pending--
                    if (success) {
                        history.remove(action)
                        // A reservation preceding this action cannot restore a committed file.
                        history.removeAll { it.album == null && it.ticket.item == action.ticket.item }
                        sent[album.key] = (sent[album.key] ?: 0) + 1
                        committedAlbum = album.key
                    }
                    if (pending == 0) {
                        saving = false
                        message = if (hasChanges) "Algumas mídias não foram salvas. Tente novamente ou desfaça as marcações." else "Alterações salvas."
                    }
                    saveReserve(); notifyChange()
                }
            }
        }
    }

    fun discardChanges() {
        if (saving) return
        while (history.isNotEmpty()) undo()
    }

    fun chooseAgain(): Boolean {
        if (saving || hasChanges) return false
        generation++; stage = 0; notifyChange(); return true
    }
    fun close() { onChange = null; worker.shutdown() } // already queued file operations finish safely
    private fun saveOrder() { prefs.edit { putString("organizer_slots_${source?.key}", JSONArray(destinations.map { it.key }).toString()) } }
    private fun saveReserve() {
        val pendingReserved = history.filter { it.album != null && it.ticket.reserved }.map { it.ticket.item }
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
