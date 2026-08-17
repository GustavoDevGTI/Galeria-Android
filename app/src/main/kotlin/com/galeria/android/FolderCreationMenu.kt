package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

class FolderCreationMenu(
    private val activity: Activity,
    private val onFolderCreated: (File) -> Unit = {}
) {
    private data class StorageLocation(
        val label: String,
        val root: File,
        val removable: Boolean
    )

    fun show() {
        showStorageChoices()
    }

    private fun showStorageChoices() {
        lateinit var dialog: AlertDialog
        val locations = storageLocations()
        val panel = panel().apply {
            addView(title("Criar nova pasta"))
            addView(description("Escolha onde a nova pasta será criada."))
            locations.forEach { location ->
                addView(storageRow(location.label, location.root.absolutePath, true) {
                    dialog.dismiss()
                    openAfterDismiss { showFolderBrowser(location, location.root) }
                })
            }
            if (locations.none { it.removable }) {
                addView(storageRow("Cartão SD", "Nenhum cartão montado", false, null))
            }
        }
        dialog = AlertDialog.Builder(activity).setView(panel).create()
        Ui.showSidePanel(dialog)
    }

    private fun showFolderBrowser(location: StorageLocation, startDirectory: File) {
        lateinit var dialog: AlertDialog
        var currentDirectory = startDirectory
        val path = TextView(activity).apply {
            textSize = 12f
            setTextColor(Ui.menuText(activity))
            alpha = 0.70f
            setPadding(Ui.dp(activity, 18), 0, Ui.dp(activity, 18), Ui.dp(activity, 8))
        }
        val folders = ArrayList<File?>()
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = folders.size
            override fun getItem(position: Int): File? = folders[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val folder = getItem(position)
                return LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = Ui.dp(activity, 52)
                    setPadding(Ui.dp(activity, 14), Ui.dp(activity, 5), Ui.dp(activity, 14), Ui.dp(activity, 5))
                    addView(
                        ImageView(activity).apply {
                            setImageResource(if (folder == null) R.drawable.ic_back else R.drawable.ic_folder)
                            setColorFilter(Ui.menuText(activity))
                            alpha = 0.78f
                        },
                        LinearLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24))
                    )
                    addView(
                        TextView(activity).apply {
                            text = folder?.name ?: "Pasta anterior"
                            textSize = 15f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(Ui.menuText(activity))
                            setPadding(Ui.dp(activity, 12), 0, 0, 0)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
            }
        }
        lateinit var refresh: () -> Unit
        val list = ListView(activity).apply {
            divider = null
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(Ui.menuSurface(activity))
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val selected = folders[position]
                currentDirectory = if (selected == null) {
                    currentDirectory.parentFile?.takeIf { isInside(location.root, it) } ?: location.root
                } else {
                    selected
                }
                refresh()
            }
        }
        refresh = {
            path.text = displayPath(location, currentDirectory)
            folders.clear()
            if (!sameFile(currentDirectory, location.root)) folders.add(null)
            folders.addAll(visibleFolders(currentDirectory, location))
            adapter.notifyDataSetChanged()
        }
        val panel = panel().apply {
            addView(title("Criar nova pasta"))
            addView(
                TextView(activity).apply {
                    text = location.label
                    textSize = 14f
                    setTextColor(Ui.menuText(activity))
                    setPadding(Ui.dp(activity, 18), 0, Ui.dp(activity, 18), Ui.dp(activity, 4))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(path)
            addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minOf(Ui.dp(activity, 350), (activity.resources.displayMetrics.heightPixels * 0.44f).toInt())
                )
            )
            addView(actionRow("Escolher armazenamento") {
                dialog.dismiss()
                openAfterDismiss { showStorageChoices() }
            })
            addView(actionRow("Criar pasta aqui", primary = true) {
                val parent = currentDirectory
                dialog.dismiss()
                openAfterDismiss { askFolderName(parent) }
            })
        }
        dialog = AlertDialog.Builder(activity).setView(panel).create()
        refresh()
        Ui.showSidePanel(dialog, fullHeight = true)
    }

    private fun askFolderName(parent: File) {
        Ui.showTextInputDialog(
            activity,
            "Criar nova pasta",
            "Nome da pasta",
            message = parent.absolutePath,
            positiveText = "Criar"
        ) { rawName -> createFolder(parent, rawName) }
    }

    private fun createFolder(parent: File, rawName: String) {
        val name = MediaActions.cleanFolderName(rawName)
        if (name.isBlank()) {
            Ui.toast(activity, "Digite um nome válido.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(activity)) {
            Ui.showConfirmationDialog(
                activity,
                "Permitir gerenciamento de arquivos",
                "Ative o acesso total a arquivos para criar a pasta no local escolhido.",
                "Permitir"
            ) { MediaActions.requestAllFilesAccess(activity) }
            return
        }
        val target = File(parent, name)
        if (target.exists()) {
            Ui.toast(activity, "A pasta já existe neste local.")
            return
        }
        if (MediaActions.createFolder(activity, target)) {
            Ui.toast(activity, "Pasta criada em ${displayParent(parent)}.")
            onFolderCreated(target)
        } else {
            Ui.toast(activity, "Não foi possível criar a pasta neste local.")
        }
    }

    private fun storageLocations(): List<StorageLocation> {
        val locations = ArrayList<StorageLocation>()
        val knownPaths = HashSet<String>()
        val internal = Environment.getExternalStorageDirectory()
        addLocation(locations, knownPaths, "Armazenamento interno", internal, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = activity.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            var cardNumber = 0
            manager.storageVolumes.forEach { volume ->
                if (volume.isPrimary || (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY)) {
                    return@forEach
                }
                val directory = volume.directory ?: return@forEach
                cardNumber++
                val label = if (cardNumber == 1) "Cartão SD" else "Cartão SD $cardNumber"
                addLocation(locations, knownPaths, label, directory, true)
            }
        } else {
            activity.getExternalFilesDirs(null).drop(1).forEachIndexed { index, appDirectory ->
                val root = storageRootFromAppDirectory(appDirectory) ?: return@forEachIndexed
                val label = if (index == 0) "Cartão SD" else "Cartão SD ${index + 1}"
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
        return if (relative.isBlank()) "Raiz do armazenamento" else relative.replace(File.separator, " › ")
    }

    private fun displayParent(parent: File): String = parent.name.ifBlank { parent.absolutePath }

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = canonicalPath(root).trimEnd(File.separatorChar)
        val candidatePath = canonicalPath(candidate)
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun sameFile(first: File, second: File): Boolean = canonicalPath(first) == canonicalPath(second)

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun panel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.menuSurface(activity), 14, activity)
        clipToOutline = true
        setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4))
    }

    private fun title(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 18f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setTextColor(Ui.menuText(activity))
        setPadding(Ui.dp(activity, 18), Ui.dp(activity, 14), Ui.dp(activity, 18), Ui.dp(activity, 8))
    }

    private fun description(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(Ui.menuText(activity))
        alpha = 0.76f
        setPadding(Ui.dp(activity, 18), 0, Ui.dp(activity, 18), Ui.dp(activity, 10))
    }

    private fun storageRow(label: String, subtitle: String, enabled: Boolean, onClick: (() -> Unit)?): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(activity, 62)
            alpha = if (enabled) 1f else 0.46f
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 6), Ui.dp(activity, 16), Ui.dp(activity, 6))
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_folder)
                    setColorFilter(Ui.menuText(activity))
                },
                LinearLayout.LayoutParams(Ui.dp(activity, 28), Ui.dp(activity, 28))
            )
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(activity, 12), 0, 0, 0)
                    addView(TextView(activity).apply {
                        text = label
                        textSize = 15f
                        setTextColor(Ui.menuText(activity))
                    })
                    addView(TextView(activity).apply {
                        text = subtitle
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(Ui.menuText(activity))
                        alpha = 0.64f
                    })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            isClickable = enabled
            isFocusable = enabled
            if (enabled && onClick != null) setOnClickListener { onClick() }
        }

    private fun actionRow(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 14f
            if (primary) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(Ui.menuText(activity))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = Ui.dp(activity, 50)
            background = if (primary) {
                Ui.rounded(Ui.blend(Ui.menuSurface(activity), Ui.menuText(activity), 0.08f), 0, activity)
            } else {
                Ui.rounded(Color.TRANSPARENT, 0, activity)
            }
            setPadding(Ui.dp(activity, 18), Ui.dp(activity, 10), Ui.dp(activity, 18), Ui.dp(activity, 10))
            setOnClickListener { onClick() }
        }

    private fun openAfterDismiss(block: () -> Unit) {
        activity.window.decorView.postDelayed(block, 60L)
    }
}
