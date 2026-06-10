package com.galeria.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.widget.TextView;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<MediaItem> videoQueue = new ArrayList<>();
    private final ArrayList<TextView> speedButtons = new ArrayList<>();
    private SharedPreferences prefs;
    private ExoPlayer currentPlayer;
    private FrameLayout content;
    private TextView title;
    private TextView playButton;
    private String currentVideoKey;
    private boolean videoPositionRestored;
    private int currentIndex;
    private float downY;
    private float downX;
    private float playbackSpeed = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        Uri uri = Uri.parse(getIntent().getStringExtra("uri"));
        String name = getIntent().getStringExtra("name");
        String mime = getIntent().getStringExtra("mime");
        String path = getIntent().getStringExtra("path");
        if (mime != null && mime.startsWith("video/")) {
            prepareVideoQueue(uri, name, mime, path);
            buildVideoLayout();
            loadCurrentVideo(false, 0);
        } else {
            buildImageLayout(uri, name, mime == null ? "" : mime, path == null ? "" : path);
        }
    }

    private void prepareVideoQueue(Uri currentUri, String name, String mime, String path) {
        String albumKey = getIntent().getStringExtra("album_key");
        if (albumKey != null && !albumKey.isEmpty()) {
            List<MediaItem> media = MediaStoreRepository.loadMediaForAlbum(this, albumKey);
            for (MediaItem item : media) {
                if (item.isVideo()) {
                    videoQueue.add(item);
                }
            }
        }
        if (videoQueue.isEmpty()) {
            videoQueue.add(new MediaItem(0, currentUri, name, mime, 0, 0, path, "video", "Video"));
        }
        currentIndex = 0;
        for (int i = 0; i < videoQueue.size(); i++) {
            if (videoQueue.get(i).uri.toString().equals(currentUri.toString())) {
                currentIndex = i;
                break;
            }
        }
    }

    private void buildVideoLayout() {
        applyPlayerWindowSettings();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.BLACK);
        bar.setPadding(Ui.dp(this, 10), statusBarHeight() + Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));

        TextView back = Ui.title(this, "Voltar", 16);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(Color.WHITE);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resetSpeedAndFinish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)));

        title = Ui.title(this, "", 17);
        title.setTextColor(Color.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        content.setBackgroundColor(Color.BLACK);
        content.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                return handleVideoTouch(event);
            }
        });
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout speedBar = new LinearLayout(this);
        speedBar.setGravity(Gravity.CENTER);
        speedBar.setBackgroundColor(Color.BLACK);
        speedBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), navigationBarHeight() + Ui.dp(this, 12));
        addSpeedButton(speedBar, "0,5x", 0.5f);
        addSpeedButton(speedBar, "1x", 1f);
        addSpeedButton(speedBar, "1,5x", 1.5f);
        addSpeedButton(speedBar, "2x", 2f);
        root.addView(speedBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateSpeedButtons();
    }

    private void buildImageLayout(Uri uri, String name, String mime, String path) {
        applyPlayerWindowSettings();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), statusBarHeight() + Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));

        TextView back = Ui.title(this, "Voltar", 16);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)));

        TextView imageTitle = Ui.title(this, name == null ? "Midia" : name, 17);
        bar.addView(imageTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout imageContent = new FrameLayout(this);
        imageContent.setBackgroundColor(prefs.getBoolean("fullscreen_black_bg", true) ? Color.BLACK : Ui.bg(this));
        showImage(imageContent, uri);
        root.addView(imageContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (prefs.getBoolean("show_fullscreen_details", false)) {
            TextView info = Ui.label(this, path.isEmpty() ? mime : path + "  " + mime);
            info.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), navigationBarHeight() + Ui.dp(this, 12));
            root.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        setContentView(root);
    }

    private boolean handleVideoTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downY = event.getY();
            downX = event.getX();
            return true;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        float deltaY = event.getY() - downY;
        float deltaX = event.getX() - downX;
        if (Math.abs(deltaY) > Ui.dp(this, 90) && Math.abs(deltaY) > Math.abs(deltaX)) {
            if (deltaY < 0) {
                switchVideo(1);
            } else {
                switchVideo(-1);
            }
            return true;
        }
        togglePlayback();
        return true;
    }

    private void switchVideo(final int direction) {
        if (videoQueue.size() < 2) {
            return;
        }
        int next = currentIndex + direction;
        if (next < 0) {
            next = videoQueue.size() - 1;
        } else if (next >= videoQueue.size()) {
            next = 0;
        }
        saveCurrentPosition();
        currentIndex = next;
        final int height = content.getHeight() == 0 ? getResources().getDisplayMetrics().heightPixels : content.getHeight();
        content.animate()
                .translationY(direction > 0 ? -height : height)
                .alpha(0.45f)
                .setDuration(110)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        content.setTranslationY(direction > 0 ? height : -height);
                        loadCurrentVideo(true, direction);
                        content.animate()
                                .translationY(0)
                                .alpha(1f)
                                .setDuration(130)
                                .start();
                    }
                })
                .start();
    }

    private void loadCurrentVideo(boolean fromSwipe, int direction) {
        releasePlayer();
        content.removeAllViews();
        MediaItem item = videoQueue.get(currentIndex);
        title.setText(item.name);

        PlayerView playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(false);
        playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        content.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        playButton = new TextView(this);
        playButton.setText("Pause");
        playButton.setTextColor(Color.WHITE);
        playButton.setTextSize(18);
        playButton.setGravity(Gravity.CENTER);
        playButton.setBackground(Ui.rounded(0x66000000, 30, this));
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 54), Gravity.CENTER);
        content.addView(playButton, playParams);
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlayback();
            }
        });

        currentPlayer = new ExoPlayer.Builder(this).build();
        currentVideoKey = "video_pos_" + item.uri.toString().hashCode();
        videoPositionRestored = false;
        currentPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri));
        currentPlayer.setRepeatMode(prefs.getBoolean("loop_videos", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        currentPlayer.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
        playerView.setPlayer(currentPlayer);
        currentPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !videoPositionRestored) {
                    restoreCurrentPosition();
                    videoPositionRestored = true;
                }
                updatePlayButton();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayButton();
            }
        });
        currentPlayer.prepare();
        currentPlayer.setPlayWhenReady(prefs.getBoolean("autoplay_videos", true));
        updatePlayButton();
    }

    private void togglePlayback() {
        if (currentPlayer == null) {
            return;
        }
        if (currentPlayer.isPlaying()) {
            currentPlayer.pause();
        } else {
            currentPlayer.play();
        }
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (playButton == null || currentPlayer == null) {
            return;
        }
        playButton.setText(currentPlayer.isPlaying() ? "Pause" : "Play");
        playButton.setAlpha(currentPlayer.isPlaying() ? 0.2f : 1f);
    }

    private void addSpeedButton(LinearLayout parent, String label, final float speed) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(15);
        button.setSingleLine(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playbackSpeed = speed;
                if (currentPlayer != null) {
                    currentPlayer.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
                }
                updateSpeedButtons();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
        params.leftMargin = Ui.dp(this, 4);
        params.rightMargin = Ui.dp(this, 4);
        parent.addView(button, params);
        speedButtons.add(button);
    }

    private void updateSpeedButtons() {
        float[] speeds = new float[] {0.5f, 1f, 1.5f, 2f};
        for (int i = 0; i < speedButtons.size(); i++) {
            TextView button = speedButtons.get(i);
            boolean selected = Math.abs(playbackSpeed - speeds[i]) < 0.01f;
            button.setTextColor(selected ? Color.BLACK : Color.WHITE);
            button.setBackground(Ui.rounded(selected ? Color.WHITE : 0x33222222, 18, this));
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

    private void applyPlayerWindowSettings() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (prefs.getBoolean("fullscreen_max_brightness", false)) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 1f;
            getWindow().setAttributes(params);
        }
    }

    private void saveCurrentPosition() {
        if (currentPlayer != null && currentVideoKey != null && prefs.getBoolean("remember_video_position", true)) {
            prefs.edit().putLong(currentVideoKey, currentPlayer.getCurrentPosition()).apply();
        }
    }

    private void restoreCurrentPosition() {
        if (currentPlayer != null && currentVideoKey != null && prefs.getBoolean("remember_video_position", true)) {
            long savedPosition = prefs.getLong(currentVideoKey, 0L);
            if (savedPosition > 0L) {
                currentPlayer.seekTo(savedPosition);
            }
        }
    }

    private void releasePlayer() {
        if (currentPlayer != null) {
            currentPlayer.release();
            currentPlayer = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentPosition();
        if (currentPlayer != null) {
            currentPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    @Override
    public void onBackPressed() {
        resetSpeedAndFinish();
    }

    private void resetSpeedAndFinish() {
        playbackSpeed = 1f;
        finish();
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return Ui.dp(this, 24);
    }

    private int navigationBarHeight() {
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return Ui.dp(this, 24);
    }
}
