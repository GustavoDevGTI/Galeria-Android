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

        TextView title = Ui.title(this, "Configuracoes", 22);
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
        addSection("Geral");
        addColorChoice();
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
        addSwitch("Sempre exibir ocultos", "Mostra pastas ocultas na lista principal.", "always_show_hidden", false, null);

        addSection("Fotos");
        addOption("Filtro de fotos", "Use Filtrar midia no menu principal.", null);

        addSection("Videos");
        addSwitch("Reproduzir automaticamente", "Inicia videos ao abrir.", "autoplay_videos", true, null);

        addSection("Miniaturas");
        addOption("Limpar cache", cacheLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearCache();
            }
        });
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
            return "Espanol";
        }
        return "Portugues";
    }

    private void chooseLanguage() {
        final String[] labels = new String[] { "Portugues", "English", "Espanol" };
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
