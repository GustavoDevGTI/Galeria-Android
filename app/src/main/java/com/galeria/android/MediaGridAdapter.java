package com.galeria.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MediaGridAdapter extends BaseAdapter {
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ArrayList<MediaItem> allItems = new ArrayList<>();
    private final ArrayList<MediaItem> visibleItems = new ArrayList<>();
    private String filter = "";

    MediaGridAdapter(Context context) {
        this.context = context;
    }

    void submit(List<MediaItem> nextItems) {
        allItems.clear();
        allItems.addAll(nextItems);
        applyFilter(filter);
    }

    void setSpacingDp(int spacingDp) {
        // Spacing is owned by GridView. Items never receive internal padding/borders.
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
        if (convertView == null) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackgroundColor(Color.TRANSPARENT);

            FrameLayout thumb = new FrameLayout(context);
            thumb.setBackgroundColor(Color.TRANSPARENT);
            SquareImageView image = new SquareImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.TRANSPARENT);
            thumb.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView badge = new TextView(context);
            badge.setText("VIDEO");
            badge.setTextColor(Ui.TEXT);
            badge.setTextSize(10);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 22));
            badgeParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            thumb.addView(badge, badgeParams);
            item.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = new TextView(context);
            name.setTextColor(Ui.muted(context));
            name.setTextSize(11);
            name.setSingleLine(true);
            name.setGravity(Gravity.LEFT);
            name.setPadding(Ui.dp(context, 2), Ui.dp(context, 4), Ui.dp(context, 2), 0);
            item.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 24)));

            holder = new Holder(image, badge, name);
            item.setTag(holder);
            convertView = item;
        } else {
            holder = (Holder) convertView.getTag();
        }

        convertView.setPadding(0, 0, 0, 0);
        convertView.setAlpha(1f);
        convertView.setScaleX(1f);
        convertView.setScaleY(1f);
        MediaItem item = getItem(position);
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
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = readThumbnail(item.uri);
                if (bitmap != null) {
                    THUMB_CACHE.put(item.uri.toString(), bitmap);
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
            }
        });
    }

    private Bitmap readThumbnail(Uri uri) {
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

    private static final class Holder {
        final ImageView image;
        final TextView badge;
        final TextView name;

        Holder(ImageView image, TextView badge, TextView name) {
            this.image = image;
            this.badge = badge;
            this.name = name;
        }
    }
}
