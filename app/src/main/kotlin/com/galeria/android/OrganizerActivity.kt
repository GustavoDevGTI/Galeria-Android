package com.galeria.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class OrganizerActivity : ComponentActivity() {
    internal lateinit var controller: OrganizerController; private set
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private var board: OrganizerBoard? = null
    private var displayedStage = -1
    private var requestedAccess = false
    private lateinit var saveButton: TextView
    private var exitAfterSave = false
    private var changeAfterSave = false
    private var list: ListView? = null
    private var pickerAdapter: BaseAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this)
        exitAfterSave = savedInstanceState?.getBoolean("exit_after_save") ?: false
        changeAfterSave = savedInstanceState?.getBoolean("change_after_save") ?: false
        controller = lastCustomNonConfigurationInstance as? OrganizerController ?: OrganizerSessions.acquire(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.bg(this@OrganizerActivity))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(icon(R.drawable.ic_back, getString(R.string.album_back)) { handleBack() },
            LinearLayout.LayoutParams(dp(48), dp(52)))
        title = Ui.title(this, getString(R.string.action_organize), 20)
        bar.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f).apply { title.gravity = Gravity.CENTER_VERTICAL })
        saveButton = Ui.label(this, "Salvar").apply {
            tag = "organizer_save"; setTextColor(Ui.text(this@OrganizerActivity)); isClickable = true; isFocusable = true
            setOnClickListener { board?.dismissPreviewImmediately(); controller.save() }
        }
        bar.addView(saveButton, LinearLayout.LayoutParams(dp(64), dp(48)))
        bar.addView(icon(R.drawable.ic_more_vertical, getString(R.string.action_more_options)) { anchor ->
            val change = getString(R.string.organizer_change)
            Ui.showPopupOptions(anchor, listOf(change)) {
                requestExit(true)
            }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        root.addView(bar)
        status = Ui.label(this, "").apply { setPadding(dp(16), dp(6), dp(16), dp(6)); tag = "organizer_status" }
        root.addView(status)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        controller.onChange = ::render
        if (controller.albums.isEmpty() && !controller.loading) controller.loadAlbums(
            intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS),
            intent.getBooleanExtra("include_hidden_filesystem", false), intent.getStringExtra("album_key"))
        render()
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = controller
    override fun onDestroy() {
        controller.onChange = null
        if (!isChangingConfigurations) OrganizerSessions.release(controller)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (requestedAccess && MediaActions.hasAllFilesAccess(this)) {
            requestedAccess = false; controller.begin()
        }
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        controller.message?.let { Ui.toast(this, it); controller.message = null }
        title.text = if (controller.stage == 2) controller.source?.name else getString(R.string.action_organize)
        status.visibility = if (controller.stage == 2 && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
        status.text = when {
            controller.loading -> getString(R.string.organizer_loading)
            controller.stage == 0 -> getString(R.string.organizer_source)
            controller.stage == 1 -> getString(R.string.organizer_loading)
            else -> resources.getQuantityString(R.plurals.organizer_remaining, controller.media.queue.size, controller.media.queue.size) +
                if (controller.pending == 0) "" else " · " + resources.getQuantityString(R.plurals.organizer_saving, controller.pending, controller.pending)
        }
        if (displayedStage != controller.stage) {
            displayedStage = controller.stage
            content.removeAllViews(); board = null; list = null
            if (controller.stage == 2) {
                board = OrganizerBoard(this, controller, ::createFolder)
                content.addView(board, FrameLayout.LayoutParams(-1, -1))
            } else buildPicker()
        }
        saveButton.visibility = if (controller.stage == 2) View.VISIBLE else View.GONE
        saveButton.isEnabled = controller.hasChanges && !controller.saving
        saveButton.alpha = if (saveButton.isEnabled) 1f else .4f
        if (!controller.saving && exitAfterSave) {
            exitAfterSave = false
            if (!controller.hasChanges) { if (changeAfterSave) controller.chooseAgain() else finish(); return }
        }
        pickerAdapter?.notifyDataSetChanged()
        board?.render()
    }

    private fun buildPicker() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.menuSurface(this@OrganizerActivity), 18, this@OrganizerActivity)
            clipToOutline = true
            setPadding(0, dp(6), 0, dp(8))
        }
        pickerAdapter = object : BaseAdapter() {
            private fun rows() = controller.albums
            override fun getCount() = rows().size
            override fun getItem(position: Int) = rows()[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val album = getItem(position)
                return Ui.albumTargetRow(this@OrganizerActivity, album).apply {
                    tag = "organizer_album_${album.key}"
                    contentDescription = album.name
                }
            }
        }
        list = ListView(this).apply {
            divider = null; adapter = pickerAdapter; tag = "organizer_album_picker"
            selector = Ui.actionFeedback(this@OrganizerActivity, Ui.menuText(this@OrganizerActivity))
            setOnItemClickListener { _, _, position, _ ->
                if (!controller.loading) {
                    val album = pickerAdapter!!.getItem(position) as AlbumItem
                    controller.chooseSource(album); startOrganizing()
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(panel, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(12), dp(6), dp(12), dp(12))
        })
    }

    private fun handleBack() {
        if (board?.dismissPreview() == true) return
        requestExit(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("exit_after_save", exitAfterSave)
        outState.putBoolean("change_after_save", changeAfterSave)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() { board?.dismissPreviewImmediately(); super.onStop() }

    private fun requestExit(changeSource: Boolean) {
        if (controller.saving) { Ui.toast(this, "Aguarde o salvamento."); return }
        if (!controller.hasChanges) { if (changeSource) controller.chooseAgain() else finish(); return }
        Ui.showActionDialog(this, "Salvar ${controller.unsavedCount} alterações?",
            listOf("Salvar e sair", "Sair sem salvar", "Continuar")) { index ->
            when (index) {
                0 -> { changeAfterSave = changeSource; exitAfterSave = true; controller.save() }
                1 -> { controller.discardChanges(); if (changeSource) controller.chooseAgain() else finish() }
            }
        }
    }
    private fun createFolder() {
        if (controller.saving) return
        FolderCreationMenu(this) { file ->
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val folder = file.canonicalFile
            if (folder.path.startsWith(root.path + File.separator)) {
                val key = folder.relativeTo(root).invariantSeparatorsPath + "/"
                controller.addFolder(AlbumItem(key, folder.name, 0, null, 0, 0, 0, key))
            }
        }.show()
    }
    private fun startOrganizing() {
        if (Build.VERSION.SDK_INT >= 30 && !MediaActions.hasAllFilesAccess(this)) {
            Ui.toast(this, getString(R.string.organizer_access))
            requestedAccess = true; MediaActions.requestAllFilesAccess(this); return
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 800); return
        }
        controller.begin()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 800 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) controller.begin()
    }

    private fun icon(resource: Int, description: String, action: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(resource); setColorFilter(Ui.text(context)); setBackgroundColor(android.graphics.Color.TRANSPARENT)
        contentDescription = description; setOnClickListener(action)
    }
    private fun dp(value: Int) = Ui.dp(this, value)

    companion object {
        fun intent(context: Context, exposed: List<String>?, includeHidden: Boolean, source: String? = null) =
            Intent(context, OrganizerActivity::class.java).apply {
                exposed?.let { putStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS, ArrayList(it)) }
                putExtra("include_hidden_filesystem", includeHidden); putExtra("album_key", source)
            }
    }
}
