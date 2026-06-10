package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class FolderPickerActivity extends Activity {
    private File rootDir;
    private File currentDir;
    private FolderAdapter adapter;
    private TextView pathView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootDir = Environment.getExternalStorageDirectory();
        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        currentDir = pictures.exists() ? pictures : rootDir;
        buildLayout();
        loadFolders();
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

        TextView title = Ui.title(this, "Selecionar pasta", 22);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        pathView = Ui.label(this, "");
        pathView.setGravity(Gravity.LEFT);
        Ui.setPadding(pathView, 18, 0, 18, 8);
        root.addView(pathView);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setCacheColorHint(android.graphics.Color.TRANSPARENT);
        list.setBackgroundColor(Ui.bg(this));
        adapter = new FolderAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            File item = adapter.getItem(position);
            if (item == null) {
                File parentDir = currentDir.getParentFile();
                if (parentDir != null && isInsideRoot(parentDir)) {
                    currentDir = parentDir;
                    loadFolders();
                }
            } else {
                currentDir = item;
                loadFolders();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        Ui.setPadding(actions, 12, 10, 12, 14);

        TextView create = Ui.title(this, "Criar pasta", 16);
        create.setTextColor(Ui.accent(this));
        create.setGravity(Gravity.CENTER);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                askCreateFolder();
            }
        });
        actions.addView(create, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 44)));

        TextView ok = Ui.title(this, "OK", 16);
        ok.setTextColor(Ui.accent(this));
        ok.setGravity(Gravity.CENTER);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ui.toast(FolderPickerActivity.this, "Pasta selecionada: " + currentDir.getName());
                finish();
            }
        });
        actions.addView(ok, new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 44)));
        root.addView(actions);

        setContentView(root);
    }

    private void loadFolders() {
        pathView.setText(currentDir.getAbsolutePath());
        ArrayList<File> folders = new ArrayList<>();
        if (!currentDir.equals(rootDir)) {
            folders.add(null);
        }
        File[] files = currentDir.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File first, File second) {
                    return first.getName().compareToIgnoreCase(second.getName());
                }
            });
            for (File file : files) {
                if (file.isDirectory() && !file.isHidden()) {
                    folders.add(file);
                }
            }
        }
        adapter.submit(folders);
    }

    private boolean isInsideRoot(File dir) {
        return dir != null && dir.getAbsolutePath().startsWith(rootDir.getAbsolutePath());
    }

    private void askCreateFolder() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nome da pasta");
        input.setTextColor(Ui.text(this));
        input.setHintTextColor(Ui.muted(this));
        new AlertDialog.Builder(this)
                .setTitle("Criar nova pasta")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Criar", (dialog, which) -> createFolder(input.getText().toString()))
                .show();
    }

    private void createFolder(String name) {
        String clean = MediaActions.cleanFolderName(name);
        if (clean.isEmpty()) {
            Ui.toast(this, "Digite um nome valido.");
            return;
        }
        File target = new File(currentDir, clean);
        if (target.exists()) {
            Ui.toast(this, "A pasta ja existe.");
            return;
        }
        if (target.mkdirs()) {
            Ui.toast(this, "Pasta criada.");
            currentDir = target;
            loadFolders();
        } else {
            Ui.toast(this, "Nao foi possivel criar a pasta aqui.");
        }
    }

    private final class FolderAdapter extends BaseAdapter {
        private final ArrayList<File> folders = new ArrayList<>();

        void submit(List<File> nextFolders) {
            folders.clear();
            folders.addAll(nextFolders);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return folders.size();
        }

        @Override
        public File getItem(int position) {
            return folders.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(FolderPickerActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.setPadding(row, 18, 10, 18, 10);

            ImageView icon = new ImageView(FolderPickerActivity.this);
            icon.setImageResource(com.galeria.android.R.drawable.ic_folder);
            icon.setColorFilter(Ui.muted(FolderPickerActivity.this));
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(FolderPickerActivity.this, 42), Ui.dp(FolderPickerActivity.this, 42)));

            TextView text = Ui.title(FolderPickerActivity.this, labelFor(getItem(position)), 18);
            text.setSingleLine(false);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            textParams.leftMargin = Ui.dp(FolderPickerActivity.this, 14);
            row.addView(text, textParams);
            return row;
        }

        private String labelFor(File file) {
            if (file == null) {
                return "..";
            }
            File[] files = file.listFiles();
            int count = files == null ? 0 : files.length;
            return file.getName() + "\n" + count + (count == 1 ? " item" : " itens");
        }
    }
}
