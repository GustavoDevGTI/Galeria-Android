package com.galeria.android
import android.content.Context

/** The information sheet is built with Portuguese labels; this shows them in the phone language. */
internal object MetadataLabels {
    private val ids = mapOf(
        "Arquivo" to R.string.meta_file,
        "Nome" to R.string.meta_name,
        "Data" to R.string.meta_date,
        "Tamanho" to R.string.meta_size,
        "Formato" to R.string.meta_format,
        "Pasta" to R.string.meta_folder,
        "Resolução" to R.string.meta_resolution,
        "Câmera" to R.string.meta_camera,
        "Capturada em" to R.string.meta_captured_at,
        "ISO" to R.string.meta_iso,
        "Abertura" to R.string.meta_aperture,
        "Exposição" to R.string.meta_exposure,
        "Distância focal" to R.string.meta_focal_length,
        "Imagem e câmera" to R.string.meta_image_camera,
        "Localização" to R.string.meta_location,
        "Coordenadas" to R.string.meta_coordinates,
        "Duração" to R.string.meta_duration,
        "Rotação" to R.string.meta_rotation,
        "Codec" to R.string.meta_codec,
        "Taxa de bits" to R.string.meta_bit_rate,
        "Quadros por segundo" to R.string.meta_frame_rate,
        "Vídeo" to R.string.meta_video,
        "Desconhecida" to R.string.meta_unknown_date,
        "Desconhecido" to R.string.meta_unknown
    )

    fun translate(context: Context, label: String): String = ids[label]?.let(context::getString) ?: label
}

/** The viewer menu options are identified by their Portuguese names; this shows them in the phone language. */
internal object ViewerMenuLabels {
    private val ids = mapOf(
        ViewerMenuRules.RENAME to R.string.msg_rename,
        ViewerMenuRules.OPEN_WITH to R.string.viewer_menu_open_with,
        ViewerMenuRules.COPY_TO to R.string.msg_copy_to,
        ViewerMenuRules.MOVE_TO to R.string.msg_move_to,
        ViewerMenuRules.HIDE to R.string.msg_hide,
        ViewerMenuRules.INFORMATION to R.string.action_information,
        ViewerMenuRules.ENABLE_LOOP to R.string.viewer_menu_enable_loop,
        ViewerMenuRules.DISABLE_LOOP to R.string.viewer_menu_disable_loop,
        ViewerMenuRules.SET_AS to R.string.viewer_menu_set_as,
        ViewerMenuRules.ROTATE to R.string.viewer_menu_rotate,
        ViewerMenuRules.EXPORT_PDF to R.string.viewer_menu_export_pdf,
        ViewerMenuRules.RESIZE to R.string.viewer_menu_resize,
        ViewerMenuRules.SHOW_ON_MAP to R.string.viewer_menu_show_on_map,
        ViewerMenuRules.PRESENTATION to R.string.viewer_menu_presentation,
        ViewerMenuRules.AUDIO_TRACK to R.string.viewer_audio_track,
        ViewerMenuRules.SUBTITLES to R.string.viewer_subtitles,
        ViewerMenuRules.RECOGNIZE_TEXT to R.string.action_recognize_text
    )

    fun translate(context: Context, option: String): String = ids[option]?.let(context::getString) ?: option
}
