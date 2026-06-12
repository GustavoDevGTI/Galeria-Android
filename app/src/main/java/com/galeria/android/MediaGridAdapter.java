package com.galeria.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MediaGridAdapter extends BaseAdapter {
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    private static final Set<String> LOADING_KEYS = java.util.Collections.synchronizedSet(new HashSet<String>());
    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ArrayList<MediaItem> allItems = new ArrayList<>();
    private final ArrayList<MediaItem> visibleItems = new ArrayList<>();
    private final HashSet<String> selectedUris = new HashSet<>();
    private String filter = "";
    private boolean listMode;
    private boolean selectionMode;

    MediaGridAdapter(Context context) {
        this.context = context;
    }

    void submit(List<MediaItem> nextItems) {
        allItems.clear();
        allItems.addAll(nextItems);
        selectedUris.retainAll(uriSet(nextItems));
        applyFilter(filter);
    }

    void setSpacingDp(int spacingDp) {
        // Spacing is owned by GridView. Items never receive internal padding/borders.
    }

    void setListMode(boolean listMode) {
        if (this.listMode != listMode) {
            this.listMode = listMode;
            notifyDataSetChanged();
        }
    }

    void applyFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase(Locale.US);
        visibleItems.clear();
        for (MediaItem item : allItems) {
            if (filter.isEmpty()
                    || item.name.toLowerCase(Locale.US).contains(filter)
                    || item.relativePath.toLowerCase(Locale.US).contains(filter)) {
                visibleItems.add(item);
            }
        }
        notifyDataSetChanged();
    }

    boolean moveVisible(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= visibleItems.size() || toPosition >= visibleItems.size() || fromPosition == toPosition) {
            return false;
        }
        MediaItem moved = visibleItems.remove(fromPosition);
        visibleItems.add(toPosition, moved);
        ArrayList<MediaItem> hidden = new ArrayList<>();
        for (MediaItem item : allItems) {
            if (!visibleItems.contains(item)) {
                hidden.add(item);
            }
        }
        allItems.clear();
        allItems.addAll(visibleItems);
        allItems.addAll(hidden);
        notifyDataSetChanged();
        return true;
    }

    boolean moveSelectedBlock(int targetPosition) {
        if (targetPosition < 0 || targetPosition >= visibleItems.size() || selectedUris.isEmpty()) {
            return false;
        }
        MediaItem target = visibleItems.get(targetPosition);
        if (selectedUris.contains(target.uri.toString())) {
            return false;
        }
        ArrayList<MediaItem> moving = new ArrayList<>();
        ArrayList<MediaItem> remaining = new ArrayList<>();
        for (MediaItem item : visibleItems) {
            if (selectedUris.contains(item.uri.toString())) {
                moving.add(item);
            } else {
                remaining.add(item);
            }
        }
        int insertAt = remaining.indexOf(target);
        if (insertAt < 0) {
            return false;
        }
        insertAt = Math.min(remaining.size(), insertAt + 1);
        remaining.addAll(insertAt, moving);
        visibleItems.clear();
        visibleItems.addAll(remaining);
        ArrayList<MediaItem> hidden = new ArrayList<>();
        for (MediaItem item : allItems) {
            if (!visibleItems.contains(item)) {
                hidden.add(item);
            }
        }
        allItems.clear();
        allItems.addAll(visibleItems);
        allItems.addAll(hidden);
        notifyDataSetChanged();
        return true;
    }

    void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedUris.clear();
        }
        notifyDataSetChanged();
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    void toggleSelection(int position) {
        if (position < 0 || position >= visibleItems.size()) {
            return;
        }
        String key = visibleItems.get(position).uri.toString();
        if (selectedUris.contains(key)) {
            selectedUris.remove(key);
        } else {
            selectedUris.add(key);
        }
        notifyDataSetChanged();
    }

    void selectPosition(int position) {
        if (position >= 0 && position < visibleItems.size()) {
            selectedUris.add(visibleItems.get(position).uri.toString());
            notifyDataSetChanged();
        }
    }

    void selectAllVisible() {
        for (MediaItem item : visibleItems) {
            selectedUris.add(item.uri.toString());
        }
        notifyDataSetChanged();
    }

    void clearSelection() {
        selectedUris.clear();
        selectionMode = false;
        notifyDataSetChanged();
    }

    boolean allVisibleSelected() {
        return !visibleItems.isEmpty() && selectedUris.size() >= visibleItems.size();
    }

    int selectedCount() {
        return selectedUris.size();
    }

    List<MediaItem> selectedItems() {
        ArrayList<MediaItem> items = new ArrayList<>();
        for (MediaItem item : visibleItems) {
            if (selectedUris.contains(item.uri.toString())) {
                items.add(item);
            }
        }
        return items;
    }

    private Set<String> uriSet(List<MediaItem> items) {
        HashSet<String> set = new HashSet<>();
        for (MediaItem item : items) {
            set.add(item.uri.toString());
        }
        return set;
    }

    List<MediaItem> currentOrder() {
        return new ArrayList<>(allItems);
    }

    @Override
    public int getCount() {
        return visibleItems.size();
    }

    @Override
    public MediaItem getItem(int position) {
        return visibleItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return visibleItems.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Holder holder;
        if (convertView == null || !(((Holder) convertView.getTag()).listMode == listMode)) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(listMode ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            item.setGravity(listMode ? Gravity.CENTER_VERTICAL : Gravity.LEFT);
            item.setBackgroundColor(Color.TRANSPARENT);

            FrameLayout thumb = new FrameLayout(context);
            thumb.setBackgroundColor(Color.TRANSPARENT);
            ImageView image = listMode ? new ImageView(context) : new SquareImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.TRANSPARENT);
            thumb.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView check = new TextView(context);
            check.setTextColor(Color.WHITE);
            check.setTextSize(15);
            check.setGravity(Gravity.CENTER);
            check.setBackground(Ui.rounded(0x99000000, 4, context));
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24));
            checkParams.gravity = Gravity.TOP | Gravity.LEFT;
            checkParams.leftMargin = Ui.dp(context, 6);
            checkParams.topMargin = Ui.dp(context, 6);
            thumb.addView(check, checkParams);

            TextView badge = new TextView(context);
            badge.setText("VÍDEO");
            badge.setTextColor(Ui.text(context));
            badge.setTextSize(10);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 22));
            badgeParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            thumb.addView(badge, badgeParams);
            LinearLayout.LayoutParams thumbParams = listMode
                    ? new LinearLayout.LayoutParams(Ui.dp(context, 82), Ui.dp(context, 82))
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            item.addView(thumb, thumbParams);

            TextView name = new TextView(context);
            name.setTextColor(Ui.muted(context));
            name.setTextSize(listMode ? 14 : 11);
            name.setSingleLine(!listMode);
            name.setMaxLines(listMode ? 2 : 1);
            name.setGravity(Gravity.LEFT);
            name.setPadding(Ui.dp(context, listMode ? 12 : 2), Ui.dp(context, listMode ? 0 : 4), Ui.dp(context, 2), 0);
            LinearLayout.LayoutParams nameParams = listMode
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 24));
            item.addView(name, nameParams);

            holder = new Holder(image, badge, name, check, listMode);
            item.setTag(holder);
            convertView = item;
        } else {
            holder = (Holder) convertView.getTag();
        }

        convertView.setPadding(0, 0, 0, 0);
        MediaItem item = getItem(position);
        boolean selected = selectedUris.contains(item.uri.toString());
        convertView.setAlpha(selected ? 0.78f : 1f);
        convertView.setScaleX(selected ? 0.94f : 1f);
        convertView.setScaleY(selected ? 0.94f : 1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            convertView.setTranslationZ(selected ? -Ui.dp(context, 2) : 0f);
        }
        holder.check.setVisibility(selectionMode || selected ? View.VISIBLE : View.GONE);
        holder.check.setText(selected ? "✓" : "");
        holder.badge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        holder.name.setText(item.name);
        holder.image.setImageBitmap(null);
        holder.image.setTag(item.uri);
        Bitmap cached = THUMB_CACHE.get(item.uri.toString());
        if (cached != null) {
            holder.image.setImageBitmap(cached);
        } else {
            loadThumbnail(item, holder.image);
        }
        return convertView;
    }

    private void loadThumbnail(final MediaItem item, final ImageView target) {
        final String key = item.uri.toString();
        if (!LOADING_KEYS.add(key)) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bitmap = readThumbnail(item.uri);
                    if (bitmap != null) {
                        THUMB_CACHE.put(key, bitmap);
                    }
                    target.post(new Runnable() {
                        @Override
                        public void run() {
                            Object tag = target.getTag();
                            if (item.uri.equals(tag)) {
                                target.setImageBitmap(bitmap);
                            }
                        }
                    });
                } finally {
                    LOADING_KEYS.remove(key);
                }
            }
        });
    }

    private Bitmap readThumbnail(Uri uri) {
        if ("file".equals(uri.getScheme()) && isVideoFile(uri.getPath())) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return ThumbnailUtils.createVideoThumbnail(new File(uri.getPath()), new Size(360, 360), null);
                }
                return ThumbnailUtils.createVideoThumbnail(uri.getPath(), android.provider.MediaStore.Video.Thumbnails.MINI_KIND);
            } catch (Exception ignored) {
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(uri, new Size(360, 360), null);
            }
        } catch (Exception ignored) {
        }

        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 8;
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isVideoFile(String path) {
        if (path == null) {
            return false;
        }
        String name = path.toLowerCase(Locale.US);
        return name.endsWith(".mp4")
                || name.endsWith(".mkv")
                || name.endsWith(".webm")
                || name.endsWith(".mov")
                || name.endsWith(".avi")
                || name.endsWith(".3gp")
                || name.endsWith(".m4v")
                || name.endsWith(".ts");
    }

    private static final class Holder {
        final ImageView image;
        final TextView badge;
        final TextView name;
        final TextView check;
        final boolean listMode;

        Holder(ImageView image, TextView badge, TextView name, TextView check, boolean listMode) {
            this.image = image;
            this.badge = badge;
            this.name = name;
            this.check = check;
            this.listMode = listMode;
        }
    }
}
