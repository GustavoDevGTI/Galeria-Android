package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.GridLayoutAnimationController;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlbumMediaActivity extends Activity {
    private static final int REQ_DELETE = 11;
    private static final int REQ_HIDE_DELETE = 12;
    private static final int REQ_MOVE_WRITE = 13;
    private static final int MAX_GRID_SPACING_DP = 8;
    private static final String GROUP_NONE = "none";
    private static final String GROUP_TYPE = "type";
    private static final String GROUP_EXTENSION = "extension";
    private static final String GROUP_DAY = "day";
    private static final String GROUP_MONTH = "month";

    private MediaGridAdapter adapter;
    private GridView grid;
    private EditText searchInput;
    private TextView emptyView;
    private File pendingHiddenCopy;
    private MediaItem pendingMoveItem;
    private String pendingMoveFolder;
    private String albumKey;
    private String albumName;
    private SharedPreferences prefs;
    private int gridSpacingDp;
    private boolean dragging;
    private int dragPosition = -1;
    private int savedFirstVisible;
    private View draggedView;
    private LinearLayout selectionBar;
    private LinearLayout selectionActions;
    private TextView selectAllText;
    private boolean dragMoved;
    private boolean showImages;
    private boolean showVideos;
    private boolean showGifs;
    private boolean showRaw;
    private boolean showSvgs;
    private boolean listMode;
    private String groupMode;
    private final ExecutorService mediaLoader = Executors.newSingleThreadExecutor();
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        albumKey = getIntent().getStringExtra("album_key");
        albumName = getIntent().getStringExtra("album_name");
        readAlbumOptions();
        if (albumName == null || albumName.isEmpty()) {
            albumName = "Album";
        }
        buildLayout();
        loadMedia(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            loadMedia(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mediaLoader.shutdownNow();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), statusBarHeight() + Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 6));

        ImageButton back = new ImageButton(this);
        back.setImageResource(com.galeria.android.R.drawable.ic_back);
        back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        back.setColorFilter(Ui.text(this));
        back.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50)));

        TextView title = Ui.title(this, albumName, 21);
        title.setSingleLine(false);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Ui.dp(this, 4);
        bar.addView(title, titleParams);
        root.addView(bar);

        gridSpacingDp = Math.max(0, Math.min(MAX_GRID_SPACING_DP, prefs.getInt(spacingKey(), 3)));
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.rounded(Ui.search(this), 18, this));
        searchBox.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        searchInput = new EditText(this);
        searchInput.setHint("Pesquisar nesta pasta");
        searchInput.setHintTextColor(Ui.muted(this));
        searchInput.setTextColor(Ui.text(this));
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.applyFilter(s.toString());
                updateEmptyState();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        ImageButton more = new ImageButton(this);
        more.setImageResource(com.galeria.android.R.drawable.ic_more_vertical);
        more.setBackgroundColor(Color.TRANSPARENT);
        more.setColorFilter(Ui.text(this));
        more.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showFolderMenu(view);
            }
        });
        searchBox.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        searchParams.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8));
        root.addView(searchBox, searchParams);

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
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 126));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        applyGridSpacing();
        AlphaAnimation itemFade = new AlphaAnimation(0f, 1f);
        itemFade.setDuration(120);
        itemFade.setInterpolator(new DecelerateInterpolator());
        grid.setLayoutAnimation(new GridLayoutAnimationController(itemFade, 0.035f, 0.035f));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setBackgroundColor(Ui.bg(this));
        adapter = new MediaGridAdapter(this);
        adapter.setSpacingDp(gridSpacingDp);
        applyViewMode();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!dragging) {
                    if (adapter.isSelectionMode()) {
                        adapter.toggleSelection(position);
                        updateSelectionUi();
                    } else {
                        openDetail(adapter.getItem(position), position);
                    }
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (!adapter.isSelectionMode()) {
                    enterSelectionMode();
                }
                adapter.selectPosition(position);
                updateSelectionUi();
                dragging = true;
                dragPosition = position;
                dragMoved = false;
                draggedView = view;
                view.setAlpha(0.55f);
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                return true;
            }
        });
        grid.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!dragging) {
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int target = grid.pointToPosition((int) event.getX(), (int) event.getY());
                    if (adapter.isSelectionMode() && target >= 0 && target != dragPosition && adapter.moveSelectedBlock(target)) {
                        dragPosition = target;
                        dragMoved = true;
                        animateGridMove();
                    } else if (!adapter.isSelectionMode() && target >= 0 && target != dragPosition && adapter.moveVisible(dragPosition, target)) {
                        dragPosition = target;
                        dragMoved = true;
                        animateGridMove();
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    finishDrag();
                    if (dragMoved) {
                        saveCustomOrder();
                        if (adapter.isSelectionMode()) {
                            exitSelectionMode();
                        }
                    }
                    return true;
                }
                return true;
            }
        });
        content.addView(grid);

        emptyView = Ui.label(this, "Nenhuma foto ou vídeo nesta pasta.");
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
                shareSelected();
            }
        });
        addSelectionAction("Favoritar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                favoriteSelected();
            }
        });
        addSelectionAction("Excluir", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDeleteSelected();
            }
        });
        addSelectionAction("Mover", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                askMoveSelected();
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

    private void showFolderMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Filtrar mídia");
        menu.getMenu().add("Agrupar por");
        menu.getMenu().add("Modo de visualização");
        menu.getMenu().add("Criar nova pasta");
        menu.getMenu().add("Aleatório");
        menu.getMenu().add("Espaçamento da grade");
        menu.setOnMenuItemClickListener(item -> {
            CharSequence title = item.getTitle();
            if ("Filtrar mídia".contentEquals(title)) {
                showMediaFilterDialog();
            } else if ("Agrupar por".contentEquals(title)) {
                showGroupDialog();
            } else if ("Modo de visualização".contentEquals(title)) {
                showViewModeDialog();
            } else if ("Criar nova pasta".contentEquals(title)) {
                showCreateFolderDialog();
            } else if ("Aleatório".contentEquals(title)) {
                startRandomPlayback();
            } else {
                showSpacingDialog();
            }
            return true;
        });
        menu.show();
    }

    private void readAlbumOptions() {
        showImages = prefs.getBoolean(optionKey("filter_images"), true);
        showVideos = prefs.getBoolean(optionKey("filter_videos"), true);
        showGifs = prefs.getBoolean(optionKey("filter_gifs"), true);
        showRaw = prefs.getBoolean(optionKey("filter_raw"), true);
        showSvgs = prefs.getBoolean(optionKey("filter_svg"), true);
        listMode = prefs.getBoolean(optionKey("list_mode"), false);
        groupMode = prefs.getString(optionKey("group_mode"), GROUP_NONE);
    }

    private void showMediaFilterDialog() {
        final String[] labels = new String[] { "Imagens", "Vídeos", "GIFs", "Imagens RAW", "SVGs" };
        final boolean[] checked = new boolean[] { showImages, showVideos, showGifs, showRaw, showSvgs };
        new AlertDialog.Builder(this)
                .setTitle("Filtrar mídia")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK", (dialog, which) -> {
                    showImages = checked[0];
                    showVideos = checked[1];
                    showGifs = checked[2];
                    showRaw = checked[3];
                    showSvgs = checked[4];
                    prefs.edit()
                            .putBoolean(optionKey("filter_images"), showImages)
                            .putBoolean(optionKey("filter_videos"), showVideos)
                            .putBoolean(optionKey("filter_gifs"), showGifs)
                            .putBoolean(optionKey("filter_raw"), showRaw)
                            .putBoolean(optionKey("filter_svg"), showSvgs)
                            .apply();
                    loadMedia(true);
                })
                .show();
    }

    private void showGroupDialog() {
        final String[] labels = new String[] {
                "Não agrupar arquivos",
                "Tipo de arquivo",
                "Extensão",
                "Data da foto (por dia)",
                "Data da foto (por mês)"
        };
        final String[] values = new String[] { GROUP_NONE, GROUP_TYPE, GROUP_EXTENSION, GROUP_DAY, GROUP_MONTH };
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(groupMode)) {
                selected = i;
                break;
            }
        }
        final int[] choice = new int[] { selected };
        new AlertDialog.Builder(this)
                .setTitle("Agrupar por")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> choice[0] = which)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK", (dialog, which) -> {
                    groupMode = values[choice[0]];
                    prefs.edit().putString(optionKey("group_mode"), groupMode).apply();
                    loadMedia(true);
                })
                .show();
    }

    private void showViewModeDialog() {
        final String[] labels = new String[] { "Grade", "Lista" };
        final int[] choice = new int[] { listMode ? 1 : 0 };
        new AlertDialog.Builder(this)
                .setTitle("Modo de visualização")
                .setSingleChoiceItems(labels, choice[0], (dialog, which) -> choice[0] = which)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK", (dialog, which) -> {
                    listMode = choice[0] == 1;
                    prefs.edit().putBoolean(optionKey("list_mode"), listMode).apply();
                    applyViewMode();
                })
                .show();
    }

    private void showCreateFolderDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), 0);

        TextView path = Ui.label(this, folderDisplayPath());
        path.setGravity(Gravity.LEFT);
        path.setTextSize(13);
        panel.addView(path, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Título");
        input.setTextColor(Ui.text(this));
        input.setHintTextColor(Ui.muted(this));
        panel.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));

        new AlertDialog.Builder(this)
                .setTitle("Criar nova pasta")
                .setView(panel)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("OK", (dialog, which) -> createFolder(input.getText().toString()))
                .show();
    }

    private void showSpacingDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 4));

        TextView hint = Ui.label(this, "Vale para todas as pastas. Esquerda: mais espaço   Direita: sem espaço");
        hint.setTextSize(12);
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(MAX_GRID_SPACING_DP);
        seekBar.setProgress(MAX_GRID_SPACING_DP - gridSpacingDp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                gridSpacingDp = MAX_GRID_SPACING_DP - progress;
                applyGridSpacing();
                prefs.edit().putInt(spacingKey(), gridSpacingDp).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        panel.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        new AlertDialog.Builder(this)
                .setTitle("Espaçamento da grade")
                .setView(panel)
                .setPositiveButton("OK", null)
                .show();
    }

    private void applyGridSpacing() {
        if (grid == null) {
            return;
        }
        int gap = Ui.dp(this, gridSpacingDp);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(gap, gap, gap, Ui.dp(this, 16));
        if (adapter != null) {
            adapter.setSpacingDp(gridSpacingDp);
        }
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
        boolean active = adapter != null && adapter.isSelectionMode() && adapter.selectedCount() > 0;
        if (selectionBar != null) {
            selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectionActions != null) {
            selectionActions.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectAllText != null && adapter != null) {
            selectAllText.setText((adapter.allVisibleSelected() ? "[x] " : "[ ] ") + "Selecionar tudo");
        }
        if (adapter != null && adapter.isSelectionMode() && adapter.selectedCount() == 0) {
            adapter.setSelectionMode(false);
        }
    }

    private void toggleSelectAll() {
        if (adapter == null) {
            return;
        }
        if (adapter.allVisibleSelected()) {
            exitSelectionMode();
        } else {
            adapter.selectAllVisible();
            updateSelectionUi();
        }
    }

    private void shareSelected() {
        List<MediaItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        ArrayList<android.net.Uri> uris = new ArrayList<>();
        for (MediaItem item : selected) {
            uris.add(item.uri);
        }
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.setType("*/*");
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Compartilhar"));
    }

    private void favoriteSelected() {
        List<MediaItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        HashSet<String> favorites = new HashSet<>(prefs.getStringSet("favorites", new HashSet<String>()));
        for (MediaItem item : selected) {
            favorites.add(item.uri.toString());
        }
        prefs.edit().putStringSet("favorites", favorites).apply();
        Ui.toast(this, selected.size() + " item(ns) adicionados aos favoritos.");
        exitSelectionMode();
    }

    private void confirmDeleteSelected() {
        final List<MediaItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Excluir selecionados")
                .setMessage("Tem certeza que deseja excluir " + selected.size() + " arquivo(s)?")
                .setPositiveButton("Excluir", (dialog, which) -> deleteSelected(selected))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void deleteSelected(List<MediaItem> selected) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            requestFileManagementAccess();
            return;
        }
        int deleted = 0;
        for (MediaItem item : selected) {
            if (MediaActions.requestPermanentDelete(this, item.uri, REQ_DELETE) == MediaActions.RESULT_DONE) {
                deleted++;
            }
        }
        Ui.toast(this, deleted + " item(ns) excluídos.");
        exitSelectionMode();
        loadMedia(true);
    }

    private void askMoveSelected() {
        final List<MediaItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ex.: Viagens");
        input.setTextColor(Ui.text(this));
        input.setHintTextColor(Ui.muted(this));
        new AlertDialog.Builder(this)
                .setTitle("Mover selecionados")
                .setMessage("Digite o nome da pasta de destino.")
                .setView(input)
                .setPositiveButton("Mover", (dialog, which) -> moveSelected(selected, input.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void moveSelected(List<MediaItem> selected, String folder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            requestFileManagementAccess();
            return;
        }
        int moved = 0;
        for (MediaItem item : selected) {
            if (MediaActions.moveToFolder(this, item, folder) == MediaActions.RESULT_DONE) {
                moved++;
            }
        }
        Ui.toast(this, moved + " item(ns) movidos.");
        exitSelectionMode();
        loadMedia(true);
    }

    private void loadMedia(boolean preserveScroll) {
        final int request = ++loadGeneration;
        final int targetPosition = preserveScroll && grid != null ? grid.getFirstVisiblePosition() : savedFirstVisible;
        final String query = searchInput == null ? "" : searchInput.getText().toString();
        if (adapter != null && adapter.getCount() == 0 && emptyView != null) {
            emptyView.setText("Carregando mídia...");
            emptyView.setVisibility(View.VISIBLE);
        }
        mediaLoader.execute(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> items = prepareAlbumMedia(MediaStoreRepository.loadMediaForAlbum(getApplicationContext(), albumKey));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (request != loadGeneration || isFinishing()) {
                            return;
                        }
                        adapter.submit(items);
                        if (searchInput != null) {
                            adapter.applyFilter(query);
                        }
                        updateEmptyState();
                        updateSelectionUi();
                        if (grid != null && items.size() <= 300) {
                            grid.scheduleLayoutAnimation();
                        }
                        if (grid != null && targetPosition > 0) {
                            grid.setSelection(targetPosition);
                        }
                    }
                });
            }
        });
    }

    private List<MediaItem> applyCustomOrder(List<MediaItem> items) {
        String saved = prefs.getString(orderKey(), "");
        if (saved.isEmpty()) {
            return items;
        }
        HashMap<String, MediaItem> byUri = new HashMap<>();
        for (MediaItem item : items) {
            byUri.put(item.uri.toString(), item);
        }
        ArrayList<MediaItem> ordered = new ArrayList<>();
        HashSet<String> used = new HashSet<>();
        String[] lines = saved.split("\\n");
        for (String line : lines) {
            MediaItem item = byUri.get(line);
            if (item != null) {
                ordered.add(item);
                used.add(line);
            }
        }
        for (MediaItem item : items) {
            if (!used.contains(item.uri.toString())) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    private List<MediaItem> prepareAlbumMedia(List<MediaItem> source) {
        ArrayList<MediaItem> filtered = new ArrayList<>();
        for (MediaItem item : applyCustomOrder(source)) {
            if (matchesMediaFilter(item)) {
                filtered.add(item);
            }
        }
        applyGrouping(filtered);
        return filtered;
    }

    private boolean matchesMediaFilter(MediaItem item) {
        if (item.isVideo()) {
            return showVideos;
        }
        String name = item.name.toLowerCase(Locale.US);
        String mime = item.mimeType.toLowerCase(Locale.US);
        if (mime.equals("image/gif") || name.endsWith(".gif")) {
            return showGifs;
        }
        if (mime.equals("image/svg+xml") || name.endsWith(".svg")) {
            return showSvgs;
        }
        if (isRawImage(name, mime)) {
            return showRaw;
        }
        return item.isImage() && showImages;
    }

    private boolean isRawImage(String name, String mime) {
        return mime.contains("raw")
                || name.endsWith(".dng")
                || name.endsWith(".raw")
                || name.endsWith(".cr2")
                || name.endsWith(".nef")
                || name.endsWith(".arw")
                || name.endsWith(".orf")
                || name.endsWith(".rw2");
    }

    private void applyGrouping(List<MediaItem> items) {
        if (GROUP_NONE.equals(groupMode)) {
            return;
        }
        Collections.sort(items, new Comparator<MediaItem>() {
            @Override
            public int compare(MediaItem first, MediaItem second) {
                int group = groupValue(first).compareTo(groupValue(second));
                if (group != 0) {
                    return group;
                }
                return Long.compare(second.dateAdded, first.dateAdded);
            }
        });
    }

    private String groupValue(MediaItem item) {
        if (GROUP_TYPE.equals(groupMode)) {
            return item.isVideo() ? "2_video" : "1_image";
        }
        if (GROUP_EXTENSION.equals(groupMode)) {
            return fileExtension(item.name);
        }
        if (GROUP_DAY.equals(groupMode)) {
            return dateGroup(item.dateAdded, true);
        }
        if (GROUP_MONTH.equals(groupMode)) {
            return dateGroup(item.dateAdded, false);
        }
        return "";
    }

    private String fileExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private String dateGroup(long seconds, boolean includeDay) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(Math.max(0L, seconds) * 1000L);
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = includeDay ? calendar.get(Calendar.DAY_OF_MONTH) : 0;
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
    }

    private void saveCustomOrder() {
        StringBuilder builder = new StringBuilder();
        for (MediaItem item : adapter.currentOrder()) {
            builder.append(item.uri.toString()).append('\n');
        }
        prefs.edit().putString(orderKey(), builder.toString()).apply();
        Ui.toast(this, "Ordem personalizada salva.");
    }

    private String orderKey() {
        return "custom_order_" + (albumKey == null ? "all" : albumKey);
    }

    private String spacingKey() {
        return "grid_spacing_global";
    }

    private void finishDrag() {
        dragging = false;
        dragPosition = -1;
        if (draggedView != null) {
            draggedView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(110).start();
            draggedView = null;
        }
        if (grid != null) {
            grid.invalidateViews();
        }
    }

    private void animateGridMove() {
        if (grid == null) {
            return;
        }
        grid.animate()
                .scaleX(0.996f)
                .scaleY(0.996f)
                .setDuration(55)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        grid.animate().scaleX(1f).scaleY(1f).setDuration(75).start();
                    }
                })
                .start();
    }

    private void updateEmptyState() {
        if (emptyView != null && adapter != null) {
            emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void startRandomPlayback() {
        if (adapter == null || adapter.getCount() == 0) {
            Ui.toast(this, "Nenhuma mídia para reproduzir.");
            return;
        }
        int position = new Random().nextInt(adapter.getCount());
        openDetail(adapter.getItem(position), position, true);
    }

    private void applyViewMode() {
        if (grid == null || adapter == null) {
            return;
        }
        if (listMode) {
            grid.setNumColumns(1);
            grid.setColumnWidth(getResources().getDisplayMetrics().widthPixels);
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        } else {
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(Ui.dp(this, 126));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        }
        adapter.setListMode(listMode);
        applyGridSpacing();
    }

    private String optionKey(String suffix) {
        return "album_" + suffix + "_" + (albumKey == null ? "all" : albumKey.hashCode());
    }

    private String folderDisplayPath() {
        String path = currentRelativeFolder();
        return path.isEmpty() ? "Armazenamento interno" : "Armazenamento interno/" + path;
    }

    private String currentRelativeFolder() {
        if (albumKey != null && !"all_media".equals(albumKey) && albumKey.endsWith("/")) {
            return albumKey;
        }
        if (adapter != null && adapter.getCount() > 0) {
            return adapter.getItem(0).relativePath;
        }
        return "";
    }

    private void createFolder(String rawName) {
        String cleanName = MediaActions.cleanFolderName(rawName);
        if (cleanName.isEmpty()) {
            Ui.toast(this, "Digite um nome para a pasta.");
            return;
        }
        String relative = currentRelativeFolder();
        File base = relative.isEmpty()
                ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                : new File(Environment.getExternalStorageDirectory(), relative);
        File target = new File(base, cleanName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            requestFileManagementAccess();
            return;
        }
        if (target.exists()) {
            Ui.toast(this, "A pasta já existe.");
            return;
        }
        if (MediaActions.createFolder(this, target)) {
            Ui.toast(this, "Pasta criada.");
        } else {
            Ui.toast(this, "Não foi possível criar a pasta.");
        }
    }

    private void requestFileManagementAccess() {
        new AlertDialog.Builder(this)
                .setTitle("Permitir gerenciamento de arquivos")
                .setMessage("Para criar pastas, mover e excluir arquivos no celular, ative o acesso total a arquivos para a Galeria.")
                .setPositiveButton("Permitir", (dialog, which) -> MediaActions.requestAllFilesAccess(this))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openDetail(MediaItem item, int position) {
        openDetail(item, position, false);
    }

    private void openDetail(MediaItem item, int position, boolean shuffleMode) {
        savedFirstVisible = grid == null ? 0 : grid.getFirstVisiblePosition();
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("uri", item.uri.toString());
        intent.putExtra("name", item.name);
        intent.putExtra("mime", item.mimeType);
        intent.putExtra("path", item.relativePath);
        intent.putExtra("album_key", albumKey);
        intent.putExtra("position", position);
        intent.putExtra("shuffle_mode", shuffleMode);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void showActions(final MediaItem item) {
        String[] actions = new String[] { "Excluir", "Ocultar", "Mover para pasta" };
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        confirmDelete(item);
                    } else if (which == 1) {
                        confirmHide(item);
                    } else {
                        askMoveFolder(item);
                    }
                })
                .show();
    }

    private void confirmDelete(final MediaItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir item")
                .setMessage("Tem certeza que deseja excluir este arquivo?")
                .setPositiveButton("Excluir", (dialog, which) -> deleteItem(item))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void deleteItem(MediaItem item) {
        int result = MediaActions.requestDelete(this, item.uri, REQ_DELETE);
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item excluído.");
            loadMedia(true);
        } else if (result == MediaActions.RESULT_FAILED) {
            requestFileManagementAccess();
        }
    }

    private void confirmHide(final MediaItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Ocultar item")
                .setMessage("O arquivo será copiado para a área oculta do app e removido da galeria pública.")
                .setPositiveButton("Ocultar", (dialog, which) -> hideItem(item))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void hideItem(MediaItem item) {
        pendingHiddenCopy = MediaActions.copyToHidden(this, item);
        if (pendingHiddenCopy == null) {
            Ui.toast(this, "Não foi possível copiar para ocultos.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int result = MediaActions.requestDelete(this, item.uri, REQ_HIDE_DELETE);
            if (result == MediaActions.RESULT_DONE) {
                Ui.toast(this, "Item ocultado.");
                pendingHiddenCopy = null;
                loadMedia(true);
            } else if (result == MediaActions.RESULT_FAILED) {
                pendingHiddenCopy.delete();
                pendingHiddenCopy = null;
                requestFileManagementAccess();
            }
        } else {
            int deleted = getContentResolver().delete(item.uri, null, null);
            if (deleted > 0) {
                Ui.toast(this, "Item ocultado.");
            } else {
                pendingHiddenCopy.delete();
                Ui.toast(this, "Não foi possível remover o original.");
            }
            pendingHiddenCopy = null;
            loadMedia(true);
        }
    }

    private void askMoveFolder(final MediaItem item) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ex.: Viagens");
        input.setTextColor(Ui.text(this));
        input.setHintTextColor(Ui.muted(this));

        new AlertDialog.Builder(this)
                .setTitle("Mover para pasta")
                .setMessage("Digite o nome da pasta dentro de Fotos ou Vídeos.")
                .setView(input)
                .setPositiveButton("Mover", (dialog, which) -> moveItem(item, input.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void moveItem(MediaItem item, String folder) {
        pendingMoveItem = item;
        pendingMoveFolder = folder;
        int result = MediaActions.moveToFolder(this, item, folder);
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item movido.");
            pendingMoveItem = null;
            pendingMoveFolder = null;
            loadMedia(true);
        } else if (result == MediaActions.RESULT_NEEDS_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaActions.requestWrite(this, item.uri, REQ_MOVE_WRITE);
        } else {
            requestFileManagementAccess();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HIDE_DELETE) {
            if (resultCode == RESULT_OK) {
                Ui.toast(this, "Item ocultado.");
            } else if (pendingHiddenCopy != null) {
                pendingHiddenCopy.delete();
                Ui.toast(this, "Ocultação cancelada.");
            }
            pendingHiddenCopy = null;
            loadMedia(true);
        } else if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                Ui.toast(this, "Item excluído.");
            }
            loadMedia(true);
        } else if (requestCode == REQ_MOVE_WRITE && pendingMoveItem != null) {
            if (resultCode == RESULT_OK) {
                int result = MediaActions.moveToFolder(this, pendingMoveItem, pendingMoveFolder);
                Ui.toast(this, result == MediaActions.RESULT_DONE ? "Item movido." : "Não foi possível mover.");
            }
            pendingMoveItem = null;
            pendingMoveFolder = null;
            loadMedia(true);
        }
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return Ui.dp(this, 24);
    }
}
