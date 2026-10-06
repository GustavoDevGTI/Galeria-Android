package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class FolderPickerActivity : Activity() {
    private lateinit var rootDir: File
    private lateinit var currentDir: File
    private lateinit var adapter: FolderAdapter
    private lateinit var pathView: TextView
    private val folderWorker = Executors.newSingleThreadExecutor()
    private val operations = ActivityOperationRunner(this)
    @Volatile private var folderRequest = 0

    override fun onDestroy() {
        folderRequest++
        folderWorker.shutdownNow()
        operations.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this)
        rootDir = Environment.getExternalStorageDirectory()
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        currentDir = if (pictures.exists()) pictures else rootDir
        buildLayout()
        loadFolders()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@FolderPickerActivity))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            Ui.applySystemBarPadding(this, 12, 8, 12, 8)
        }
        val back = Ui.title(this, "Voltar", 16).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)))

        val title = Ui.title(this, "Selecionar pasta", 22)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        pathView = Ui.label(this, "").apply { gravity = Gravity.START }
        Ui.setPadding(pathView, 18, 0, 18, 8)
        root.addView(pathView)

        val list = ListView(this).apply {
            divider = null
            cacheColorHint = android.graphics.Color.TRANSPARENT
            setBackgroundColor(Ui.bg(this@FolderPickerActivity))
        }
        adapter = FolderAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (item == null) {
                val parentDir = currentDir.parentFile
                if (parentDir != null && isInsideRoot(parentDir)) {
                    currentDir = parentDir
                    loadFolders()
                }
            } else {
                currentDir = item
                loadFolders()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            Ui.setPadding(this, 12, 10, 12, 14)
        }
        val create = Ui.title(this, "Criar pasta", 16).apply {
            setTextColor(Ui.accent(this@FolderPickerActivity))
            gravity = Gravity.CENTER
            setOnClickListener { askCreateFolder() }
        }
        actions.addView(create, LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 44)))

        val ok = Ui.title(this, "OK", 16).apply {
            setTextColor(Ui.accent(this@FolderPickerActivity))
            gravity = Gravity.CENTER
            setOnClickListener {
                Ui.toast(this@FolderPickerActivity, "Pasta selecionada: ${currentDir.name}")
                finish()
            }
        }
        actions.addView(ok, LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 44)))
        root.addView(actions)
        setContentView(root)
    }

    private fun loadFolders() {
        pathView.text = currentDir.absolutePath
        val directory = currentDir
        val request = ++folderRequest
        // Remove old-directory entries so fast repeated taps never navigate via stale rows.
        adapter.submit(emptyList(), emptyMap())
        folderWorker.execute {
            if (request != folderRequest) return@execute
            val folders = ArrayList<File?>()
            if (directory != rootDir) folders.add(null)
            directory.listFiles()?.filter { it.isDirectory && !it.isHidden }
                ?.sortedBy { it.name.lowercase() }?.let(folders::addAll)
            val counts = HashMap<String, Int>()
            for (folder in folders.filterNotNull()) {
                if (request != folderRequest) return@execute
                counts[folder.absolutePath] = folder.list()?.size ?: 0
            }
            runOnUiThread {
                if (request == folderRequest && !isFinishing && !isDestroyed) adapter.submit(folders, counts)
            }
        }
    }

    private fun isInsideRoot(dir: File?): Boolean =
        dir != null && dir.absolutePath.startsWith(rootDir.absolutePath)

    private fun askCreateFolder() {
        Ui.showTextInputDialog(
            this,
            "Criar nova pasta",
            "Nome da pasta",
            positiveText = "Criar"
        ) { name ->
            createFolder(name)
        }
    }

    private fun createFolder(name: String) {
        if (operations.busy) return
        val clean = MediaActions.cleanFolderName(name)
        if (clean.isEmpty()) {
            Ui.toast(this, "Digite um nome válido.")
            return
        }
        val target = File(currentDir, clean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            Ui.showConfirmationDialog(
                this,
                "Permitir gerenciamento de arquivos",
                "Para criar pastas no celular, ative o acesso total a arquivos para a Galeria.",
                "Permitir"
            ) { MediaActions.requestAllFilesAccess(this) }
            return
        }
        operations.run({
            if (target.exists()) 0 else if (MediaActions.createFolder(this, target)) 1 else -1
        }) { result ->
            if (result == 1) {
                Ui.toast(this, "Pasta criada.")
                currentDir = target
                loadFolders()
            } else Ui.toast(this, if (result == 0) "A pasta já existe." else "Não foi possível criar a pasta aqui.")
        }
    }

    private inner class FolderAdapter : BaseAdapter() {
        private val folders = ArrayList<File?>()
        private var counts: Map<String, Int> = emptyMap()

        fun submit(nextFolders: List<File?>, nextCounts: Map<String, Int>) {
            counts = nextCounts
            folders.clear()
            folders.addAll(nextFolders)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = folders.size

        override fun getItem(position: Int): File? = folders[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (convertView is LinearLayout) {
                (convertView.getChildAt(1) as TextView).text = labelFor(getItem(position))
                return convertView
            }
            val row = LinearLayout(this@FolderPickerActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                Ui.setPadding(this, 18, 10, 18, 10)
            }

            val icon = ImageView(this@FolderPickerActivity).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(Ui.muted(this@FolderPickerActivity))
            }
            row.addView(
                icon,
                LinearLayout.LayoutParams(
                    Ui.dp(this@FolderPickerActivity, 42),
                    Ui.dp(this@FolderPickerActivity, 42)
                )
            )

            val text = Ui.title(this@FolderPickerActivity, labelFor(getItem(position)), 18).apply {
                isSingleLine = false
            }
            val textParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(this@FolderPickerActivity, 14)
            }
            row.addView(text, textParams)
            return row
        }

        private fun labelFor(file: File?): String {
            if (file == null) return ".."
            val count = counts[file.absolutePath] ?: 0
            return file.name + "\n" + count + if (count == 1) " item" else " itens"
        }
    }
}
