package com.galeria.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_READ = 10;
    private static final String PREFS = "gallery_albums";
    private static final String PREF_ALL_FILES_PROMPTED = "all_files_prompted";
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
    private LinearLayout selectionBar;
    private LinearLayout selectionActions;
    private TextView selectAllText;
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
    private long lastColumnGestureMs;
    private final ExecutorService mediaLoader = Executors.newSingleThreadExecutor();
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();
        buildLayout();
        if (hasReadPermission()) {
            ensureFileManagementAccess(false);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mediaLoader.shutdownNow();
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
        topParams.setMargins(Ui.dp(this, 24), statusBarHeight() + Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 14));
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

        selectionBar = new LinearLayout(this);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setBackground(Ui.rounded(Ui.surface(this), 8, this));
        Ui.setPadding(selectionBar, 14, 8, 14, 8);
        selectAllText = Ui.title(this, "[ ] Selecionar tudo", 15);
        selectAllText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleSelectAll();
            }
        });
        selectionBar.addView(selectAllText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cancelSelection = Ui.title(this, "Cancelar", 15);
        cancelSelection.setGravity(Gravity.RIGHT);
        cancelSelection.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exitSelectionMode();
            }
        });
        selectionBar.addView(cancelSelection, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 38)));
        selectionBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams selectionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        selectionParams.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8));
        root.addView(selectionBar, selectionParams);

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
                if (adapter.isSelectionMode()) {
                    adapter.toggleSelection(position);
                    updateSelectionUi();
                    return;
                }
                AlbumItem album = adapter.getItem(position);
                Intent intent = new Intent(MainActivity.this, AlbumMediaActivity.class);
                intent.putExtra("album_key", album.key);
                intent.putExtra("album_name", album.name);
                startActivity(intent);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                enterSelectionMode();
                adapter.selectPosition(position);
                updateSelectionUi();
                view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(90).start();
                return true;
            }
        });
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                long now = System.currentTimeMillis();
                if (now - lastColumnGestureMs < 180L) {
                    return false;
                }
                if (detector.getScaleFactor() < 0.92f) {
                    lastColumnGestureMs = now;
                    setColumnCount(columnCount + 1);
                    return true;
                }
                if (detector.getScaleFactor() > 1.08f) {
                    lastColumnGestureMs = now;
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

        selectionActions = new LinearLayout(this);
        selectionActions.setGravity(Gravity.CENTER);
        selectionActions.setBackgroundColor(Ui.bg(this));
        Ui.setPadding(selectionActions, 10, 8, 10, 12);
        addSelectionAction("Compartilhar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shareSelectedAlbums();
            }
        });
        addSelectionAction("Favoritar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                favoriteSelectedAlbums();
            }
        });
        addSelectionAction("Excluir", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDeleteSelectedAlbums();
            }
        });
        addSelectionAction("Mover", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                askMoveSelectedAlbums();
            }
        });
        selectionActions.setVisibility(View.GONE);
        root.addView(selectionActions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void addSelectionAction(String label, View.OnClickListener listener) {
        TextView button = Ui.title(this, label, 14);
        button.setTextColor(Ui.accent(this));
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.rounded(Ui.surface(this), 8, this));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
        params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        selectionActions.addView(button, params);
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
        menu.getMenu().add("Filtrar mídia");
        menu.getMenu().add("Organização de pastas");
        menu.getMenu().add("Exibir/ocultar pastas");
        menu.getMenu().add("Criar nova pasta");
        menu.getMenu().add("Configurações");
        menu.getMenu().add("Ocultos");
        menu.getMenu().add("Atualizar");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Ordenar por".equals(title)) {
                showSortDialog();
            } else if ("Filtrar mídia".equals(title)) {
                showMediaFilterDialog();
            } else if ("Organização de pastas".equals(title)) {
                showFolderOrganizationDialog();
            } else if ("Exibir/ocultar pastas".equals(title)) {
                showFolderVisibilityDialog();
            } else if ("Criar nova pasta".equals(title)) {
                startActivity(new Intent(this, FolderPickerActivity.class));
            } else if ("Configurações".equals(title)) {
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

    private void enterSelectionMode() {
        adapter.setSelectionMode(true);
        updateSelectionUi();
    }

    private void exitSelectionMode() {
        adapter.clearSelection();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        boolean active = adapter != null && adapter.isSelectionMode();
        if (selectionBar != null) {
            selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectionActions != null) {
            selectionActions.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectAllText != null && adapter != null) {
            selectAllText.setText((adapter.allVisibleSelected() ? "[x] " : "[ ] ")
                    + "Selecionar tudo"
                    + (adapter.selectedCount() > 0 ? " (" + adapter.selectedCount() + ")" : ""));
        }
        if (active && adapter != null && adapter.selectedCount() == 0) {
            exitSelectionMode();
        }
    }

    private void toggleSelectAll() {
        if (adapter == null) {
            return;
        }
        if (adapter.allVisibleSelected()) {
            adapter.clearSelection();
        } else {
            adapter.selectAllVisible();
        }
        updateSelectionUi();
    }

    private void shareSelectedAlbums() {
        final List<AlbumItem> albums = adapter.selectedAlbums();
        if (albums.isEmpty()) {
            return;
        }
        mediaLoader.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Uri> uris = new ArrayList<>();
                for (MediaItem item : mediaForAlbums(albums)) {
                    uris.add(item.uri);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (uris.isEmpty()) {
                            Ui.toast(MainActivity.this, "Nenhuma mídia para compartilhar.");
                            return;
                        }
                        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        share.setType("*/*");
                        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share, "Compartilhar"));
                    }
                });
            }
        });
    }

    private void favoriteSelectedAlbums() {
        final List<AlbumItem> albums = adapter.selectedAlbums();
        if (albums.isEmpty()) {
            return;
        }
        mediaLoader.execute(new Runnable() {
            @Override
            public void run() {
                List<MediaItem> items = mediaForAlbums(albums);
                final int count = items.size();
                HashSet<String> favorites = new HashSet<>(prefs.getStringSet("favorites", new HashSet<String>()));
                for (MediaItem item : items) {
                    favorites.add(item.uri.toString());
                }
                prefs.edit().putStringSet("favorites", favorites).apply();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(MainActivity.this, count + " item(ns) adicionados aos favoritos.");
                        exitSelectionMode();
                    }
                });
            }
        });
    }

    private void confirmDeleteSelectedAlbums() {
        final List<AlbumItem> albums = adapter.selectedAlbums();
        if (albums.isEmpty()) {
            return;
        }
        int visibleCount = 0;
        for (AlbumItem album : albums) {
            visibleCount += album.count;
        }
        new AlertDialog.Builder(this)
                .setTitle("Excluir álbuns")
                .setMessage("Tem certeza que deseja excluir as mídias de " + albums.size()
                        + " álbum(ns)? Cerca de " + visibleCount + " item(ns) serão removidos.")
                .setPositiveButton("Excluir", (dialog, which) -> deleteSelectedAlbums(albums))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void deleteSelectedAlbums(final List<AlbumItem> albums) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            ensureFileManagementAccess(true);
            return;
        }
        mediaLoader.execute(new Runnable() {
            @Override
            public void run() {
                int deleted = 0;
                for (MediaItem item : mediaForAlbums(albums)) {
                    if (MediaActions.requestPermanentDelete(MainActivity.this, item.uri, REQ_READ) == MediaActions.RESULT_DONE) {
                        deleted++;
                    }
                }
                final int deletedCount = deleted;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(MainActivity.this, deletedCount + " item(ns) excluídos.");
                        exitSelectionMode();
                        loadAlbums();
                    }
                });
            }
        });
    }

    private void askMoveSelectedAlbums() {
        final List<AlbumItem> albums = adapter.selectedAlbums();
        if (albums.isEmpty()) {
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ex.: Viagens");
        input.setTextColor(Ui.text(this));
        input.setHintTextColor(Ui.muted(this));
        new AlertDialog.Builder(this)
                .setTitle("Mover álbuns")
                .setMessage("Digite o nome da pasta de destino.")
                .setView(input)
                .setPositiveButton("Mover", (dialog, which) -> moveSelectedAlbums(albums, input.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void moveSelectedAlbums(final List<AlbumItem> albums, final String folder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            ensureFileManagementAccess(true);
            return;
        }
        mediaLoader.execute(new Runnable() {
            @Override
            public void run() {
                int moved = 0;
                for (MediaItem item : mediaForAlbums(albums)) {
                    if (MediaActions.moveToFolder(MainActivity.this, item, folder) == MediaActions.RESULT_DONE) {
                        moved++;
                    }
                }
                final int movedCount = moved;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(MainActivity.this, movedCount + " item(ns) movidos.");
                        exitSelectionMode();
                        loadAlbums();
                    }
                });
            }
        });
    }

    private List<MediaItem> mediaForAlbums(List<AlbumItem> albums) {
        HashSet<String> keys = new HashSet<>();
        boolean allMedia = false;
        for (AlbumItem album : albums) {
            if ("all_media".equals(album.key)) {
                allMedia = true;
            }
            keys.add(album.key);
        }
        ArrayList<MediaItem> items = new ArrayList<>();
        for (MediaItem item : MediaStoreRepository.loadMedia(getApplicationContext())) {
            if ((allMedia || keys.contains(item.albumKey)) && matchesMediaFilter(item)) {
                items.add(item);
            }
        }
        return items;
    }

    private void loadAlbums() {
        final int request = ++loadGeneration;
        final boolean searchAllFiles = prefs.getBoolean("search_all_files", false);
        final boolean includeHidden = showHiddenFolders;
        final Set<String> hiddenKeys = new HashSet<>(prefs.getStringSet("hidden_folder_keys", new HashSet<String>()));
        final String query = searchInput == null ? "" : searchInput.getText().toString();
        if (adapter != null && adapter.getCount() == 0 && emptyView != null) {
            emptyView.setText("Carregando mídia...");
            emptyView.setVisibility(View.VISIBLE);
        }
        mediaLoader.execute(new Runnable() {
            @Override
            public void run() {
                List<MediaItem> media = MediaStoreRepository.loadMedia(getApplicationContext());
                ArrayList<MediaItem> filteredMedia = new ArrayList<>();
                for (MediaItem item : media) {
                    if (matchesMediaFilter(item)) {
                        filteredMedia.add(item);
                    }
                }

                List<AlbumItem> albums;
                if (searchAllFiles) {
                    albums = new ArrayList<>();
                    MediaItem cover = filteredMedia.isEmpty() ? null : filteredMedia.get(0);
                    long latest = 0;
                    long first = Long.MAX_VALUE;
                    long size = 0;
                    for (MediaItem item : filteredMedia) {
                        latest = Math.max(latest, item.dateAdded);
                        if (item.dateAdded > 0) {
                            first = Math.min(first, item.dateAdded);
                        }
                        size += Math.max(0, item.size);
                    }
                    albums.add(new AlbumItem("all_media", "Todos os arquivos", filteredMedia.size(), cover, latest, first == Long.MAX_VALUE ? latest : first, size, ""));
                } else {
                    albums = MediaStoreRepository.buildAlbums(filteredMedia);
                }
                if (!includeHidden && !hiddenKeys.isEmpty()) {
                    ArrayList<AlbumItem> visibleAlbums = new ArrayList<>();
                    for (AlbumItem album : albums) {
                        if (!hiddenKeys.contains(album.key)) {
                            visibleAlbums.add(album);
                        }
                    }
                    albums = visibleAlbums;
                }
                sortAlbums(albums);
                final List<AlbumItem> result = albums;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (request != loadGeneration || isFinishing()) {
                            return;
                        }
                        adapter.submit(result);
                        if (searchInput != null) {
                            adapter.applyFilter(query);
                        }
                        updateEmptyText();
                    }
                });
            }
        });
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
        if (selectionBar != null) {
            selectionBar.setBackground(Ui.rounded(Ui.surface(this), 8, this));
        }
        if (selectionActions != null) {
            selectionActions.setBackgroundColor(Ui.bg(this));
            for (int i = 0; i < selectionActions.getChildCount(); i++) {
                View child = selectionActions.getChildAt(i);
                child.setBackground(Ui.rounded(Ui.surface(this), 8, this));
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(Ui.accent(this));
                }
            }
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
                "Data de modificação",
                "Data de criação",
                "Aleatório"
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
                "Vídeos",
                "GIFs",
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
                .setTitle("Filtrar mídia")
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
                .setTitle("Organização de pastas")
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
            grid.animate()
                    .alpha(0.82f)
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(85)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            grid.setNumColumns(columnCount);
                            grid.scheduleLayoutAnimation();
                            grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130).start();
                        }
                    })
                    .start();
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
        emptyView.setText("Nenhuma pasta encontrada.");
        emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestReadPermission() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
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
            ensureFileManagementAccess(false);
            loadAlbums();
        } else {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("Autorize acesso completo a fotos e vídeos para carregar a galeria.");
        }
    }

    private void ensureFileManagementAccess(boolean force) {
        if (MediaActions.hasAllFilesAccess(this)) {
            return;
        }
        if (!force && prefs.getBoolean(PREF_ALL_FILES_PROMPTED, false)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Permitir gerenciamento de arquivos")
                .setMessage("Para excluir, mover, copiar e criar pastas no celular, ative o acesso total a arquivos para a Galeria.")
                .setPositiveButton("Permitir", (dialog, which) -> {
                    prefs.edit().putBoolean(PREF_ALL_FILES_PROMPTED, true).apply();
                    MediaActions.requestAllFilesAccess(this);
                })
                .setNegativeButton("Agora não", (dialog, which) -> prefs.edit().putBoolean(PREF_ALL_FILES_PROMPTED, true).apply())
                .show();
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return Ui.dp(this, 24);
    }
}
