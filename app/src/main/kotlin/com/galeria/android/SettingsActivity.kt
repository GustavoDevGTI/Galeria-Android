package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout
    private lateinit var root: LinearLayout
    private var advancedOptionsExpanded = false
    private val cacheOperations = ActivityOperationRunner(this)
    private var cacheSubtitle: TextView? = null
    private var cacheMeasurement = 0
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cacheSizeJob: Job? = null
    private var measuredCacheBytes: Long? = null
    private var measuredCacheAt = 0L

    override fun onDestroy() {
        cacheMeasurement++
        cacheScope.cancel()
        cacheOperations.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advancedOptionsExpanded = savedInstanceState?.getBoolean("advanced_options_expanded") ?: false
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        Ui.applySystemBars(this)
        buildLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("advanced_options_expanded", advancedOptionsExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        Ui.applySystemBars(this)
        if (::content.isInitialized) fillContent()
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@SettingsActivity))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            Ui.applySystemBarPadding(this, 12, 8, 12, 8)
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
        addSwitch(
            getString(R.string.settings_use_trash),
            getString(R.string.settings_use_trash_hint),
            TrashPreferences.ENABLED,
            true,
            null
        )
        val advancedStart = content.childCount
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
        cacheSubtitle = addOption(getString(R.string.settings_clear_cache), "…") { clearCache() }
        measureCache()

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

        addSection(getString(R.string.settings_section_migrating))
        addOption(getString(R.string.settings_export_favorites_path), getString(R.string.settings_no_favorites), null)

        val advancedToggle = Ui.title(
            this,
            getString(R.string.settings_advanced_options) + if (advancedOptionsExpanded) "  ▴" else "  ▾",
            15
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = Ui.dp(this@SettingsActivity, 50)
            setPadding(Ui.dp(this@SettingsActivity, 16), 0, Ui.dp(this@SettingsActivity, 16), 0)
            contentDescription = getString(
                if (advancedOptionsExpanded) R.string.settings_hide_advanced_options
                else R.string.settings_show_advanced_options
            )
            isClickable = true
            isFocusable = true
            val toggleTone = if (advancedOptionsExpanded) {
                Ui.highlightSurface(this@SettingsActivity)
            } else {
                Ui.elevatedSurface(this@SettingsActivity)
            }
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf((Ui.text(this@SettingsActivity) and 0x00FFFFFF) or 0x18000000),
                Ui.rounded(toggleTone, 12, this@SettingsActivity, Ui.border(this@SettingsActivity), 1),
                Ui.rounded(android.graphics.Color.WHITE, 12, this@SettingsActivity)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = Ui.dp(this@SettingsActivity, 4)
                bottomMargin = Ui.dp(this@SettingsActivity, 8)
            }
            setOnClickListener {
                Haptics.perform(this, Haptics.toggle(!advancedOptionsExpanded))
                advancedOptionsExpanded = !advancedOptionsExpanded
                fillContent()
            }
        }
        content.addView(advancedToggle, advancedStart)
        for (index in advancedStart + 1 until content.childCount) {
            content.getChildAt(index).visibility = if (advancedOptionsExpanded) View.VISIBLE else View.GONE
        }
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
            val accent = Ui.accent(this@SettingsActivity)
            if (ThemeToneRules.contrastRatio(accent, Ui.bg(this@SettingsActivity)) >= 4.5) {
                setTextColor(accent)
            }
            isAllCaps = true
            letterSpacing = 0.04f
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@SettingsActivity, 18)
            bottomMargin = Ui.dp(this@SettingsActivity, 6)
        }
        content.addView(view, params)
    }

    private fun addOption(title: String, subtitle: String, listener: (() -> Unit)?): TextView {
        val row = rowBase()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = Ui.title(this, title, 16)
        val subtitleView = Ui.label(this, subtitle).apply { gravity = Gravity.START }
        texts.addView(titleView)
        texts.addView(subtitleView)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (listener != null) {
            row.setOnClickListener {
                Haptics.perform(row, Haptics.Effect.TAP)
                listener()
            }
        }
        content.addView(row)
        return subtitleView
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

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            thumbTintList = ColorStateList(
                states,
                intArrayOf(Ui.accent(this@SettingsActivity), Ui.muted(this@SettingsActivity))
            )
            trackTintList = ColorStateList(
                states,
                intArrayOf(Ui.highlightSurface(this@SettingsActivity), Ui.elevatedSurface(this@SettingsActivity))
            )
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
                Haptics.perform(this, Haptics.toggle(isChecked))
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

        val swatch = View(this).apply {
            tag = "settings_theme_swatch"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Ui.highlightSurface(this@SettingsActivity))
                setStroke(Ui.dp(this@SettingsActivity, 1), Ui.border(this@SettingsActivity))
            }
        }
        row.addView(swatch, LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)).apply {
            marginStart = Ui.dp(this@SettingsActivity, 12)
        })
        row.setOnClickListener {
            Haptics.perform(row, Haptics.Effect.TAP)
            showThemeColorDialog()
        }
        content.addView(row)
    }

    private fun showThemeColorDialog() {
        val initialColor = Ui.themeSeed(this)
        var selectedColor = initialColor
        var applied = false
        val panel = LinearLayout(this).apply {
            tag = "theme_color_panel"
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.bg(this@SettingsActivity), 16, this@SettingsActivity, Ui.border(this@SettingsActivity), 1)
            clipToOutline = true
            Ui.setPadding(this, 20, 18, 12, 8)
        }

        val title = TextView(this).apply {
            setText(R.string.settings_choose_theme_color)
            textSize = 19f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = Ui.dp(this@SettingsActivity, 8) })

        val instruction = TextView(this).apply {
            setText(R.string.settings_theme_color_instruction)
            textSize = 13f
        }
        panel.addView(
            instruction,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@SettingsActivity, 4)
                bottomMargin = Ui.dp(this@SettingsActivity, 8)
                marginEnd = Ui.dp(this@SettingsActivity, 8)
            }
        )

        val wheel = ThemeColorWheelView(this).apply {
            tag = ThemeColorWheelView.TAG
            contentDescription = getString(R.string.settings_theme_color_wheel_description)
            setColor(initialColor)
        }
        panel.addView(
            wheel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 260)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                marginEnd = Ui.dp(this@SettingsActivity, 8)
            }
        )

        val dialogRef = arrayOfNulls<AlertDialog>(1)
        fun textAction(label: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label)
            textSize = 15f
            if (bold) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            minHeight = Ui.dp(this@SettingsActivity, 48)
            isClickable = true
            isFocusable = true
            Ui.setPadding(this, 12, 0, 12, 0)
            setOnClickListener { onClick() }
        }
        val restore = textAction(R.string.settings_theme_restore, false) {
            applied = true
            prefs.edit().remove(Ui.THEME_COLOR_PREF).apply()
            Haptics.perform(this@SettingsActivity, Haptics.Effect.CONFIRM)
            Ui.clearThemePreview()
            LauncherIconManager.syncWithPreferences(this@SettingsActivity)
            dialogRef[0]?.dismiss()
            Ui.applySystemBars(this@SettingsActivity)
            buildLayout()
        }.apply { visibility = if (prefs.contains(Ui.THEME_COLOR_PREF)) View.VISIBLE else View.GONE }
        val cancel = textAction(R.string.action_cancel, false) { dialogRef[0]?.dismiss() }
        val confirm = textAction(R.string.settings_theme_apply, true) {
            applied = true
            prefs.edit().putInt(Ui.THEME_COLOR_PREF, selectedColor).apply()
            Haptics.perform(this@SettingsActivity, Haptics.Effect.CONFIRM)
            Ui.clearThemePreview()
            LauncherIconManager.syncWithPreferences(this@SettingsActivity)
            dialogRef[0]?.dismiss()
            Ui.toast(this@SettingsActivity, getString(R.string.settings_theme_color_applied))
            Ui.applySystemBars(this@SettingsActivity)
            buildLayout()
        }
        val actions = LinearLayout(this).apply {
            tag = "theme_color_actions"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(restore)
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(cancel)
            addView(confirm, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@SettingsActivity, 4) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = Ui.dp(this@SettingsActivity, 12)
            marginStart = -Ui.dp(this@SettingsActivity, 12)
        })

        // Rebuilds the screen behind the dialog so cards and texts follow the preview too,
        // at most once per short pause while the finger is dragging on the wheel.
        val refreshScreen = Runnable { if (!isFinishing) buildLayout() }
        fun applyPreview(color: Int) {
            selectedColor = Ui.normalizeThemeSeed(color)
            Ui.setThemePreview(selectedColor)
            root.setBackgroundColor(Ui.bg(this@SettingsActivity))
            window.decorView.removeCallbacks(refreshScreen)
            window.decorView.postDelayed(refreshScreen, PREVIEW_REFRESH_DELAY_MS)
            Ui.applySystemBars(this@SettingsActivity)
            val bg = Ui.bg(this@SettingsActivity)
            panel.background = Ui.rounded(bg, 16, this@SettingsActivity, Ui.border(this@SettingsActivity), 1)
            val mainText = Ui.text(this@SettingsActivity)
            title.setTextColor(mainText)
            instruction.setTextColor(Ui.muted(this@SettingsActivity))
            val accent = Ui.accent(this@SettingsActivity)
            val confirmColor = if (ThemeToneRules.contrastRatio(accent, bg) >= 4.5) accent else mainText
            listOf(restore to Ui.muted(this@SettingsActivity), cancel to mainText, confirm to confirmColor)
                .forEach { (action, color) ->
                    action.setTextColor(color)
                    action.background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf((mainText and 0x00FFFFFF) or 0x22000000),
                        null,
                        Ui.rounded(android.graphics.Color.WHITE, 10, this@SettingsActivity)
                    )
                }
        }

        wheel.onColorChanged = ::applyPreview
        applyPreview(initialColor)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }
        dialogRef[0] = AlertDialog.Builder(this)
            .setView(scroll)
            .create()
        dialogRef[0]?.setOnDismissListener {
            window.decorView.removeCallbacks(refreshScreen)
            Ui.clearThemePreview()
            if (!applied && !isFinishing) {
                Ui.applySystemBars(this@SettingsActivity)
                buildLayout()
            }
        }
        dialogRef[0]?.let { dialog -> Ui.showCenteredPanel(dialog) }
    }

    private fun rowBase(): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf((Ui.text(this@SettingsActivity) and 0x00FFFFFF) or 0x16000000),
                Ui.rounded(Ui.surface(this@SettingsActivity), 12, this@SettingsActivity, Ui.border(this@SettingsActivity), 1),
                Ui.rounded(android.graphics.Color.WHITE, 12, this@SettingsActivity)
            )
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

    private fun measureCache() {
        val known = measuredCacheBytes
        if (known != null && android.os.SystemClock.elapsedRealtime() - measuredCacheAt < 5_000L) {
            cacheSubtitle?.text = getString(R.string.settings_cache_size_kb, known / 1024)
            return
        }
        if (cacheSizeJob?.isActive == true) return
        val measurement = cacheMeasurement
        cacheSizeJob = cacheScope.launch {
            val bytes = withContext(Dispatchers.IO) { folderSize(cacheDir) }
            if (measurement == cacheMeasurement && !isFinishing) {
                measuredCacheBytes = bytes
                measuredCacheAt = android.os.SystemClock.elapsedRealtime()
                cacheSubtitle?.text = getString(R.string.settings_cache_size_kb, bytes / 1024)
            }
        }
    }

    private fun clearCache() {
        if (cacheOperations.busy) return
        cacheMeasurement++
        measuredCacheBytes = null
        cacheSizeJob?.cancel()
        cacheOperations.run({ deleteChildren(cacheDir) }) {
            Ui.toast(this, getString(R.string.settings_cache_cleared))
            fillContent()
        }
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
        const val PREVIEW_REFRESH_DELAY_MS = 60L
    }
}
