package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * "Criar nova pasta": one centred window in the app's dialog style. It shows the storage choice
 * side by side, the current path with a step back, the folders inside it, the name field and
 * Cancelar/Criar. Opened from "Mover" it only offers the internal storage, where moves go.
 */
class FolderCreationMenu(
    private val activity: Activity,
    private val forMove: Boolean = false,
    private val onFolderCreated: (File) -> Unit = {}
) {
    private var creating = false
    private data class StorageLocation(val label: String, val root: File, val removable: Boolean)

    fun show() {
        folderWorker.execute {
            val locations = storageLocations()
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && locations.isNotEmpty()) showWindow(locations)
            }
        }
    }

    private fun showWindow(locations: List<StorageLocation>) {
        lateinit var dialog: AlertDialog
        var location = locations.first()
        // From "Mover" it starts in Pictures, the usual home for new albums.
        var currentDirectory = if (forMove) {
            File(location.root, Environment.DIRECTORY_PICTURES).takeIf { it.isDirectory } ?: location.root
        } else location.root
        var requestVersion = 0
        val folders = ArrayList<File>()

        val body = Ui.themedDialogBody(activity, activity.getString(R.string.action_create_folder), null)

        // Storage, side by side; the SD card stays visible but disabled when none is mounted.
        val storageRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        val chips = ArrayList<Pair<StorageLocation?, TextView>>()
        lateinit var refresh: () -> Unit
        fun styleChips() {
            chips.forEach { (chipLocation, chip) ->
                val selected = chipLocation == location
                chip.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                chip.background = pill(if (selected) Ui.menuHighlightSurface(activity) else Ui.menuOptionSurface(activity))
                chip.isSelected = selected
            }
        }
        fun addChip(chipLocation: StorageLocation?, label: String) {
            val chip = TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Ui.menuText(activity))
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                isFocusable = true
                // Long storage names ("Armazenamento interno") shrink to fit instead of being cut.
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                )
                if (chipLocation == null) {
                    alpha = 0.45f
                    contentDescription = "$label: ${activity.getString(R.string.folder_no_sd_card)}"
                    setOnClickListener { Ui.toast(activity, activity.getString(R.string.folder_no_sd_card)) }
                } else {
                    setOnClickListener {
                        if (location == chipLocation) return@setOnClickListener
                        location = chipLocation
                        currentDirectory = chipLocation.root
                        styleChips()
                        refresh()
                    }
                }
            }
            chips.add(chipLocation to chip)
            storageRow.addView(chip, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (storageRow.childCount > 0) marginStart = dp(8)
            })
        }
        locations.forEach { addChip(it, it.label) }
        if (locations.none { it.removable }) addChip(null, activity.getString(R.string.folder_sd_card))
        styleChips()
        body.addView(storageRow, matchWrap())

        // Current path with a step back to the parent folder.
        val back = ImageView(activity).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Ui.menuText(activity))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = activity.getString(R.string.folder_previous)
            background = ripple(Color.TRANSPARENT, 18)
            tag = "folder_creation_up"
        }
        val path = TextView(activity).apply {
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Ui.menuText(activity))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(6), 0, 0, 0)
            tag = "folder_creation_path"
        }
        body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), dp(2))
            addView(back, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(path, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, matchWrap())

        // Folders inside the current one.
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = folders.size
            override fun getItem(position: Int): File = folders[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: folderRow()
                (row.getChildAt(1) as TextView).text = getItem(position).name
                return row
            }
        }
        val empty = TextView(activity).apply {
            text = activity.getString(R.string.folder_empty)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Ui.menuText(activity))
            alpha = 0.6f
            visibility = View.GONE
        }
        val list = ListView(activity).apply {
            divider = null
            dividerHeight = dp(3)
            cacheColorHint = Color.TRANSPARENT
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipToPadding = false
            this.adapter = adapter
            tag = "folder_creation_list"
            setOnItemClickListener { _, _, position, _ ->
                currentDirectory = folders[position]
                refresh()
            }
        }
        val listBox = FrameLayout(activity)
        body.addView(listBox.apply {
            background = Ui.rounded(Ui.menuElevatedSurface(activity), 14, activity, Ui.menuBorder(activity), 1)
            clipToOutline = true
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(10)
        })

        // Name of the new folder.
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = activity.getString(R.string.folder_name)
            textSize = 16f
            setTextColor(Ui.menuText(activity))
            setHintTextColor((Ui.menuText(activity) and 0x00FFFFFF) or 0x99000000.toInt())
            background = Ui.rounded(Ui.menuElevatedSurface(activity), 12, activity, Ui.menuBorder(activity), 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            tag = "folder_creation_name"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            // The keyboard covers the lower half: while the name is typed the window wraps a short
            // list so Cancelar and Criar stay visible, then goes back to the tall list.
            setOnFocusChangeListener { _, focused ->
                (listBox.layoutParams as LinearLayout.LayoutParams).apply {
                    height = if (focused) dp(132) else 0
                    weight = if (focused) 0f else 1f
                    listBox.layoutParams = this
                }
                dialog.window?.let { window ->
                    window.attributes = window.attributes.apply {
                        height = if (focused) ViewGroup.LayoutParams.WRAP_CONTENT
                            else (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
                    }
                }
            }
        }
        body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(4)
        })
        lateinit var confirm: () -> Unit
        body.addView(Ui.themedActionPair(
            activity,
            activity.getString(R.string.action_cancel),
            activity.getString(R.string.folder_create),
            onFirst = { dialog.dismiss() },
            onSecond = {
                confirm()
            }
        ))
        confirm = {
            // Android keeps media out of the storage root, so a destination for "Mover" needs a parent.
            if (forMove && !location.removable && sameFile(currentDirectory, location.root)) {
                Ui.toast(activity, activity.getString(R.string.folder_pick_parent))
            } else {
                createFolder(currentDirectory, input.text.toString()) { dialog.dismiss() }
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { confirm(); true } else false
        }

        refresh = {
            val directory = currentDirectory
            val atRoot = sameFile(directory, location.root)
            path.text = displayPath(location, directory)
            back.isEnabled = !atRoot
            back.alpha = if (atRoot) 0.3f else 1f
            val version = ++requestVersion
            val current = location
            folderWorker.execute {
                val next = visibleFolders(directory, current)
                activity.runOnUiThread {
                    if (version == requestVersion && dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                        folders.clear()
                        folders.addAll(next)
                        adapter.notifyDataSetChanged()
                        list.setSelection(0)
                        empty.visibility = if (next.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
        back.setOnClickListener {
            currentDirectory = currentDirectory.parentFile?.takeIf { isInside(location.root, it) } ?: location.root
            refresh()
        }

        dialog = AlertDialog.Builder(activity).setView(body).create()
        dialog.setOnDismissListener { requestVersion++ }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Tall window: the folder list takes the room the header does not use.
        Ui.showCenteredPanel(dialog, fullHeight = true)
        refresh()
    }

    private fun folderRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50)
        background = ripple(Ui.menuOptionSurface(activity), 10)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(Ui.menuText(activity))
            alpha = 0.82f
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(activity).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Ui.menuText(activity))
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun createFolder(parent: File, rawName: String, onCreated: () -> Unit) {
        if (creating) return
        val name = MediaActions.cleanFolderName(rawName)
        if (name.isBlank()) {
            Ui.toast(activity, activity.getString(R.string.folder_invalid_name))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(activity)) {
            Ui.showConfirmationDialog(
                activity,
                activity.getString(R.string.access_full_title),
                activity.getString(R.string.folder_access_explanation),
                activity.getString(R.string.action_allow)
            ) { MediaActions.requestAllFilesAccess(activity) }
            return
        }
        val target = File(parent, name)
        creating = true
        folderWorker.execute {
            val result = runCatching { if (target.exists()) 0 else if (MediaActions.createFolder(activity, target)) 1 else -1 }.getOrDefault(-1)
            activity.runOnUiThread {
                creating = false
                if (!activity.isFinishing && !activity.isDestroyed) {
                    when (result) {
                        1 -> {
                            Ui.toast(activity, activity.getString(R.string.folder_created_at, displayParent(parent)))
                            onCreated()
                            onFolderCreated(target)
                        }
                        0 -> Ui.toast(activity, activity.getString(R.string.folder_exists_at_location))
                        else -> Ui.toast(activity, activity.getString(R.string.folder_create_at_location_failed))
                    }
                }
            }
        }
    }

    private fun storageLocations(): List<StorageLocation> {
        val locations = ArrayList<StorageLocation>()
        val knownPaths = HashSet<String>()
        val internal = Environment.getExternalStorageDirectory()
        addLocation(locations, knownPaths, activity.getString(R.string.album_internal_storage), internal, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = activity.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            var cardNumber = 0
            manager.storageVolumes.forEach { volume ->
                if (volume.isPrimary || (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY)) {
                    return@forEach
                }
                val directory = volume.directory ?: return@forEach
                cardNumber++
                val label = if (cardNumber == 1) {
                    activity.getString(R.string.folder_sd_card)
                } else {
                    activity.getString(R.string.folder_sd_card_numbered, cardNumber)
                }
                addLocation(locations, knownPaths, label, directory, true)
            }
        } else {
            activity.getExternalFilesDirs(null).drop(1).forEachIndexed { index, appDirectory ->
                val root = storageRootFromAppDirectory(appDirectory) ?: return@forEachIndexed
                val label = if (index == 0) {
                    activity.getString(R.string.folder_sd_card)
                } else {
                    activity.getString(R.string.folder_sd_card_numbered, index + 1)
                }
                addLocation(locations, knownPaths, label, root, true)
            }
        }
        return locations
    }

    private fun addLocation(
        locations: MutableList<StorageLocation>,
        knownPaths: MutableSet<String>,
        label: String,
        root: File,
        removable: Boolean
    ) {
        val path = canonicalPath(root)
        if (knownPaths.add(path)) locations.add(StorageLocation(label, root, removable))
    }

    private fun visibleFolders(directory: File, location: StorageLocation): List<File> {
        val listed = runCatching {
            directory.listFiles()
                ?.filter { it.isDirectory && !it.isHidden && isInside(location.root, it) }
                ?.sortedWith { first, second -> first.name.compareTo(second.name, ignoreCase = true) }
        }.getOrNull()
        if (!listed.isNullOrEmpty()) return listed
        if (location.removable || !sameFile(directory, location.root)) return emptyList()
        return listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PICTURES
        ).map { File(location.root, it) }.filter { it.exists() && it.isDirectory }
    }

    private fun storageRootFromAppDirectory(directory: File?): File? {
        if (directory == null) return null
        val normalized = directory.absolutePath.replace('\\', '/')
        val marker = normalized.indexOf("/Android/")
        return if (marker > 0) File(normalized.substring(0, marker)) else null
    }

    private fun displayPath(location: StorageLocation, directory: File): String {
        val root = canonicalPath(location.root).trimEnd(File.separatorChar)
        val current = canonicalPath(directory)
        val relative = current.removePrefix(root).trim(File.separatorChar)
        return if (relative.isBlank()) {
            activity.getString(R.string.folder_storage_root)
        } else {
            relative.replace(File.separator, " › ")
        }
    }

    private fun displayParent(parent: File): String = parent.name.ifBlank { parent.absolutePath }

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = canonicalPath(root).trimEnd(File.separatorChar)
        val candidatePath = canonicalPath(candidate)
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun sameFile(first: File, second: File): Boolean = canonicalPath(first) == canonicalPath(second)

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun dp(value: Int): Int = Ui.dp(activity, value)

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun pill(color: Int) = ripple(color, 20, Ui.menuBorder(activity))

    private fun ripple(color: Int, radius: Int, border: Int? = null) = RippleDrawable(
        ColorStateList.valueOf((Ui.menuText(activity) and 0x00FFFFFF) or 0x22000000),
        if (border != null) Ui.rounded(color, radius, activity, border, 1) else Ui.rounded(color, radius, activity),
        Ui.rounded(Color.WHITE, radius, activity)
    )

    private companion object { val folderWorker = Executors.newSingleThreadExecutor() }
}
