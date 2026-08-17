package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
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

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@SettingsActivity))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            Ui.setPadding(this, 12, 12, 12, 8)
        }
        val back = Ui.title(this, "Voltar", 16).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)))

        val title = Ui.title(this, "Configurações", 22)
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
        addSection("Personalização de cores")
        addColorChoice()

        addSection("Geral")
        addOption("Idioma", languageLabel()) { chooseLanguage() }
        addOption("Formato de data e hora", normalizeDisplayValue(prefs.getString("date_time_format", "Padrão do sistema").orEmpty())) {
            chooseValue("Formato de data e hora", "date_time_format", arrayOf("Padrão do sistema", "Data curta", "Data e hora"))
        }
        addOption("Prioridade de carregamento", prefs.getString("loading_priority", "Velocidade").orEmpty()) {
            chooseValue("Prioridade de carregamento", "loading_priority", arrayOf("Velocidade", "Qualidade", "Equilibrado"))
        }
        addOption("Gerenciar pastas inclusas", "Abrir seletor de pastas.") {
            startActivity(Intent(this, FolderPickerActivity::class.java))
        }
        addOption("Gerenciar pastas ignoradas", "Use Exibir/ocultar pastas no menu principal.", null)
        addSwitch("Sempre exibir ocultos", "Mostra pastas ocultas na lista principal.", "always_show_hidden", false, null)
        addSwitch("Procurar todos os arquivos", "Mostra todos os arquivos em vez de somente pastas na tela principal.", "search_all_files", false, null)

        addSection("Fotos")
        addOption("Filtro de fotos", "Use Filtrar mídia no menu principal.", null)

        addSection("Vídeos")
        addSwitch("Reproduzir automaticamente", "Inicia vídeos ao abrir.", "autoplay_videos", true, null)
        addSwitch("Lembrar última posição", "Retoma vídeos de onde parou.", "remember_video_position", true, null)
        addSwitch("Reproduzir vídeos em ciclo", "Repete o vídeo continuamente.", "loop_videos", false, null)
        addSwitch("Abrir vídeos em tela separada", "Mantém vídeos no visualizador dedicado.", "video_separate_screen", false, null)
        addSwitch("Gestos verticais de volume/brilho", "Preferência salva para o visualizador.", "video_vertical_gestures", true, null)

        addSection("Miniaturas")
        addSwitch("Recortar miniaturas em quadrados", "Mantém capas com proporção uniforme.", "crop_square_thumbnails", true, null)
        addSwitch("Animar GIFs nas miniaturas", "Preferência salva para suporte a GIF animado.", "animate_gif_thumbnails", true, null)
        addOption("Estilo da miniatura de arquivo", normalizeDisplayValue(prefs.getString("file_thumb_style", "Padrão").orEmpty())) {
            chooseValue("Estilo da miniatura de arquivo", "file_thumb_style", arrayOf("Padrão", "Quadrado", "Cantos arredondados"))
        }
        addOption("Estilo da miniatura de pasta", prefs.getString("folder_thumb_style", "Cantos arredondados").orEmpty()) {
            chooseValue("Estilo da miniatura de pasta", "folder_thumb_style", arrayOf("Quadrado", "Cantos arredondados", "Circular"))
        }
        addOption("Limpar cache", cacheLabel()) { clearCache() }

        addSection("Rolagem")
        addSwitch("Rolar miniaturas horizontalmente", "Preferência salva para modos futuros de grade.", "horizontal_thumbnail_scroll", false, null)
        addSwitch("Puxar para atualizar", "Preferência salva para atualizar a galeria por gesto.", "pull_to_refresh", true, null)

        addSection("Mídia em tela cheia")
        addSwitch("Maximizar brilho", "Preferência salva para o visualizador.", "fullscreen_max_brightness", false, null)
        addSwitch("Fundo preto em tela cheia", "Usa fundo preto ao abrir mídia.", "fullscreen_black_bg", true, null)
        addSwitch("Esconder interface do sistema", "Oculta barras do sistema no visualizador.", "fullscreen_hide_system_ui", false, null)
        addSwitch("Trocar mídia tocando nas laterais", "Preferência salva para navegação lateral.", "tap_sides_change_media", false, null)
        addSwitch("Controle de brilho na vertical", "Preferência salva para gestos no visualizador.", "vertical_brightness_gesture", false, null)
        addSwitch("Fechar com gesto para baixo", "Arraste para baixo para sair do visualizador.", "swipe_down_to_close", true, null)
        addSwitch("Exibir o notch", "Preferência salva para aparelhos com recorte.", "show_display_cutout", true, null)
        addOption("Rotação de tela", normalizeDisplayValue(prefs.getString("rotation_criterion", "Padrão do sistema").orEmpty())) {
            chooseValue("Rotação de tela", "rotation_criterion", arrayOf("Padrão do sistema", "Retrato", "Paisagem", "Sensor"))
        }

        addSection("Zoom aprofundado para imagens")
        addSwitch("Habilitar zoom aprofundado", "Preferência salva para zoom avançado.", "deep_image_zoom", true, null)
        addSwitch("Rotação por gestos", "Preferência salva para gestos de imagem.", "image_rotation_gestures", true, null)
        addSwitch("Maior qualidade possível", "Carrega imagens priorizando qualidade.", "best_image_quality", false, null)
        addSwitch("Zoom 1:1 com dois toques duplos", "Preferência salva para zoom rápido.", "double_double_tap_zoom", false, null)

        addSection("Detalhes adicionais")
        addSwitch("Exibir detalhes em tela cheia", "Mostra informações do arquivo no visualizador.", "show_fullscreen_details", false, null)

        addSection("Segurança")
        addSwitch("Proteger com senha todo o app", "Preferência salva para proteção futura.", "lock_entire_app", false, null)
        addSwitch("Proteger visualização de ocultos", "Preferência salva para itens ocultos.", "lock_hidden_items", false, null)
        addSwitch("Proteger exclusão e movimentação", "Preferência salva para operações sensíveis.", "lock_file_operations", false, null)

        addSection("Operações de arquivos")
        addSwitch("Apagar pastas vazias", "Remove pastas vazias após excluir conteúdo.", "delete_empty_folders", false, null)
        addSwitch("Manter data de modificação", "Evita atualizar a data ao mover arquivos quando possível.", "keep_modified_date", true, null)
        addSwitch("Pular confirmação de exclusão", "Pula a confirmação interna do app.", "skip_delete_confirmation", false, null)

        addSection("Barra inferior")
        addSwitch("Exibir botões de ação", "Preferência salva para a barra inferior.", "show_bottom_actions", true, null)
        addOption("Gerenciar botões visíveis", "Excluir, mover, ocultar e restaurar.", null)

        addSection("Lixeira")
        addSwitch("Mover para a Lixeira", "Usa a lixeira do Android quando disponível.", "move_to_trash", false, null)

        addSection("Migrando")
        addOption("Exportar caminho dos favoritos", "Nenhum favorito criado ainda.", null)
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
        val subtitleView = Ui.label(this, subtitle).apply { gravity = Gravity.LEFT }
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
        val subtitleView = Ui.label(this, subtitle).apply { gravity = Gravity.LEFT }
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
        val titleView = Ui.title(this, "Cor do tema", 16)
        val subtitleView = Ui.label(this, "Define o visual padrão da galeria.").apply { gravity = Gravity.LEFT }
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
            Ui.setPadding(this, 16, 12, 16, 8)
        }
        val colors = themeColors()
        var index = 0
        val dialogRef = arrayOfNulls<AlertDialog>(1)
        repeat(4) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            repeat(8) {
                val color = colors[index++]
                val swatch = TextView(this).apply {
                    background = Ui.rounded(color, 4, this@SettingsActivity)
                    setOnClickListener {
                        prefs.edit().putInt("theme_color", color).apply()
                        Ui.toast(this@SettingsActivity, "Cor aplicada.")
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
        dialogRef[0] = AlertDialog.Builder(this)
            .setTitle("Escolher cor tema")
            .setView(panel)
            .setNegativeButton("Cancelar", null)
            .create()
        dialogRef[0]?.let { dialog -> Ui.applySidePanelStyle(dialog) }
        dialogRef[0]?.setOnShowListener { dialogRef[0]?.let { dialog -> Ui.applySidePanelStyle(dialog) } }
        dialogRef[0]?.show()
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
            "en" -> "English"
            "es" -> "Español"
            else -> "Português"
        }

    private fun normalizeDisplayValue(value: String): String =
        when (value) {
            "Padrao do sistema" -> "Padrão do sistema"
            "Padrao" -> "Padrão"
            "Portugues" -> "Português"
            "Espanol" -> "Español"
            else -> value
        }

    private fun chooseLanguage() {
        val labels = arrayOf("Português", "English", "Español")
        val values = arrayOf("pt", "en", "es")
        val current = prefs.getString("language", "pt")
        val checked = values.indexOf(current).takeIf { it >= 0 } ?: 0
        Ui.showChoiceDialog(this, "Idioma", labels, checked) { which ->
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
        return "${bytes / 1024} KB em cache."
    }

    private fun clearCache() {
        deleteChildren(cacheDir)
        Ui.toast(this, "Cache limpo.")
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
}
