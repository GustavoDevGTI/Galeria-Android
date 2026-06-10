package com.galeria.android;

import android.content.Context;
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
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
    private final ArrayList<MediaItem> items = new ArrayList<>();

    MediaGridAdapter(Context context) {
        this.context = context;
    }

    void submit(List<MediaItem> nextItems) {
        items.clear();
        items.addAll(nextItems);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public MediaItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Holder holder;
        if (convertView == null) {
            FrameLayout frame = new FrameLayout(context);
            frame.setBackgroundColor(Ui.BG);
            frame.setPadding(Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2));

            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Ui.SURFACE);
            frame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            TextView badge = new TextView(context);
            badge.setText("VIDEO");
            badge.setTextColor(Ui.TEXT);
            badge.setTextSize(11);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundColor(0xAA000000);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(Ui.dp(context, 54), Ui.dp(context, 24));
            badgeParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            frame.addView(badge, badgeParams);

            holder = new Holder(image, badge);
            frame.setTag(holder);
            convertView = frame;
        } else {
            holder = (Holder) convertView.getTag();
        }

        MediaItem item = getItem(position);
        holder.badge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
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

        Holder(ImageView image, TextView badge) {
            this.image = image;
            this.badge = badge;
        }
    }
}
