package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
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
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.Locale;

public class FileDetailActivity extends Activity {
    private File file;
    private String mimeType;

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
        content.setBackgroundColor(android.graphics.Color.BLACK);
        if (mimeType.startsWith("video/")) {
            VideoView video = new VideoView(this);
            video.setVideoURI(Uri.fromFile(file));
            MediaController controller = new MediaController(this);
            controller.setAnchorView(video);
            video.setMediaController(controller);
            content.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            video.requestFocus();
            video.start();
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

    private void restoreFile() {
        boolean restored = MediaActions.restoreHiddenFile(this, file, mimeType);
        Ui.toast(this, restored ? "Item restaurado." : "Nao foi possivel restaurar.");
        if (restored) {
            finish();
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Excluir oculto")
                .setMessage("Este arquivo oculto sera apagado definitivamente.")
                .setPositiveButton("Excluir", (dialog, which) -> {
                    boolean deleted = file.delete();
                    Ui.toast(this, deleted ? "Item excluido." : "Nao foi possivel excluir.");
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
