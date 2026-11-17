package com.galeria.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Names and defaults of the user preferences shown in Settings, in one place.
 * The keys keep their original text so values saved by earlier versions still apply.
 */
internal object GalleryPreferences {
    // Security
    const val APP_PASSWORD = "security_app_password"
    const val SECURE_FOLDERS = "security_secure_folders"
    const val LOCK_ENTIRE_APP = "lock_entire_app"
    const val LOCK_HIDDEN_ITEMS = "lock_hidden_items"
    const val LOCK_FILE_OPERATIONS = "lock_file_operations"

    // File operations
    const val DELETE_EMPTY_FOLDERS = "delete_empty_folders"
    const val KEEP_MODIFIED_DATE = "keep_modified_date"
    const val SKIP_DELETE_CONFIRMATION = "skip_delete_confirmation"

    // Viewer
    const val VIDEO_SEPARATE_SCREEN = "video_separate_screen"
    const val VIDEO_VERTICAL_GESTURES = "video_vertical_gestures"
    const val FULLSCREEN_BLACK_BG = "fullscreen_black_bg"
    const val FULLSCREEN_HIDE_SYSTEM_UI = "fullscreen_hide_system_ui"
    const val TAP_SIDES_CHANGE_MEDIA = "tap_sides_change_media"
    const val VERTICAL_BRIGHTNESS_GESTURE = "vertical_brightness_gesture"
    const val SWIPE_DOWN_TO_CLOSE = "swipe_down_to_close"
    const val SHOW_DISPLAY_CUTOUT = "show_display_cutout"
    const val DEEP_IMAGE_ZOOM = "deep_image_zoom"
    const val IMAGE_ROTATION_GESTURES = "image_rotation_gestures"
    const val BEST_IMAGE_QUALITY = "best_image_quality"
    const val DOUBLE_DOUBLE_TAP_ZOOM = "double_double_tap_zoom"
    const val SHOW_FULLSCREEN_DETAILS = "show_fullscreen_details"
    const val SHOW_BOTTOM_ACTIONS = "show_bottom_actions"

    // Grids
    const val CROP_SQUARE_THUMBNAILS = "crop_square_thumbnails"
    const val ANIMATE_GIF_THUMBNAILS = "animate_gif_thumbnails"
    const val HORIZONTAL_THUMBNAIL_SCROLL = "horizontal_thumbnail_scroll"
    const val PULL_TO_REFRESH = "pull_to_refresh"
    const val FILE_THUMB_STYLE = "file_thumb_style"
    const val SHOW_ALBUM_NAMES = "show_album_names"
    const val SHOW_FILE_NAMES = "show_file_names"

    // General
    const val DATE_TIME_FORMAT = "date_time_format"
    const val LOADING_PRIORITY = "loading_priority"
    const val FOLDER_THUMB_STYLE = "folder_thumb_style"
    const val ROTATION_CRITERION = "rotation_criterion"

    // Videos
    const val AUTOPLAY_VIDEOS = "autoplay_videos"
    const val REMEMBER_VIDEO_POSITION = "remember_video_position"
    const val LOOP_VIDEOS = "loop_videos"
    const val FULLSCREEN_MAX_BRIGHTNESS = "fullscreen_max_brightness"

    // Library: hidden folders, favorites and how lists are shown
    const val HIDDEN_FOLDER_KEYS = "hidden_folder_keys"
    const val SHOW_HIDDEN_FOLDERS = "show_hidden_folders"
    const val SEARCH_ALL_FILES = "search_all_files"
    const val FAVORITES = "favorites"
    const val ORGANIZER_CREATED_FOLDERS = "organizer_created_folders"
    const val COLUMN_COUNT = "column_count"
    const val ALBUM_SPACING_DP = "album_spacing_dp"

    // Sorting and filters (global on the main screen, or per album through AlbumCoverRules.optionKey)
    const val SORT_MODE = "sort_mode"
    const val SORT_DESC = "sort_desc"
    const val FILTER_IMAGES = "filter_images"
    const val FILTER_VIDEOS = "filter_videos"
    const val FILTER_GIFS = "filter_gifs"
    const val FILTER_RAW = "filter_raw"
    /** Main screen SVG filter; albums use [ALBUM_FILTER_SVG]. Two names kept for saved values. */
    const val FILTER_SVGS = "filter_svgs"
    const val ALBUM_FILTER_SVG = "filter_svg"
    const val FILTER_PORTRAITS = "filter_portraits"

    /** Defaults match the switches in Settings, so reading a value never disagrees with what the user sees. */
    private val booleanDefaults = mapOf(
        DELETE_EMPTY_FOLDERS to false,
        KEEP_MODIFIED_DATE to true,
        SKIP_DELETE_CONFIRMATION to false,
        VIDEO_SEPARATE_SCREEN to false,
        VIDEO_VERTICAL_GESTURES to true,
        FULLSCREEN_BLACK_BG to true,
        FULLSCREEN_HIDE_SYSTEM_UI to false,
        TAP_SIDES_CHANGE_MEDIA to false,
        VERTICAL_BRIGHTNESS_GESTURE to false,
        SWIPE_DOWN_TO_CLOSE to false,
        SHOW_DISPLAY_CUTOUT to true,
        DEEP_IMAGE_ZOOM to true,
        IMAGE_ROTATION_GESTURES to false,
        BEST_IMAGE_QUALITY to false,
        DOUBLE_DOUBLE_TAP_ZOOM to false,
        SHOW_FULLSCREEN_DETAILS to false,
        SHOW_BOTTOM_ACTIONS to true,
        CROP_SQUARE_THUMBNAILS to true,
        ANIMATE_GIF_THUMBNAILS to true,
        HORIZONTAL_THUMBNAIL_SCROLL to false,
        PULL_TO_REFRESH to true,
        SHOW_ALBUM_NAMES to true,
        SHOW_FILE_NAMES to true
    )

    fun defaultOf(key: String): Boolean = booleanDefaults[key] ?: false

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)

    fun flag(context: Context, key: String): Boolean = prefs(context).getBoolean(key, defaultOf(key))

    /**
     * A "show hidden" preference only applies while the hidden items are not locked, or after the
     * user typed the app password in this visit.
     */
    fun hiddenFlag(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, false) && hiddenItemsUnlocked(context)

    fun hiddenItemsUnlocked(context: Context): Boolean =
        !SecurityStore(prefs(context)).lockHiddenItems || SecuritySession.hiddenUnlocked
}
