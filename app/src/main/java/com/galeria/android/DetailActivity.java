package com.galeria.android;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = Uri.parse(getIntent().getStringExtra("uri"));
        String name = getIntent().getStringExtra("name");
        String mime = getIntent().getStringExtra("mime");
        String path = getIntent().getStringExtra("path");
        buildLayout(uri, name, mime == null ? "" : mime, path == null ? "" : path);
    }

    private void buildLayout(Uri uri, String name, String mime, String path) {
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

        TextView title = Ui.title(this, name == null ? "Midia" : name, 17);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(android.graphics.Color.BLACK);
        if (mime.startsWith("video/")) {
            showVideo(content, uri);
        } else {
            showImage(content, uri);
        }
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView info = Ui.label(this, path.isEmpty() ? mime : path + "  " + mime);
        Ui.setPadding(info, 12, 8, 12, 12);
        root.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void showImage(FrameLayout content, final Uri uri) {
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final android.graphics.Bitmap bitmap;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bitmap = getContentResolver().loadThumbnail(uri, new Size(1800, 1800), null);
                    } else {
                        bitmap = android.graphics.BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
                    }
                    image.post(new Runnable() {
                        @Override
                        public void run() {
                            image.setImageBitmap(bitmap);
                        }
                    });
                } catch (Exception exception) {
                    image.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(DetailActivity.this, "Nao foi possivel abrir a imagem.");
                        }
                    });
                }
            }
        });
    }

    private void showVideo(FrameLayout content, Uri uri) {
        VideoView video = new VideoView(this);
        video.setVideoURI(uri);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        content.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video.requestFocus();
        if (getSharedPreferences(Ui.PREFS, MODE_PRIVATE).getBoolean("autoplay_videos", true)) {
            video.start();
        }
    }
}
