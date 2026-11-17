package com.galeria.android

import android.content.Intent

/**
 * Options of the main screen, split between the bottom bar and the panel that replaced the
 * three-dot menu. The bar starts with Ordenar, Organizar, Ocultos and Configurações.
 */
internal object MainBrowseLayout {
    val defaultBar = listOf("sort", "organize", "hidden", "settings")
    val defaultOrder = defaultBar + listOf(
        "filter", "folder_organization", "spacing", "create_folder", "trash_hidden", "refresh", "trash", "theme"
    )
}

internal fun MainActivity.mainBrowseLayoutStore() = BrowseLayoutStore(
    getSharedPreferences(Ui.PREFS, android.content.Context.MODE_PRIVATE), "main",
    MainBrowseLayout.defaultBar, MainBrowseLayout.defaultOrder
)

internal fun MainActivity.mainBrowseActions(): List<BrowseAction> {
    val showHiddenTrash = GalleryPreferences.hiddenFlag(this, VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF)
    return listOf(
        BrowseAction("sort", R.drawable.ic_sort, getString(R.string.browse_sort), getString(R.string.action_sort_by)) {
            showSortDialog()
        },
        BrowseAction("organize", R.drawable.ic_organize, getString(R.string.action_organize)) {
            startActivity(OrganizerActivity.intent(this, adapter.allAlbumsSnapshot().map { it.key },
                shouldIncludeHiddenFilesystem()))
        },
        BrowseAction("hidden", R.drawable.ic_eye_off, getString(R.string.browse_hidden), getString(R.string.main_folder_visibility)) {
            SecurityGate.requireHiddenAccess(this) { showFolderVisibilityDialog() }
        },
        BrowseAction("settings", R.drawable.ic_settings, getString(R.string.action_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        },
        BrowseAction("filter", R.drawable.ic_filter, getString(R.string.action_filter_media)) { showMediaFilterDialog() },
        BrowseAction("folder_organization", R.drawable.ic_folder_organization, getString(R.string.main_folder_organization)) {
            showFolderOrganizationDialog()
        },
        BrowseAction("spacing", R.drawable.ic_spacing, getString(R.string.main_album_spacing)) { showAlbumSpacingDialog() },
        BrowseAction("create_folder", R.drawable.ic_folder_add, getString(R.string.action_create_folder)) {
            FolderCreationMenu(this) { loadAlbums() }.show()
        },
        BrowseAction("trash_hidden", R.drawable.ic_trash_eye,
            getString(if (showHiddenTrash) R.string.trash_hide_hidden else R.string.trash_show_hidden)) {
            val apply = {
                prefs.edit().putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, !showHiddenTrash).apply()
                loadAlbums()
                refreshBrowseBar()
            }
            if (showHiddenTrash) apply() else SecurityGate.requireHiddenAccess(this, action = apply)
        },
        BrowseAction("refresh", R.drawable.ic_refresh, getString(R.string.action_refresh)) { loadAlbums() },
        BrowseAction("trash", R.drawable.ic_trash, getString(R.string.album_trash)) {
            startActivity(Intent(this, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", VirtualAlbumRules.TRASH_KEY)
                putExtra("album_name", getString(R.string.album_trash))
                putExtra("include_hidden_filesystem", shouldIncludeHiddenFilesystem())
            })
        },
        BrowseAction("theme", R.drawable.ic_palette, getString(R.string.action_theme)) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_THEME, true))
        }
    )
}

/** Shows the bar's shortcuts as saved, skipping options this screen does not offer right now. */
internal fun MainActivity.refreshBrowseBar() {
    val actions = mainBrowseActions().associateBy { it.id }
    browseBar.setActions(mainBrowseLayoutStore().load().bar.mapNotNull(actions::get))
}

internal fun MainActivity.openBrowsePanel() {
    val panel = browsePanel ?: BrowseActionPanel(this, browseBar, mainBrowseLayoutStore(), ::mainBrowseActions) {
        refreshBrowseBar()
    }.also { browsePanel = it }
    panel.show()
}
