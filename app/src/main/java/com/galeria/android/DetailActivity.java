package com.galeria.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
    private SharedPreferences prefs;
    private VideoView currentVideo;
    private String currentVideoKey;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        Uri uri = Uri.parse(getIntent().getStringExtra("uri"));
        String name = getIntent().getStringExtra("name");
        String mime = getIntent().getStringExtra("mime");
        String path = getIntent().getStringExtra("path");
        buildLayout(uri, name, mime == null ? "" : mime, path == null ? "" : path);
    }

    private void buildLayout(Uri uri, String name, String mime, String path) {
        applyFullscreenSettings();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

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
        content.setBackgroundColor(prefs.getBoolean("fullscreen_black_bg", true) ? android.graphics.Color.BLACK : Ui.bg(this));
        if (prefs.getBoolean("swipe_down_to_close", true)) {
            content.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        downY = event.getY();
                    } else if (event.getAction() == MotionEvent.ACTION_UP && event.getY() - downY > Ui.dp(DetailActivity.this, 120)) {
                        finish();
                        return true;
                    }
                    return true;
                }
            });
        }
        if (mime.startsWith("video/")) {
            showVideo(content, uri);
        } else {
            showImage(content, uri);
        }
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (prefs.getBoolean("show_fullscreen_details", false)) {
            TextView info = Ui.label(this, path.isEmpty() ? mime : path + "  " + mime);
            Ui.setPadding(info, 12, 8, 12, 12);
            root.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (currentVideo != null && currentVideoKey != null && prefs.getBoolean("remember_video_position", true)) {
            prefs.edit().putInt(currentVideoKey, currentVideo.getCurrentPosition()).apply();
        }
    }

    private void applyFullscreenSettings() {
        if (prefs.getBoolean("fullscreen_max_brightness", false)) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 1f;
            getWindow().setAttributes(params);
        }
        if (prefs.getBoolean("fullscreen_hide_system_ui", false)) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
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
        final VideoView video = new VideoView(this);
        currentVideo = video;
        currentVideoKey = "video_pos_" + uri.toString().hashCode();
        video.setVideoURI(uri);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        content.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video.requestFocus();
        video.setOnPreparedListener(mp -> {
            mp.setLooping(prefs.getBoolean("loop_videos", false));
            if (prefs.getBoolean("remember_video_position", true)) {
                int savedPosition = prefs.getInt(currentVideoKey, 0);
                if (savedPosition > 0) {
                    video.seekTo(savedPosition);
                }
            }
            if (prefs.getBoolean("autoplay_videos", true)) {
                video.start();
            }
        });
    }
}
