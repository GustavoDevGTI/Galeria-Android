package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

public class AlbumMediaActivity extends Activity {
    private static final int REQ_DELETE = 11;
    private static final int REQ_HIDE_DELETE = 12;
    private static final int REQ_MOVE_WRITE = 13;

    private MediaGridAdapter adapter;
    private TextView emptyView;
    private File pendingHiddenCopy;
    private MediaItem pendingMoveItem;
    private String pendingMoveFolder;
    private String albumKey;
    private String albumName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        albumKey = getIntent().getStringExtra("album_key");
        albumName = getIntent().getStringExtra("album_name");
        if (albumName == null || albumName.isEmpty()) {
            albumName = "Album";
        }
        buildLayout();
        loadMedia();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMedia();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPadding(bar, 10, 16, 14, 10);

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

        TextView title = Ui.title(this, albumName, 22);
        title.setSingleLine(false);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Ui.dp(this, 4);
        bar.addView(title, titleParams);
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 118));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 2));
        grid.setVerticalSpacing(Ui.dp(this, 2));
        grid.setClipToPadding(false);
        grid.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 16));
        grid.setBackgroundColor(Ui.BG);
        adapter = new MediaGridAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openDetail(adapter.getItem(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showActions(adapter.getItem(position));
                return true;
            }
        });
        content.addView(grid);

        emptyView = Ui.label(this, "Nenhuma foto ou video neste album.");
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void loadMedia() {
        List<MediaItem> items = MediaStoreRepository.loadMediaForAlbum(this, albumKey);
        adapter.submit(items);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openDetail(MediaItem item) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("uri", item.uri.toString());
        intent.putExtra("name", item.name);
        intent.putExtra("mime", item.mimeType);
        intent.putExtra("path", item.relativePath);
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
                .setMessage("Este arquivo sera removido do dispositivo.")
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
            loadMedia();
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
            loadMedia();
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
            loadMedia();
        } else if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                Ui.toast(this, "Item excluido.");
            }
            loadMedia();
        } else if (requestCode == REQ_MOVE_WRITE && pendingMoveItem != null) {
            if (resultCode == RESULT_OK) {
                int result = MediaActions.moveToFolder(this, pendingMoveItem, pendingMoveFolder);
                Ui.toast(this, result == MediaActions.RESULT_DONE ? "Item movido." : "Nao foi possivel mover.");
            }
            pendingMoveItem = null;
            pendingMoveFolder = null;
            loadMedia();
        }
    }
}
