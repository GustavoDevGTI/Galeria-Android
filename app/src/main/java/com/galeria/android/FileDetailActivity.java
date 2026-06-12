package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

public class FileDetailActivity extends Activity {
    private File file;
    private String mimeType;
    private ExoPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        file = new File(getIntent().getStringExtra("path"));
        mimeType = mimeFor(file);
        buildLayout();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPadding(bar, 10, 8, 10, 8);

        TextView back = Ui.title(this, "Voltar", 16);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)));

        TextView title = Ui.title(this, file.getName(), 17);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.BLACK);
        if (mimeType.startsWith("video/")) {
            PlayerView playerView = new PlayerView(this);
            playerView.setBackgroundColor(Color.BLACK);
            playerView.setUseController(false);
            playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            player = new ExoPlayer.Builder(this).build();
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
            playerView.setPlayer(player);
            content.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            player.prepare();
            player.setPlayWhenReady(getSharedPreferences(Ui.PREFS, MODE_PRIVATE).getBoolean("autoplay_videos", true));
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            content.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Ui.setPadding(actions, 10, 10, 10, 12);

        Button restore = Ui.button(this, "Restaurar");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                restoreFile();
            }
        });
        actions.addView(restore, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));

        Button delete = Ui.button(this, "Excluir");
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDelete();
            }
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        deleteParams.leftMargin = Ui.dp(this, 8);
        actions.addView(delete, deleteParams);
        root.addView(actions);

        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void restoreFile() {
        boolean restored = MediaActions.restoreHiddenFile(this, file, mimeType);
        Ui.toast(this, restored ? "Item restaurado." : "Não foi possível restaurar.");
        if (restored) {
            finish();
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Excluir oculto")
                .setMessage("Este arquivo oculto será apagado definitivamente.")
                .setPositiveButton("Excluir", (dialog, which) -> {
                    boolean deleted = file.delete();
                    Ui.toast(this, deleted ? "Item excluído." : "Não foi possível excluir.");
                    if (deleted) {
                        finish();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    static String mimeFor(File file) {
        String name = file.getName();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            ext = name.substring(dot + 1).toLowerCase(Locale.US);
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) {
            return mime;
        }
        if (ext.equals("mp4") || ext.equals("mkv") || ext.equals("webm") || ext.equals("3gp")) {
            return "video/mp4";
        }
        return "image/jpeg";
    }
}
