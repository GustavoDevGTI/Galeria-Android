package com.galeria.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_READ = 10;

    private AlbumGridAdapter adapter;
    private TextView emptyView;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        if (hasReadPermission()) {
            loadAlbums();
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(Ui.rounded(Ui.SEARCH, 34, this));
        Ui.setPadding(top, 16, 5, 10, 5);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 70));
        topParams.setMargins(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 14));
        root.addView(top, topParams);

        ImageButton searchIcon = iconButton(com.galeria.android.R.drawable.ic_search);
        searchIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                searchInput.requestFocus();
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        top.addView(searchIcon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 52)));

        searchInput = new EditText(this);
        searchInput.setHint("Pesquisar pastas");
        searchInput.setHintTextColor(0x99F5F7FA);
        searchInput.setTextColor(Ui.TEXT);
        searchInput.setTextSize(20);
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

        ImageButton camera = iconButton(com.galeria.android.R.drawable.ic_camera);
        camera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ui.toast(MainActivity.this, "Camera sera adicionada na proxima etapa.");
            }
        });
        top.addView(camera, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 52)));

        ImageButton refresh = iconButton(com.galeria.android.R.drawable.ic_image);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadAlbums();
            }
        });
        top.addView(refresh, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 52)));

        ImageButton more = iconButton(com.galeria.android.R.drawable.ic_more_vertical);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu(view);
            }
        });
        top.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 52)));

        FrameLayout content = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setVerticalSpacing(Ui.dp(this, 16));
        grid.setClipToPadding(false);
        grid.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 20));
        grid.setBackgroundColor(Ui.BG);
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
        button.setColorFilter(Ui.TEXT);
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        return button;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Ocultos");
        menu.getMenu().add("Atualizar");
        menu.setOnMenuItemClickListener(item -> {
            if ("Ocultos".contentEquals(item.getTitle())) {
                startActivity(new Intent(this, HiddenActivity.class));
            } else {
                loadAlbums();
            }
            return true;
        });
        menu.show();
    }

    private void loadAlbums() {
        List<AlbumItem> albums = MediaStoreRepository.loadAlbums(this);
        adapter.submit(albums);
        if (searchInput != null) {
            adapter.applyFilter(searchInput.getText().toString());
        }
        updateEmptyText();
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
