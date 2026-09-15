package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        buildLayout()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) fillContent()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@SettingsActivity))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            Ui.setPadding(this, 12, 12, 12, 8)
        }
        val back = Ui.title(this, getString(R.string.album_back), 16).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)))

        val title = Ui.title(this, getString(R.string.action_settings), 22)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            Ui.setPadding(this, 18, 8, 18, 24)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        fillContent()
    }

    private fun fillContent() {
        content.removeAllViews()
        addSection(getString(R.string.settings_section_access))
        addOption(getString(R.string.settings_media_access), mediaLibraryAccessLabel()) {
            requestPermissions(MediaActions.mediaLibraryPermissions(), REQ_MEDIA_LIBRARY)
        }
        addOption(getString(R.string.settings_full_management), allFilesAccessLabel()) {
            requestFullFileManagementAccess()
        }

        addSection(getString(R.string.settings_section_colors))
        addColorChoice()

        addSection(getString(R.string.settings_section_general))
        addOption(getString(R.string.settings_language), languageLabel()) { chooseLanguage() }
        val dateTimeValues = resources.getStringArray(R.array.settings_date_time_values)
        addOption(getString(R.string.settings_date_time_format), normalizeDisplayValue(prefs.getString("date_time_format", dateTimeValues[0]).orEmpty())) {
            chooseValue(getString(R.string.settings_date_time_format), "date_time_format", dateTimeValues)
        }
        val loadingPriorityValues = resources.getStringArray(R.array.settings_loading_priority_values)
        addOption(getString(R.string.settings_loading_priority), prefs.getString("loading_priority", loadingPriorityValues[0]).orEmpty()) {
            chooseValue(getString(R.string.settings_loading_priority), "loading_priority", loadingPriorityValues)
        }
        addOption(getString(R.string.settings_manage_included_folders), getString(R.string.settings_open_folder_picker)) {
            if (MediaActions.hasAllFilesAccess(this)) {
                startActivity(Intent(this, FolderPickerActivity::class.java))
            } else {
                requestFullFileManagementAccess()
            }
        }
        addOption(getString(R.string.settings_manage_ignored_folders), getString(R.string.settings_manage_ignored_folders_hint), null)
        addSwitch(
            getString(R.string.settings_always_show_hidden),
            if (MediaActions.hasAllFilesAccess(this)) {
                getString(R.string.settings_always_show_hidden_enabled_hint)
            } else {
                getString(R.string.settings_always_show_hidden_requires_full_hint)
            },
            "always_show_hidden",
            false,
            null
        )
        addSwitch(getString(R.string.settings_search_all_files), getString(R.string.settings_search_all_files_hint), "search_all_files", false, null)

        addSection(getString(R.string.settings_section_photos))
        addOption(getString(R.string.settings_photo_filter), getString(R.string.settings_photo_filter_hint), null)

        addSection(getString(R.string.settings_section_videos))
        addSwitch(getString(R.string.settings_autoplay_videos), getString(R.string.settings_autoplay_videos_hint), "autoplay_videos", true, null)
        addSwitch(getString(R.string.settings_remember_video_position), getString(R.string.settings_remember_video_position_hint), "remember_video_position", true, null)
        addSwitch(getString(R.string.settings_loop_videos), getString(R.string.settings_loop_videos_hint), "loop_videos", false, null)
        addSwitch(getString(R.string.settings_video_separate_screen), getString(R.string.settings_video_separate_screen_hint), "video_separate_screen", false, null)
        addSwitch(getString(R.string.settings_video_vertical_gestures), getString(R.string.settings_viewer_saved_preference_hint), "video_vertical_gestures", true, null)

        addSection(getString(R.string.settings_section_thumbnails))
        addSwitch(getString(R.string.settings_crop_square_thumbnails), getString(R.string.settings_crop_square_thumbnails_hint), "crop_square_thumbnails", true, null)
        addSwitch(getString(R.string.settings_animate_gif_thumbnails), getString(R.string.settings_animate_gif_thumbnails_hint), "animate_gif_thumbnails", true, null)
        val fileThumbnailValues = resources.getStringArray(R.array.settings_file_thumbnail_values)
        addOption(getString(R.string.settings_file_thumbnail_style), normalizeDisplayValue(prefs.getString("file_thumb_style", fileThumbnailValues[0]).orEmpty())) {
            chooseValue(getString(R.string.settings_file_thumbnail_style), "file_thumb_style", fileThumbnailValues)
        }
        val folderThumbnailValues = resources.getStringArray(R.array.settings_folder_thumbnail_values)
        addOption(getString(R.string.settings_folder_thumbnail_style), prefs.getString("folder_thumb_style", folderThumbnailValues[1]).orEmpty()) {
            chooseValue(getString(R.string.settings_folder_thumbnail_style), "folder_thumb_style", folderThumbnailValues)
        }
        addOption(getString(R.string.settings_clear_cache), cacheLabel()) { clearCache() }

        addSection(getString(R.string.settings_section_scrolling))
        addSwitch(getString(R.string.settings_horizontal_thumbnail_scroll), getString(R.string.settings_horizontal_thumbnail_scroll_hint), "horizontal_thumbnail_scroll", false, null)
        addSwitch(getString(R.string.settings_pull_to_refresh), getString(R.string.settings_pull_to_refresh_hint), "pull_to_refresh", true, null)

        addSection(getString(R.string.settings_section_fullscreen_media))
        addSwitch(getString(R.string.settings_fullscreen_max_brightness), getString(R.string.settings_viewer_saved_preference_hint), "fullscreen_max_brightness", false, null)
        addSwitch(getString(R.string.settings_fullscreen_black_bg), getString(R.string.settings_fullscreen_black_bg_hint), "fullscreen_black_bg", true, null)
        addSwitch(getString(R.string.settings_fullscreen_hide_system_ui), getString(R.string.settings_fullscreen_hide_system_ui_hint), "fullscreen_hide_system_ui", false, null)
        addSwitch(getString(R.string.settings_tap_sides_change_media), getString(R.string.settings_tap_sides_change_media_hint), "tap_sides_change_media", false, null)
        addSwitch(getString(R.string.settings_vertical_brightness_gesture), getString(R.string.settings_vertical_brightness_gesture_hint), "vertical_brightness_gesture", false, null)
        addSwitch(getString(R.string.settings_swipe_down_to_close), getString(R.string.settings_swipe_down_to_close_hint), "swipe_down_to_close", true, null)
        addSwitch(getString(R.string.settings_show_display_cutout), getString(R.string.settings_show_display_cutout_hint), "show_display_cutout", true, null)
        val rotationValues = resources.getStringArray(R.array.settings_rotation_values)
        addOption(getString(R.string.settings_rotation), normalizeDisplayValue(prefs.getString("rotation_criterion", rotationValues[0]).orEmpty())) {
            chooseValue(getString(R.string.settings_rotation), "rotation_criterion", rotationValues)
        }

        addSection(getString(R.string.settings_section_deep_image_zoom))
        addSwitch(getString(R.string.settings_deep_image_zoom), getString(R.string.settings_deep_image_zoom_hint), "deep_image_zoom", true, null)
        addSwitch(getString(R.string.settings_image_rotation_gestures), getString(R.string.settings_image_rotation_gestures_hint), "image_rotation_gestures", true, null)
        addSwitch(getString(R.string.settings_best_image_quality), getString(R.string.settings_best_image_quality_hint), "best_image_quality", false, null)
        addSwitch(getString(R.string.settings_double_double_tap_zoom), getString(R.string.settings_double_double_tap_zoom_hint), "double_double_tap_zoom", false, null)

        addSection(getString(R.string.settings_section_additional_details))
        addSwitch(getString(R.string.settings_show_fullscreen_details), getString(R.string.settings_show_fullscreen_details_hint), "show_fullscreen_details", false, null)

        addSection(getString(R.string.settings_section_security))
        addSwitch(getString(R.string.settings_lock_entire_app), getString(R.string.settings_lock_entire_app_hint), "lock_entire_app", false, null)
        addSwitch(getString(R.string.settings_lock_hidden_items), getString(R.string.settings_lock_hidden_items_hint), "lock_hidden_items", false, null)
        addSwitch(getString(R.string.settings_lock_file_operations), getString(R.string.settings_lock_file_operations_hint), "lock_file_operations", false, null)

        addSection(getString(R.string.settings_section_file_operations))
        addSwitch(getString(R.string.settings_delete_empty_folders), getString(R.string.settings_delete_empty_folders_hint), "delete_empty_folders", false, null)
        addSwitch(getString(R.string.settings_keep_modified_date), getString(R.string.settings_keep_modified_date_hint), "keep_modified_date", true, null)
        addSwitch(getString(R.string.settings_skip_delete_confirmation), getString(R.string.settings_skip_delete_confirmation_hint), "skip_delete_confirmation", false, null)

        addSection(getString(R.string.settings_section_bottom_bar))
        addSwitch(getString(R.string.settings_show_bottom_actions), getString(R.string.settings_show_bottom_actions_hint), "show_bottom_actions", true, null)
        addOption(getString(R.string.settings_manage_visible_buttons), getString(R.string.settings_manage_visible_buttons_hint), null)

        addSection(getString(R.string.settings_section_trash))
        addSwitch(getString(R.string.settings_move_to_trash), getString(R.string.settings_move_to_trash_hint), "move_to_trash", false, null)

        addSection(getString(R.string.settings_section_migrating))
        addOption(getString(R.string.settings_export_favorites_path), getString(R.string.settings_no_favorites), null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_LIBRARY) fillContent()
    }

    private fun mediaLibraryAccessLabel(): String = when (MediaActions.mediaLibraryAccess(this)) {
        MediaActions.MediaLibraryAccess.FULL -> getString(R.string.settings_media_access_full)
        MediaActions.MediaLibraryAccess.LIMITED -> getString(R.string.settings_media_access_limited)
        MediaActions.MediaLibraryAccess.NONE -> getString(R.string.settings_media_access_none)
    }

    private fun allFilesAccessLabel(): String = if (MediaActions.hasAllFilesAccess(this)) {
        getString(R.string.settings_full_management_active)
    } else {
        getString(R.string.settings_full_management_inactive)
    }

    private fun requestFullFileManagementAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || MediaActions.hasAllFilesAccess(this)) return
        Ui.showConfirmationDialog(
            this,
            getString(R.string.access_full_management_title),
            getString(R.string.settings_full_management_explanation),
            getString(R.string.settings_open_system_settings)
        ) { MediaActions.requestAllFilesAccess(this) }
    }

    private fun addSection(title: String) {
        val view = Ui.title(this, title, 13).apply {
            setTextColor(Ui.muted(this@SettingsActivity))
            isAllCaps = true
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@SettingsActivity, 18)
            bottomMargin = Ui.dp(this@SettingsActivity, 6)
        }
        content.addView(view, params)
    }

    private fun addOption(title: String, subtitle: String, listener: (() -> Unit)?) {
        val row = rowBase()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = Ui.title(this, title, 16)
        val subtitleView = Ui.label(this, subtitle).apply { gravity = Gravity.START }
        texts.addView(titleView)
        texts.addView(subtitleView)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (listener != null) {
            row.setOnClickListener { listener() }
        }
        content.addView(row)
    }

    private fun addSwitch(
        title: String,
        subtitle: String,
        key: String,
        defaultValue: Boolean,
        afterChange: Runnable?
    ) {
        val row = rowBase()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = Ui.title(this, title, 16)
        val subtitleView = Ui.label(this, subtitle).apply { gravity = Gravity.START }
        texts.addView(titleView)
        texts.addView(subtitleView)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
                afterChange?.run()
            })
        }
        row.addView(toggle)
        content.addView(row)
    }

    private fun addColorChoice() {
        val row = rowBase()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = Ui.title(this, getString(R.string.settings_theme_color), 16)
        val subtitleView = Ui.label(this, getString(R.string.settings_theme_color_hint)).apply { gravity = Gravity.START }
        texts.addView(titleView)
        texts.addView(subtitleView)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val preview = TextView(this).apply {
            background = Ui.rounded(Ui.themeSeed(this@SettingsActivity), 6, this@SettingsActivity)
        }
        row.addView(preview, LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)))
        row.setOnClickListener { showThemeColorDialog() }
        content.addView(row)
    }

    private fun showThemeColorDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.menuSurface(this@SettingsActivity), 14, this@SettingsActivity)
            clipToOutline = true
            addView(
                TextView(this@SettingsActivity).apply {
                    setText(R.string.settings_choose_theme_color)
                    textSize = 18f
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setTextColor(Ui.menuText(this@SettingsActivity))
                    Ui.setPadding(this, 18, 18, 18, 10)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val colors = themeColors()
        var index = 0
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        repeat(8) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            repeat(4) {
                val color = colors[index++]
                val swatch = TextView(this).apply {
                    background = Ui.rounded(color, 10, this@SettingsActivity)
                    setOnClickListener {
                        prefs.edit().putInt("theme_color", color).apply()
                        Ui.toast(this@SettingsActivity, getString(R.string.settings_theme_color_applied))
                        dialogRef[0]?.dismiss()
                        buildLayout()
                    }
                }
                val params = LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 42)).apply {
                    setMargins(Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 4))
                }
                row.addView(swatch, params)
            }
            panel.addView(row)
        }
        panel.addView(
            TextView(this).apply {
                setText(R.string.action_cancel)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(Ui.menuText(this@SettingsActivity))
                isClickable = true
                isFocusable = true
                Ui.setPadding(this, 18, 14, 18, 14)
                setOnClickListener { dialogRef[0]?.dismiss() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        dialogRef[0] = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        dialogRef[0]?.let { dialog -> Ui.showSidePanel(dialog) }
    }

    private fun themeColors(): IntArray = intArrayOf(
        Color.rgb(18, 18, 18), Color.rgb(45, 45, 45), Color.rgb(244, 244, 245), Color.rgb(214, 211, 209),
        Color.rgb(239, 68, 68), Color.rgb(220, 38, 38), Color.rgb(249, 115, 22), Color.rgb(245, 158, 11),
        Color.rgb(234, 179, 8), Color.rgb(132, 204, 22), Color.rgb(34, 197, 94), Color.rgb(16, 185, 129),
        Color.rgb(20, 184, 166), Color.rgb(6, 182, 212), Color.rgb(14, 165, 233), Color.rgb(59, 130, 246),
        Color.rgb(37, 99, 235), Color.rgb(99, 102, 241), Color.rgb(124, 58, 237), Color.rgb(147, 51, 234),
        Color.rgb(168, 85, 247), Color.rgb(217, 70, 239), Color.rgb(236, 72, 153), Color.rgb(244, 114, 182),
        Color.rgb(190, 18, 60), Color.rgb(127, 29, 29), Color.rgb(120, 53, 15), Color.rgb(63, 98, 18),
        Color.rgb(21, 94, 117), Color.rgb(30, 64, 175), Color.rgb(88, 28, 135), Color.rgb(80, 7, 36)
    )

    private fun rowBase(): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.surface(this@SettingsActivity), 8, this@SettingsActivity)
            Ui.setPadding(this, 14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@SettingsActivity, 8)
            }
        }

    private fun languageLabel(): String =
        when (prefs.getString("language", "pt")) {
            "en" -> getString(R.string.settings_language_english)
            "es" -> getString(R.string.settings_language_spanish)
            else -> getString(R.string.settings_language_portuguese)
        }

    private fun normalizeDisplayValue(value: String): String =
        when (value) {
            "Padrao do sistema" -> getString(R.string.settings_value_system_default)
            "Padrao" -> getString(R.string.settings_value_default)
            "Portugues" -> getString(R.string.settings_language_portuguese)
            "Espanol" -> getString(R.string.settings_language_spanish)
            else -> value
        }

    private fun chooseLanguage() {
        val labels = resources.getStringArray(R.array.settings_language_values)
        val values = arrayOf("pt", "en", "es")
        val current = prefs.getString("language", "pt")
        val checked = values.indexOf(current).takeIf { it >= 0 } ?: 0
        Ui.showChoiceDialog(this, getString(R.string.settings_language), labels, checked) { which ->
            prefs.edit().putString("language", values[which]).apply()
            fillContent()
        }
    }

    private fun chooseValue(title: String, key: String, values: Array<String>) {
        val current = prefs.getString(key, values[0])
        val checked = values.indexOf(current).takeIf { it >= 0 } ?: 0
        Ui.showChoiceDialog(this, title, values, checked) { which ->
            prefs.edit().putString(key, values[which]).apply()
            fillContent()
        }
    }

    private fun cacheLabel(): String {
        val bytes = folderSize(cacheDir)
        return getString(R.string.settings_cache_size_kb, bytes / 1024)
    }

    private fun clearCache() {
        deleteChildren(cacheDir)
        Ui.toast(this, getString(R.string.settings_cache_cleared))
        fillContent()
    }

    private fun folderSize(file: File?): Long {
        if (file == null || !file.exists()) {
            return 0
        }
        if (file.isFile) {
            return file.length()
        }
        var total = 0L
        file.listFiles()?.forEach { child ->
            total += folderSize(child)
        }
        return total
    }

    private fun deleteChildren(dir: File?) {
        dir?.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteChildren(file)
            }
            file.delete()
        }
    }

    private companion object {
        const val REQ_MEDIA_LIBRARY = 20
    }
}
