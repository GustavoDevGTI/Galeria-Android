package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class AlbumMediaActivity extends Activity {
    private static final int REQ_DELETE = 11;
    private static final int REQ_HIDE_DELETE = 12;
    private static final int REQ_MOVE_WRITE = 13;
    private static final int MAX_GRID_SPACING_DP = 8;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        albumKey = getIntent().getStringExtra("album_key");
        albumName = getIntent().getStringExtra("album_name");
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

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), statusBarHeight() + Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 6));

        ImageButton back = new ImageButton(this);
        back.setImageResource(com.galeria.android.R.drawable.ic_back);
        back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        back.setColorFilter(Ui.TEXT);
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

        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 126));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        applyGridSpacing();
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setBackgroundColor(Ui.BG);
        adapter = new MediaGridAdapter(this);
        adapter.setSpacingDp(gridSpacingDp);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!dragging) {
                    openDetail(adapter.getItem(position), position);
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                dragging = true;
                dragPosition = position;
                view.setAlpha(0.55f);
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
                    if (target >= 0 && target != dragPosition && adapter.moveVisible(dragPosition, target)) {
                        dragPosition = target;
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    dragging = false;
                    dragPosition = -1;
                    saveCustomOrder();
                    return true;
                }
                return true;
            }
        });
        content.addView(grid);

        emptyView = Ui.label(this, "Nenhuma foto ou video nesta pasta.");
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void showFolderMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Espacamento da grade");
        menu.setOnMenuItemClickListener(item -> {
            showSpacingDialog();
            return true;
        });
        menu.show();
    }

    private void showSpacingDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 4));

        TextView hint = Ui.label(this, "Esquerda: mais espaco   Direita: sem espaco");
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
                .setTitle("Espacamento da grade")
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

    private void loadMedia(boolean preserveScroll) {
        int targetPosition = preserveScroll && grid != null ? grid.getFirstVisiblePosition() : savedFirstVisible;
        List<MediaItem> items = applyCustomOrder(MediaStoreRepository.loadMediaForAlbum(this, albumKey));
        adapter.submit(items);
        if (searchInput != null) {
            adapter.applyFilter(searchInput.getText().toString());
        }
        updateEmptyState();
        if (grid != null && targetPosition > 0) {
            grid.setSelection(targetPosition);
        }
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
        return "grid_spacing_" + (albumKey == null ? "all" : albumKey);
    }

    private void updateEmptyState() {
        if (emptyView != null && adapter != null) {
            emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void openDetail(MediaItem item, int position) {
        savedFirstVisible = grid == null ? 0 : grid.getFirstVisiblePosition();
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("uri", item.uri.toString());
        intent.putExtra("name", item.name);
        intent.putExtra("mime", item.mimeType);
        intent.putExtra("path", item.relativePath);
        intent.putExtra("album_key", albumKey);
        intent.putExtra("position", position);
        startActivity(intent);
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
                .setPositiveButton("Excluir", (dialog, which) -> MediaActions.requestDelete(this, item.uri, REQ_DELETE))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmHide(final MediaItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Ocultar item")
                .setMessage("O arquivo sera copiado para a area oculta do app e removido da galeria publica.")
                .setPositiveButton("Ocultar", (dialog, which) -> hideItem(item))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void hideItem(MediaItem item) {
        pendingHiddenCopy = MediaActions.copyToHidden(this, item);
        if (pendingHiddenCopy == null) {
            Ui.toast(this, "Nao foi possivel copiar para ocultos.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaActions.requestDelete(this, item.uri, REQ_HIDE_DELETE);
        } else {
            int deleted = getContentResolver().delete(item.uri, null, null);
            if (deleted > 0) {
                Ui.toast(this, "Item ocultado.");
            } else {
                pendingHiddenCopy.delete();
                Ui.toast(this, "Nao foi possivel remover o original.");
            }
            pendingHiddenCopy = null;
            loadMedia(true);
        }
    }

    private void askMoveFolder(final MediaItem item) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ex.: Viagens");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);

        new AlertDialog.Builder(this)
                .setTitle("Mover para pasta")
                .setMessage("Digite o nome da pasta dentro de Fotos ou Videos.")
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
            Ui.toast(this, "Nao foi possivel mover.");
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
                Ui.toast(this, "Ocultacao cancelada.");
            }
            pendingHiddenCopy = null;
            loadMedia(true);
        } else if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                Ui.toast(this, "Item excluido.");
            }
            loadMedia(true);
        } else if (requestCode == REQ_MOVE_WRITE && pendingMoveItem != null) {
            if (resultCode == RESULT_OK) {
                int result = MediaActions.moveToFolder(this, pendingMoveItem, pendingMoveFolder);
                Ui.toast(this, result == MediaActions.RESULT_DONE ? "Item movido." : "Nao foi possivel mover.");
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
