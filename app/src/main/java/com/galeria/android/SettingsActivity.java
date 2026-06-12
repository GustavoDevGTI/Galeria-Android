package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

public class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        buildLayout();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPadding(bar, 12, 12, 12, 8);

        TextView back = Ui.title(this, "Voltar", 16);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)));

        TextView title = Ui.title(this, "Configurações", 22);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.setPadding(content, 18, 8, 18, 24);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        fillContent();
    }

    private void fillContent() {
        content.removeAllViews();
        addSection("Personalização de cores");
        addColorChoice();

        addSection("Geral");
        addSwitch("Modo escuro", "Alterna entre claro e escuro.", "dark_mode", true, new Runnable() {
            @Override
            public void run() {
                buildLayout();
            }
        });
        addOption("Idioma", languageLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseLanguage();
            }
        });
        addOption("Formato de data e hora", normalizeDisplayValue(prefs.getString("date_time_format", "Padrão do sistema")), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseValue("Formato de data e hora", "date_time_format", new String[] { "Padrão do sistema", "Data curta", "Data e hora" });
            }
        });
        addOption("Prioridade de carregamento", prefs.getString("loading_priority", "Velocidade"), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseValue("Prioridade de carregamento", "loading_priority", new String[] { "Velocidade", "Qualidade", "Equilibrado" });
            }
        });
        addOption("Gerenciar pastas inclusas", "Abrir seletor de pastas.", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new android.content.Intent(SettingsActivity.this, FolderPickerActivity.class));
            }
        });
        addOption("Gerenciar pastas ignoradas", "Use Exibir/ocultar pastas no menu principal.", null);
        addSwitch("Sempre exibir ocultos", "Mostra pastas ocultas na lista principal.", "always_show_hidden", false, null);
        addSwitch("Procurar todos os arquivos", "Mostra todos os arquivos em vez de somente pastas na tela principal.", "search_all_files", false, null);

        addSection("Fotos");
        addOption("Filtro de fotos", "Use Filtrar mídia no menu principal.", null);

        addSection("Vídeos");
        addSwitch("Reproduzir automaticamente", "Inicia vídeos ao abrir.", "autoplay_videos", true, null);
        addSwitch("Lembrar última posição", "Retoma vídeos de onde parou.", "remember_video_position", true, null);
        addSwitch("Reproduzir vídeos em ciclo", "Repete o vídeo continuamente.", "loop_videos", false, null);
        addSwitch("Abrir vídeos em tela separada", "Mantém vídeos no visualizador dedicado.", "video_separate_screen", false, null);
        addSwitch("Gestos verticais de volume/brilho", "Preferência salva para o visualizador.", "video_vertical_gestures", true, null);

        addSection("Miniaturas");
        addSwitch("Recortar miniaturas em quadrados", "Mantém capas com proporção uniforme.", "crop_square_thumbnails", true, null);
        addSwitch("Animar GIFs nas miniaturas", "Preferência salva para suporte a GIF animado.", "animate_gif_thumbnails", true, null);
        addOption("Estilo da miniatura de arquivo", normalizeDisplayValue(prefs.getString("file_thumb_style", "Padrão")), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseValue("Estilo da miniatura de arquivo", "file_thumb_style", new String[] { "Padrão", "Quadrado", "Cantos arredondados" });
            }
        });
        addOption("Estilo da miniatura de pasta", prefs.getString("folder_thumb_style", "Cantos arredondados"), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseValue("Estilo da miniatura de pasta", "folder_thumb_style", new String[] { "Quadrado", "Cantos arredondados", "Circular" });
            }
        });
        addOption("Limpar cache", cacheLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearCache();
            }
        });

        addSection("Rolagem");
        addSwitch("Rolar miniaturas horizontalmente", "Preferência salva para modos futuros de grade.", "horizontal_thumbnail_scroll", false, null);
        addSwitch("Puxar para atualizar", "Preferência salva para atualizar a galeria por gesto.", "pull_to_refresh", true, null);

        addSection("Mídia em tela cheia");
        addSwitch("Maximizar brilho", "Preferência salva para o visualizador.", "fullscreen_max_brightness", false, null);
        addSwitch("Fundo preto em tela cheia", "Usa fundo preto ao abrir mídia.", "fullscreen_black_bg", true, null);
        addSwitch("Esconder interface do sistema", "Oculta barras do sistema no visualizador.", "fullscreen_hide_system_ui", false, null);
        addSwitch("Trocar mídia tocando nas laterais", "Preferência salva para navegação lateral.", "tap_sides_change_media", false, null);
        addSwitch("Controle de brilho na vertical", "Preferência salva para gestos no visualizador.", "vertical_brightness_gesture", false, null);
        addSwitch("Fechar com gesto para baixo", "Arraste para baixo para sair do visualizador.", "swipe_down_to_close", true, null);
        addSwitch("Exibir o notch", "Preferência salva para aparelhos com recorte.", "show_display_cutout", true, null);
        addOption("Rotação de tela", normalizeDisplayValue(prefs.getString("rotation_criterion", "Padrão do sistema")), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseValue("Rotação de tela", "rotation_criterion", new String[] { "Padrão do sistema", "Retrato", "Paisagem", "Sensor" });
            }
        });

        addSection("Zoom aprofundado para imagens");
        addSwitch("Habilitar zoom aprofundado", "Preferência salva para zoom avançado.", "deep_image_zoom", true, null);
        addSwitch("Rotação por gestos", "Preferência salva para gestos de imagem.", "image_rotation_gestures", true, null);
        addSwitch("Maior qualidade possível", "Carrega imagens priorizando qualidade.", "best_image_quality", false, null);
        addSwitch("Zoom 1:1 com dois toques duplos", "Preferência salva para zoom rápido.", "double_double_tap_zoom", false, null);

        addSection("Detalhes adicionais");
        addSwitch("Exibir detalhes em tela cheia", "Mostra informações do arquivo no visualizador.", "show_fullscreen_details", false, null);

        addSection("Segurança");
        addSwitch("Proteger com senha todo o app", "Preferência salva para proteção futura.", "lock_entire_app", false, null);
        addSwitch("Proteger visualização de ocultos", "Preferência salva para itens ocultos.", "lock_hidden_items", false, null);
        addSwitch("Proteger exclusão e movimentação", "Preferência salva para operações sensíveis.", "lock_file_operations", false, null);

        addSection("Operações de arquivos");
        addSwitch("Apagar pastas vazias", "Remove pastas vazias após excluir conteúdo.", "delete_empty_folders", false, null);
        addSwitch("Manter data de modificação", "Evita atualizar a data ao mover arquivos quando possível.", "keep_modified_date", true, null);
        addSwitch("Pular confirmação de exclusão", "Pula a confirmação interna do app.", "skip_delete_confirmation", false, null);

        addSection("Barra inferior");
        addSwitch("Exibir botões de ação", "Preferência salva para a barra inferior.", "show_bottom_actions", true, null);
        addOption("Gerenciar botões visíveis", "Excluir, mover, ocultar e restaurar.", null);

        addSection("Lixeira");
        addSwitch("Mover para a Lixeira", "Usa a lixeira do Android quando disponível.", "move_to_trash", false, null);

        addSection("Migrando");
        addOption("Exportar caminho dos favoritos", "Nenhum favorito criado ainda.", null);
    }

    private void addSection(String title) {
        TextView view = Ui.title(this, title, 13);
        view.setTextColor(Ui.muted(this));
        view.setAllCaps(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 18);
        params.bottomMargin = Ui.dp(this, 6);
        content.addView(view, params);
    }

    private void addOption(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = rowBase();
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = Ui.title(this, title, 16);
        TextView subtitleView = Ui.label(this, subtitle);
        subtitleView.setGravity(Gravity.LEFT);
        texts.addView(titleView);
        texts.addView(subtitleView);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        content.addView(row);
    }

    private void addSwitch(String title, String subtitle, final String key, boolean defaultValue, final Runnable afterChange) {
        LinearLayout row = rowBase();
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = Ui.title(this, title, 16);
        TextView subtitleView = Ui.label(this, subtitle);
        subtitleView.setGravity(Gravity.LEFT);
        texts.addView(titleView);
        texts.addView(subtitleView);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
                if (afterChange != null) {
                    afterChange.run();
                }
            }
        });
        row.addView(toggle);
        content.addView(row);
    }

    private void addColorChoice() {
        LinearLayout row = rowBase();
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = Ui.title(this, "Cor do tema", 16);
        TextView subtitleView = Ui.label(this, "Escolha um destaque discreto.");
        subtitleView.setGravity(Gravity.LEFT);
        texts.addView(titleView);
        texts.addView(subtitleView);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout swatches = new LinearLayout(this);
        int[] colors = new int[] {
                Ui.text(this),
                Color.rgb(96, 165, 250),
                Color.rgb(168, 85, 247),
                Color.rgb(244, 114, 182)
        };
        for (final int color : colors) {
            TextView swatch = new TextView(this);
            swatch.setBackground(Ui.rounded(color, 14, this));
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    prefs.edit().putInt("theme_color", color).apply();
                    Ui.toast(SettingsActivity.this, "Cor aplicada.");
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            params.leftMargin = Ui.dp(this, 8);
            swatches.addView(swatch, params);
        }
        row.addView(swatches);
        content.addView(row);
    }

    private LinearLayout rowBase() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.rounded(Ui.surface(this), 8, this));
        Ui.setPadding(row, 14, 12, 14, 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(params);
        return row;
    }

    private String languageLabel() {
        String lang = prefs.getString("language", "pt");
        if ("en".equals(lang)) {
            return "English";
        }
        if ("es".equals(lang)) {
            return "Español";
        }
        return "Português";
    }

    private String normalizeDisplayValue(String value) {
        if ("Padrao do sistema".equals(value)) {
            return "Padrão do sistema";
        }
        if ("Padrao".equals(value)) {
            return "Padrão";
        }
        if ("Portugues".equals(value)) {
            return "Português";
        }
        if ("Espanol".equals(value)) {
            return "Español";
        }
        return value;
    }

    private void chooseLanguage() {
        final String[] labels = new String[] { "Português", "English", "Español" };
        final String[] values = new String[] { "pt", "en", "es" };
        String current = prefs.getString("language", "pt");
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Idioma")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.edit().putString("language", values[which]).apply();
                    dialog.dismiss();
                    fillContent();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void chooseValue(String title, final String key, final String[] values) {
        String current = prefs.getString(key, values[0]);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(values, checked, (dialog, which) -> {
                    prefs.edit().putString(key, values[which]).apply();
                    dialog.dismiss();
                    fillContent();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String cacheLabel() {
        long bytes = folderSize(getCacheDir());
        return (bytes / 1024) + " KB em cache.";
    }

    private void clearCache() {
        deleteChildren(getCacheDir());
        Ui.toast(this, "Cache limpo.");
        fillContent();
    }

    private long folderSize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                total += folderSize(child);
            }
        }
        return total;
    }

    private void deleteChildren(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteChildren(file);
            }
            file.delete();
        }
    }
}
