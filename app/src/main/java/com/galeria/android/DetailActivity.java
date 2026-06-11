package com.galeria.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends Activity {
    private static final int REQ_DELETE = 31;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<MediaItem> mediaQueue = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ExoPlayer currentPlayer;
    private FrameLayout content;
    private TextView title;
    private TextView currentTime;
    private TextView durationTime;
    private TextView speedButton;
    private ImageButton playPauseButton;
    private ImageButton favoriteButton;
    private LinearLayout videoControls;
    private LinearLayout timelineRow;
    private SeekBar seekBar;
    private PopupWindow speedPopup;
    private String currentVideoKey;
    private Uri pendingDeleteUri;
    private boolean videoPositionRestored;
    private boolean userSeeking;
    private int currentIndex;
    private float downY;
    private float downX;
    private float playbackSpeed = 1f;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateTimeline();
            handler.postDelayed(this, 350);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        Uri uri = Uri.parse(getIntent().getStringExtra("uri"));
        String name = getIntent().getStringExtra("name");
        String mime = getIntent().getStringExtra("mime");
        String path = getIntent().getStringExtra("path");
        prepareMediaQueue(uri, name, mime, path);
        buildLayout();
        loadCurrentItem(0);
    }

    private void prepareMediaQueue(Uri currentUri, String name, String mime, String path) {
        String albumKey = getIntent().getStringExtra("album_key");
        if (albumKey != null && !albumKey.isEmpty()) {
            mediaQueue.addAll(applyCustomOrder(MediaStoreRepository.loadMediaForAlbum(this, albumKey), albumKey));
        }
        if (mediaQueue.isEmpty()) {
            mediaQueue.add(new MediaItem(0, currentUri, name, mime, 0, 0, path, "media", "Media"));
        }
        currentIndex = 0;
        for (int i = 0; i < mediaQueue.size(); i++) {
            if (mediaQueue.get(i).uri.toString().equals(currentUri.toString())) {
                currentIndex = i;
                break;
            }
        }
    }

    private List<MediaItem> applyCustomOrder(List<MediaItem> items, String albumKey) {
        String saved = prefs.getString("custom_order_" + albumKey, "");
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

    private void buildLayout() {
        applyWindowSettings();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.BLACK);
        bar.setPadding(Ui.dp(this, 8), statusBarHeight() + Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));

        ImageButton back = iconButton(com.galeria.android.R.drawable.ic_back, Ui.dp(this, 48));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resetSpeedAndFinish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));

        title = Ui.title(this, "", 17);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Ui.dp(this, 4);
        bar.addView(title, titleParams);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        content.setBackgroundColor(Color.BLACK);
        content.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                return handleSwipeOrTap(event);
            }
        });
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.BLACK);
        bottom.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), navigationBarHeight() + Ui.dp(this, 8));

        videoControls = new LinearLayout(this);
        videoControls.setGravity(Gravity.CENTER);
        playPauseButton = iconButton(com.galeria.android.R.drawable.ic_play, Ui.dp(this, 46));
        playPauseButton.setBackground(Ui.rounded(0x66000000, 24, this));
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlayback();
            }
        });
        videoControls.addView(playPauseButton, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        bottom.addView(videoControls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        timelineRow = new LinearLayout(this);
        timelineRow.setGravity(Gravity.CENTER_VERTICAL);
        currentTime = timeLabel("00:00");
        durationTime = timeLabel("00:00");
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && currentPlayer != null && currentPlayer.getDuration() > 0) {
                    long target = currentPlayer.getDuration() * progress / 1000L;
                    currentTime.setText(formatTime(target));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (currentPlayer != null && currentPlayer.getDuration() > 0) {
                    currentPlayer.seekTo(currentPlayer.getDuration() * bar.getProgress() / 1000L);
                }
                userSeeking = false;
            }
        });
        speedButton = timeLabel("1x");
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSpeedPopup();
            }
        });
        timelineRow.addView(currentTime, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 34)));
        timelineRow.addView(seekBar, new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1));
        timelineRow.addView(durationTime, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 34)));
        timelineRow.addView(speedButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 34)));
        bottom.addView(timelineRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        favoriteButton = actionButton(com.galeria.android.R.drawable.ic_star);
        favoriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleFavorite();
            }
        });
        ImageButton share = actionButton(com.galeria.android.R.drawable.ic_share);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shareCurrent();
            }
        });
        ImageButton trash = actionButton(com.galeria.android.R.drawable.ic_trash);
        trash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDeleteCurrent();
            }
        });
        actions.addView(favoriteButton, actionParams());
        actions.addView(share, actionParams());
        actions.addView(trash, actionParams());
        bottom.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));

        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private TextView timeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private ImageButton iconButton(int icon, int touchSize) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setColorFilter(Color.WHITE);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        button.setMinimumWidth(touchSize);
        button.setMinimumHeight(touchSize);
        return button;
    }

    private ImageButton actionButton(int icon) {
        ImageButton button = iconButton(icon, Ui.dp(this, 44));
        button.setBackground(Ui.rounded(0x22000000, 22, this));
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 44));
        params.leftMargin = Ui.dp(this, 16);
        params.rightMargin = Ui.dp(this, 16);
        return params;
    }

    private boolean handleSwipeOrTap(MotionEvent event) {
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
        int threshold = Ui.dp(this, 72);
        if (Math.abs(deltaY) > threshold && Math.abs(deltaY) > Math.abs(deltaX)) {
            switchItem(deltaY < 0 ? 1 : -1, false);
            return true;
        }
        if (Math.abs(deltaX) > threshold && Math.abs(deltaX) > Math.abs(deltaY)) {
            switchItem(deltaX > 0 ? 1 : -1, true);
            return true;
        }
        if (currentItem().isVideo()) {
            togglePlayback();
        }
        return true;
    }

    private void switchItem(final int direction, final boolean horizontal) {
        if (mediaQueue.size() < 2) {
            return;
        }
        saveCurrentPosition();
        int next = currentIndex + direction;
        if (next < 0) {
            next = mediaQueue.size() - 1;
        } else if (next >= mediaQueue.size()) {
            next = 0;
        }
        currentIndex = next;
        final int offset = horizontal
                ? (content.getWidth() == 0 ? getResources().getDisplayMetrics().widthPixels : content.getWidth())
                : (content.getHeight() == 0 ? getResources().getDisplayMetrics().heightPixels : content.getHeight());
        content.animate()
                .translationX(horizontal ? (direction > 0 ? offset : -offset) : 0)
                .translationY(horizontal ? 0 : (direction > 0 ? -offset : offset))
                .alpha(0.35f)
                .setDuration(105)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        content.setTranslationX(horizontal ? (direction > 0 ? -offset : offset) : 0);
                        content.setTranslationY(horizontal ? 0 : (direction > 0 ? offset : -offset));
                        loadCurrentItem(direction);
                        content.animate()
                                .translationX(0)
                                .translationY(0)
                                .alpha(1f)
                                .setDuration(125)
                                .start();
                    }
                })
                .start();
    }

    private void loadCurrentItem(int direction) {
        releasePlayer();
        handler.removeCallbacks(progressUpdater);
        if (speedPopup != null) {
            speedPopup.dismiss();
        }
        content.removeAllViews();
        MediaItem item = currentItem();
        title.setText(item.name);
        updateFavoriteButton();
        if (item.isVideo()) {
            showVideo(item);
        } else {
            showImage(item);
        }
    }

    private void showVideo(MediaItem item) {
        videoControls.setVisibility(View.VISIBLE);
        timelineRow.setVisibility(View.VISIBLE);
        PlayerView playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(false);
        playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        content.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
                updatePlayPauseButton();
                updateTimeline();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButton();
            }
        });
        currentPlayer.prepare();
        currentPlayer.setPlayWhenReady(prefs.getBoolean("autoplay_videos", true));
        updateSpeedButton();
        updatePlayPauseButton();
        handler.post(progressUpdater);
    }

    private void showImage(final MediaItem item) {
        videoControls.setVisibility(View.GONE);
        timelineRow.setVisibility(View.GONE);
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        content.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final android.graphics.Bitmap bitmap;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bitmap = getContentResolver().loadThumbnail(item.uri, new Size(1800, 1800), null);
                    } else {
                        bitmap = android.graphics.BitmapFactory.decodeStream(getContentResolver().openInputStream(item.uri));
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

    private void togglePlayback() {
        if (currentPlayer == null) {
            return;
        }
        if (currentPlayer.isPlaying()) {
            currentPlayer.pause();
        } else {
            if (currentPlayer.getPlaybackState() == Player.STATE_ENDED) {
                currentPlayer.seekTo(0);
            }
            currentPlayer.play();
        }
        updatePlayPauseButton();
    }

    private void updatePlayPauseButton() {
        if (playPauseButton == null || currentPlayer == null) {
            return;
        }
        playPauseButton.setImageResource(currentPlayer.isPlaying() ? com.galeria.android.R.drawable.ic_pause : com.galeria.android.R.drawable.ic_play);
    }

    private void updateTimeline() {
        if (currentPlayer == null || !currentItem().isVideo()) {
            return;
        }
        long duration = Math.max(0L, currentPlayer.getDuration());
        long position = Math.max(0L, currentPlayer.getCurrentPosition());
        currentTime.setText(formatTime(position));
        durationTime.setText(duration > 0 ? formatTime(duration) : "00:00");
        if (!userSeeking && duration > 0) {
            seekBar.setProgress((int) Math.min(1000L, position * 1000L / duration));
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void showSpeedPopup() {
        if (speedPopup != null && speedPopup.isShowing()) {
            speedPopup.dismiss();
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.rounded(0xDD111111, 12, this));
        addSpeedOption(panel, "0,5x", 0.5f);
        addSpeedOption(panel, "1x", 1f);
        addSpeedOption(panel, "1,5x", 1.5f);
        addSpeedOption(panel, "2x", 2f);
        speedPopup = new PopupWindow(panel, Ui.dp(this, 74), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        speedPopup.setOutsideTouchable(true);
        speedPopup.showAsDropDown(speedButton, -Ui.dp(this, 10), -Ui.dp(this, 184));
    }

    private void addSpeedOption(LinearLayout panel, String label, final float speed) {
        TextView option = timeLabel(label);
        option.setTextSize(14);
        option.setBackgroundColor(Math.abs(playbackSpeed - speed) < 0.01f ? 0x55FFFFFF : Color.TRANSPARENT);
        option.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playbackSpeed = speed;
                if (currentPlayer != null) {
                    currentPlayer.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
                }
                updateSpeedButton();
                if (speedPopup != null) {
                    speedPopup.dismiss();
                }
            }
        });
        panel.addView(option, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)));
    }

    private void updateSpeedButton() {
        if (speedButton != null) {
            if (Math.abs(playbackSpeed - 0.5f) < 0.01f) {
                speedButton.setText("0,5x");
            } else if (Math.abs(playbackSpeed - 1.5f) < 0.01f) {
                speedButton.setText("1,5x");
            } else if (Math.abs(playbackSpeed - 2f) < 0.01f) {
                speedButton.setText("2x");
            } else {
                speedButton.setText("1x");
            }
        }
    }

    private void toggleFavorite() {
        MediaItem item = currentItem();
        HashSet<String> favorites = new HashSet<>(prefs.getStringSet("favorites", new HashSet<String>()));
        String key = item.uri.toString();
        if (favorites.contains(key)) {
            favorites.remove(key);
            Ui.toast(this, "Removido dos favoritos.");
        } else {
            favorites.add(key);
            Ui.toast(this, "Adicionado aos favoritos.");
        }
        prefs.edit().putStringSet("favorites", favorites).apply();
        updateFavoriteButton();
    }

    private void updateFavoriteButton() {
        if (favoriteButton == null || mediaQueue.isEmpty()) {
            return;
        }
        Set<String> favorites = prefs.getStringSet("favorites", new HashSet<String>());
        favoriteButton.setAlpha(favorites.contains(currentItem().uri.toString()) ? 1f : 0.55f);
    }

    private void shareCurrent() {
        MediaItem item = currentItem();
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(item.mimeType.isEmpty() ? "*/*" : item.mimeType);
        share.putExtra(Intent.EXTRA_STREAM, item.uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Compartilhar"));
    }

    private void confirmDeleteCurrent() {
        new AlertDialog.Builder(this)
                .setTitle("Excluir arquivo")
                .setMessage("Tem certeza que deseja excluir este arquivo?")
                .setPositiveButton("Excluir", (dialog, which) -> deleteCurrent())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void deleteCurrent() {
        MediaItem item = currentItem();
        pendingDeleteUri = item.uri;
        int result = MediaActions.requestPermanentDelete(this, item.uri, REQ_DELETE);
        if (result == MediaActions.RESULT_DONE) {
            Ui.toast(this, "Item excluido.");
            removeDeletedItem();
        } else if (result == MediaActions.RESULT_FAILED) {
            pendingDeleteUri = null;
            Ui.toast(this, "Nao foi possivel excluir.");
        }
    }

    private void removeDeletedItem() {
        if (mediaQueue.isEmpty()) {
            finish();
            return;
        }
        mediaQueue.remove(currentIndex);
        if (mediaQueue.isEmpty()) {
            finish();
            return;
        }
        if (currentIndex >= mediaQueue.size()) {
            currentIndex = mediaQueue.size() - 1;
        }
        loadCurrentItem(0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) {
            boolean deleted = resultCode == RESULT_OK || (pendingDeleteUri != null && !MediaActions.mediaExists(this, pendingDeleteUri));
            pendingDeleteUri = null;
            if (deleted) {
                Ui.toast(this, "Item excluido.");
                removeDeletedItem();
            } else {
                Ui.toast(this, "Exclusao cancelada.");
            }
        }
    }

    private MediaItem currentItem() {
        return mediaQueue.get(currentIndex);
    }

    private void applyWindowSettings() {
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
        handler.removeCallbacks(progressUpdater);
        if (currentPlayer != null) {
            currentPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressUpdater);
        releasePlayer();
    }

    @Override
    public void onBackPressed() {
        resetSpeedAndFinish();
    }

    private void resetSpeedAndFinish() {
        playbackSpeed = 1f;
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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
