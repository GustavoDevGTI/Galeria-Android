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
    private var startButton: Button? = null
    private var list: ListView? = null
    private var pickerAdapter: BaseAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this)
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
        bar.addView(icon(R.drawable.ic_more_vertical, getString(R.string.action_more_options)) { anchor ->
            val change = getString(R.string.organizer_change)
            Ui.showPopupOptions(anchor, listOf(change)) {
                if (!controller.chooseAgain()) Ui.toast(this, "Aguarde os movimentos em andamento.")
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
        status.text = when {
            controller.loading -> getString(R.string.organizer_loading)
            controller.stage == 0 -> getString(R.string.organizer_source)
            controller.stage == 1 -> getString(R.string.organizer_destinations, controller.destinations.size)
            else -> resources.getQuantityString(R.plurals.organizer_remaining, controller.media.queue.size, controller.media.queue.size) +
                if (controller.pending == 0) "" else " · " + resources.getQuantityString(R.plurals.organizer_saving, controller.pending, controller.pending)
        }
        if (displayedStage != controller.stage) {
            displayedStage = controller.stage
            content.removeAllViews(); board = null; startButton = null; list = null
            if (controller.stage == 2) {
                board = OrganizerBoard(this, controller)
                content.addView(board, FrameLayout.LayoutParams(-1, -1))
            } else buildPicker()
        }
        pickerAdapter?.notifyDataSetChanged()
        startButton?.isEnabled = !controller.loading && OrganizerRules.validSelection(
            controller.source?.key, controller.destinations.map { it.key })
        board?.render()
    }

    private fun buildPicker() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.menuSurface(this@OrganizerActivity), 18, this@OrganizerActivity)
            clipToOutline = true
            setPadding(0, dp(6), 0, dp(8))
        }
        if (controller.stage == 1) {
            panel.addView(Ui.label(this, getString(if (controller.albums.size < 2) R.string.organizer_insufficient else R.string.organizer_destination_help))
                .apply { setPadding(dp(20), dp(4), dp(20), dp(12)) })
        }
        pickerAdapter = object : BaseAdapter() {
            private fun rows() = controller.albums.filter { controller.stage == 0 || it.key != controller.source?.key }
            override fun getCount() = rows().size
            override fun getItem(position: Int) = rows()[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val album = getItem(position)
                val index = controller.destinations.indexOfFirst { it.key == album.key }
                return Ui.albumTargetRow(this@OrganizerActivity, album).apply {
                    tag = "organizer_album_${album.key}"
                    addView(Ui.title(context, if (controller.stage == 1 && index >= 0) "${index + 1}" else "", 16).apply {
                        setTextColor(Ui.menuText(context))
                    },
                        LinearLayout.LayoutParams(dp(32), -2))
                    isSelected = if (controller.stage == 0) album.key == controller.source?.key else index >= 0
                    if (isSelected) background = Ui.rounded(Ui.blend(Ui.menuSurface(context), Ui.menuText(context), .10f), 10, context)
                    contentDescription = album.name + if (index >= 0) ", destino ${index + 1}" else ""
                }
            }
        }
        list = ListView(this).apply {
            divider = null; adapter = pickerAdapter; tag = "organizer_album_picker"
            selector = Ui.actionFeedback(this@OrganizerActivity, Ui.menuText(this@OrganizerActivity))
            setOnItemClickListener { _, _, position, _ ->
                if (!controller.loading) {
                    val album = pickerAdapter!!.getItem(position) as AlbumItem
                    if (controller.stage == 0) controller.chooseSource(album) else controller.toggleDestination(album)
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (controller.stage == 1) {
            panel.addView(Ui.button(this, getString(R.string.action_create_folder)).apply {
                setOnClickListener {
                    FolderCreationMenu(this@OrganizerActivity) { file ->
                        val root = Environment.getExternalStorageDirectory().canonicalFile
                        val folder = file.canonicalFile
                        if (folder.path.startsWith(root.path + File.separator)) {
                            val key = folder.relativeTo(root).invariantSeparatorsPath + "/"
                            controller.addFolder(AlbumItem(key, folder.name, 0, null, 0, 0, 0, key))
                        } else Ui.toast(this@OrganizerActivity, "Escolha uma pasta no armazenamento interno.")
                    }.show()
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            startButton = Ui.button(this, getString(R.string.organizer_start)).apply {
                tag = "organizer_start"
                setOnClickListener { startOrganizing() }
            }
            panel.addView(startButton, LinearLayout.LayoutParams(-1, dp(52)))
        }
        content.addView(panel, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(12), dp(6), dp(12), dp(12))
        })
    }

    private fun handleBack() {
        if (board?.dismissPreview() == true) return
        if (controller.stage == 1) controller.chooseAgain() else finish()
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
