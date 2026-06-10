package com.galeria.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_READ = 10;
    private static final String PREFS = "gallery_albums";
    private static final String SORT_NAME = "name";
    private static final String SORT_PATH = "path";
    private static final String SORT_SIZE = "size";
    private static final String SORT_MODIFIED = "modified";
    private static final String SORT_CREATED = "created";
    private static final String SORT_RANDOM = "random";

    private AlbumGridAdapter adapter;
    private TextView emptyView;
    private EditText searchInput;
    private GridView grid;
    private LinearLayout root;
    private LinearLayout top;
    private SharedPreferences prefs;
    private ScaleGestureDetector scaleDetector;
    private String sortMode;
    private boolean sortDesc;
    private boolean showImages;
    private boolean showVideos;
    private boolean showGifs;
    private boolean showRaw;
    private boolean showSvgs;
    private boolean showPortraits;
    private boolean showHiddenFolders;
    private int columnCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();
        buildLayout();
        if (hasReadPermission()) {
            loadAlbums();
        } else {
            requestReadPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        applyThemeColors();
        if (hasReadPermission()) {
            loadAlbums();
        }
    }

    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(Ui.rounded(Ui.search(this), 22, this));
        Ui.setPadding(top, 8, 1, 5, 1);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        topParams.setMargins(Ui.dp(this, 24), Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 14));
        root.addView(top, topParams);

        ImageButton searchIcon = iconButton(com.galeria.android.R.drawable.ic_search);
        searchIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                searchInput.requestFocus();
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        top.addView(searchIcon, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 38)));

        searchInput = new EditText(this);
        searchInput.setHint("Pesquisar pastas");
        searchInput.setHintTextColor(0x99F5F7FA);
        searchInput.setTextColor(Ui.text(this));
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchInput.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.applyFilter(s.toString());
                updateEmptyText();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        top.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ImageButton more = iconButton(com.galeria.android.R.drawable.ic_more_vertical);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu(view);
            }
        });
        top.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 38)));

        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(columnCount);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setVerticalSpacing(Ui.dp(this, 16));
        grid.setClipToPadding(false);
        grid.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 20));
        grid.setBackgroundColor(Ui.bg(this));
        adapter = new AlbumGridAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AlbumItem album = adapter.getItem(position);
                Intent intent = new Intent(MainActivity.this, AlbumMediaActivity.class);
                intent.putExtra("album_key", album.key);
                intent.putExtra("album_name", album.name);
                startActivity(intent);
            }
        });
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (detector.getScaleFactor() < 0.92f) {
                    setColumnCount(columnCount + 1);
                    return true;
                }
                if (detector.getScaleFactor() > 1.08f) {
                    setColumnCount(columnCount - 1);
                    return true;
                }
                return false;
            }
        });
        grid.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                scaleDetector.onTouchEvent(event);
                return false;
            }
        });
        content.addView(grid);

        emptyView = Ui.label(this, "Nenhuma pasta encontrada.");
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private ImageButton iconButton(int icon) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setColorFilter(Ui.accent(this));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        return button;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Ordenar por");
        menu.getMenu().add("Filtrar midia");
        menu.getMenu().add("Organizacao de pastas");
        menu.getMenu().add("Exibir/ocultar pastas");
        menu.getMenu().add("Criar nova pasta");
        menu.getMenu().add("Configuracoes");
        menu.getMenu().add("Ocultos");
        menu.getMenu().add("Atualizar");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Ordenar por".equals(title)) {
                showSortDialog();
            } else if ("Filtrar midia".equals(title)) {
                showMediaFilterDialog();
            } else if ("Organizacao de pastas".equals(title)) {
                showFolderOrganizationDialog();
            } else if ("Exibir/ocultar pastas".equals(title)) {
                showFolderVisibilityDialog();
            } else if ("Criar nova pasta".equals(title)) {
                startActivity(new Intent(this, FolderPickerActivity.class));
            } else if ("Configuracoes".equals(title)) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if ("Ocultos".equals(title)) {
                startActivity(new Intent(this, HiddenActivity.class));
            } else {
                loadAlbums();
            }
            return true;
        });
        menu.show();
    }

    private void loadAlbums() {
        List<MediaItem> media = MediaStoreRepository.loadMedia(this);
        ArrayList<MediaItem> filteredMedia = new ArrayList<>();
        for (MediaItem item : media) {
            if (matchesMediaFilter(item)) {
                filteredMedia.add(item);
            }
        }

        List<AlbumItem> albums = MediaStoreRepository.buildAlbums(filteredMedia);
        Set<String> hiddenKeys = prefs.getStringSet("hidden_folder_keys", new HashSet<String>());
        if (!showHiddenFolders && !hiddenKeys.isEmpty()) {
            ArrayList<AlbumItem> visibleAlbums = new ArrayList<>();
            for (AlbumItem album : albums) {
                if (!hiddenKeys.contains(album.key)) {
                    visibleAlbums.add(album);
                }
            }
            albums = visibleAlbums;
        }
        sortAlbums(albums);
        adapter.submit(albums);
        if (searchInput != null) {
            adapter.applyFilter(searchInput.getText().toString());
        }
        updateEmptyText();
    }

    private void loadSettings() {
        sortMode = prefs.getString("sort_mode", SORT_MODIFIED);
        sortDesc = prefs.getBoolean("sort_desc", true);
        showImages = prefs.getBoolean("filter_images", true);
        showVideos = prefs.getBoolean("filter_videos", true);
        showGifs = prefs.getBoolean("filter_gifs", true);
        showRaw = prefs.getBoolean("filter_raw", true);
        showSvgs = prefs.getBoolean("filter_svgs", true);
        showPortraits = prefs.getBoolean("filter_portraits", false);
        showHiddenFolders = prefs.getBoolean("show_hidden_folders", false)
                || prefs.getBoolean("always_show_hidden", false);
        columnCount = prefs.getInt("column_count", 3);
        if (columnCount < 2 || columnCount > 6) {
            columnCount = 3;
        }
    }

    private void applyThemeColors() {
        if (root != null) {
            root.setBackgroundColor(Ui.bg(this));
        }
        if (top != null) {
            top.setBackground(Ui.rounded(Ui.search(this), 22, this));
        }
        if (searchInput != null) {
            searchInput.setTextColor(Ui.text(this));
            searchInput.setHintTextColor(Ui.muted(this));
        }
        if (grid != null) {
            grid.setBackgroundColor(Ui.bg(this));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showSortDialog() {
        final String[] labels = new String[] {
                "Nome",
                "Caminho",
                "Tamanho",
                "Data de modificacao",
                "Data de criacao",
                "Aleatorio"
        };
        final String[] modes = new String[] {
                SORT_NAME,
                SORT_PATH,
                SORT_SIZE,
                SORT_MODIFIED,
                SORT_CREATED,
                SORT_RANDOM
        };
        int checked = 3;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(sortMode)) {
                checked = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Ordenar por")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        sortMode = modes[which];
                    }
                })
                .setNegativeButton("Cancelar", null)
                .setNeutralButton(sortDesc ? "Decrescente" : "Crescente", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        sortDesc = !sortDesc;
                        saveSorting();
                        loadAlbums();
                    }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveSorting();
                        loadAlbums();
                    }
                })
                .show();
    }

    private void saveSorting() {
        prefs.edit()
                .putString("sort_mode", sortMode)
                .putBoolean("sort_desc", sortDesc)
                .apply();
    }

    private void showMediaFilterDialog() {
        final String[] labels = new String[] {
                "Imagens",
                "Videos",
                "Gifs",
                "Imagens RAW",
                "SVGs",
                "Retratos"
        };
        final boolean[] checked = new boolean[] {
                showImages,
                showVideos,
                showGifs,
                showRaw,
                showSvgs,
                showPortraits
        };

        new AlertDialog.Builder(this)
                .setTitle("Filtrar midia")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showImages = checked[0];
                        showVideos = checked[1];
                        showGifs = checked[2];
                        showRaw = checked[3];
                        showSvgs = checked[4];
                        showPortraits = checked[5];
                        prefs.edit()
                                .putBoolean("filter_images", showImages)
                                .putBoolean("filter_videos", showVideos)
                                .putBoolean("filter_gifs", showGifs)
                                .putBoolean("filter_raw", showRaw)
                                .putBoolean("filter_svgs", showSvgs)
                                .putBoolean("filter_portraits", showPortraits)
                                .apply();
                        loadAlbums();
                    }
                })
                .show();
    }

    private void showFolderOrganizationDialog() {
        final String[] labels = new String[] { "2 colunas", "3 colunas", "4 colunas", "5 colunas", "6 colunas" };
        int checked = Math.max(0, Math.min(4, columnCount - 2));
        new AlertDialog.Builder(this)
                .setTitle("Organizacao de pastas")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setColumnCount(which + 2);
                        dialog.dismiss();
                    }
                })
                .setMessage("Use dois dedos na grade: juntar diminui as capas; afastar aumenta as capas.")
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showFolderVisibilityDialog() {
        final List<AlbumItem> albums = MediaStoreRepository.buildAlbums(MediaStoreRepository.loadMedia(this));
        sortAlbums(albums);
        final Set<String> hiddenKeys = new HashSet<>(prefs.getStringSet("hidden_folder_keys", new HashSet<String>()));
        final String[] labels = new String[albums.size() + 1];
        final boolean[] checked = new boolean[albums.size() + 1];
        labels[0] = "Exibir pastas ocultas temporariamente";
        checked[0] = showHiddenFolders;
        for (int i = 0; i < albums.size(); i++) {
            AlbumItem album = albums.get(i);
            labels[i + 1] = album.name + " (" + album.count + ")";
            checked[i + 1] = !hiddenKeys.contains(album.key);
        }

        new AlertDialog.Builder(this)
                .setTitle("Exibir/ocultar pastas")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setNeutralButton("Mostrar todas", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit()
                                .putStringSet("hidden_folder_keys", new HashSet<String>())
                                .putBoolean("show_hidden_folders", false)
                                .apply();
                        showHiddenFolders = false;
                        loadAlbums();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        HashSet<String> nextHidden = new HashSet<>();
                        for (int i = 0; i < albums.size(); i++) {
                            if (!checked[i + 1]) {
                                nextHidden.add(albums.get(i).key);
                            }
                        }
                        showHiddenFolders = checked[0];
                        prefs.edit()
                                .putStringSet("hidden_folder_keys", nextHidden)
                                .putBoolean("show_hidden_folders", showHiddenFolders)
                                .apply();
                        loadAlbums();
                    }
                })
                .show();
    }

    private void setColumnCount(int nextCount) {
        int bounded = Math.max(2, Math.min(6, nextCount));
        if (bounded == columnCount) {
            return;
        }
        columnCount = bounded;
        prefs.edit().putInt("column_count", columnCount).apply();
        if (grid != null) {
            grid.setNumColumns(columnCount);
        }
    }

    private void sortAlbums(List<AlbumItem> albums) {
        if (SORT_RANDOM.equals(sortMode)) {
            Collections.shuffle(albums, new Random(42));
            if (sortDesc) {
                Collections.reverse(albums);
            }
            return;
        }

        Collections.sort(albums, new Comparator<AlbumItem>() {
            @Override
            public int compare(AlbumItem first, AlbumItem second) {
                int result;
                if (SORT_NAME.equals(sortMode)) {
                    result = first.name.compareToIgnoreCase(second.name);
                } else if (SORT_PATH.equals(sortMode)) {
                    result = first.path.compareToIgnoreCase(second.path);
                } else if (SORT_SIZE.equals(sortMode)) {
                    result = Long.compare(first.totalSize, second.totalSize);
                } else if (SORT_CREATED.equals(sortMode)) {
                    result = Long.compare(first.firstDate, second.firstDate);
                } else {
                    result = Long.compare(first.latestDate, second.latestDate);
                }
                return sortDesc ? -result : result;
            }
        });
    }

    private boolean matchesMediaFilter(MediaItem item) {
        String mime = item.mimeType.toLowerCase(Locale.US);
        String name = item.name.toLowerCase(Locale.US);
        if (isGif(mime, name)) {
            return showGifs;
        }
        if (isSvg(mime, name)) {
            return showSvgs;
        }
        if (isRaw(name)) {
            return showRaw;
        }
        if (item.isVideo()) {
            return showVideos;
        }
        if (item.isImage()) {
            return showImages || showPortraits;
        }
        return false;
    }

    private boolean isGif(String mime, String name) {
        return mime.equals("image/gif") || name.endsWith(".gif");
    }

    private boolean isSvg(String mime, String name) {
        return mime.equals("image/svg+xml") || name.endsWith(".svg");
    }

    private boolean isRaw(String name) {
        return name.endsWith(".dng")
                || name.endsWith(".raw")
                || name.endsWith(".cr2")
                || name.endsWith(".nef")
                || name.endsWith(".arw")
                || name.endsWith(".orf")
                || name.endsWith(".rw2");
    }

    private void updateEmptyText() {
        if (adapter == null || emptyView == null) {
            return;
        }
        emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestReadPermission() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (Build.VERSION.SDK_INT >= 34) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        requestPermissions(permissions.toArray(new String[0]), REQ_READ);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ && hasReadPermission()) {
            loadAlbums();
        } else {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("Permissao negada. Autorize o acesso a fotos e videos.");
        }
    }
}
