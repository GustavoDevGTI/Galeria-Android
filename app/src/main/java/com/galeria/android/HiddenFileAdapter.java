package com.galeria.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HiddenFileAdapter extends BaseAdapter {
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 24)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ArrayList<File> files = new ArrayList<>();

    HiddenFileAdapter(Context context) {
        this.context = context;
    }

    void submit(File[] nextFiles) {
        files.clear();
        if (nextFiles != null) {
            List<File> list = Arrays.asList(nextFiles);
            list.sort(new Comparator<File>() {
                @Override
                public int compare(File first, File second) {
                    return Long.compare(second.lastModified(), first.lastModified());
                }
            });
            for (File file : list) {
                if (file.isFile() && !file.getName().equals(".nomedia")) {
                    files.add(file);
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return files.size();
    }

    @Override
    public File getItem(int position) {
        return files.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
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
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView name = new TextView(context);
            name.setTextColor(Ui.TEXT);
            name.setTextSize(11);
            name.setGravity(Gravity.BOTTOM);
            name.setBackgroundColor(0x99000000);
            Ui.setPadding(name, 5, 3, 5, 3);
            frame.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 34), Gravity.BOTTOM));

            holder = new Holder(image, name);
            frame.setTag(holder);
            convertView = frame;
        } else {
            holder = (Holder) convertView.getTag();
        }

        final File file = getItem(position);
        final String key = file.getAbsolutePath() + ":" + file.lastModified();
        holder.name.setText(file.getName());
        holder.image.setTag(key);
        Bitmap cached = THUMB_CACHE.get(key);
        if (cached != null) {
            holder.image.setImageBitmap(cached);
            return convertView;
        }
        holder.image.setImageBitmap(null);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = thumbnail(file);
                if (bitmap != null) {
                    THUMB_CACHE.put(key, bitmap);
                }
                holder.image.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(holder.image.getTag())) {
                            holder.image.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        });
        return convertView;
    }

    private Bitmap thumbnail(File file) {
        String mime = FileDetailActivity.mimeFor(file);
        try {
            if (mime.startsWith("video/")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return ThumbnailUtils.createVideoThumbnail(file, new Size(360, 360), null);
                }
                return ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), android.provider.MediaStore.Video.Thumbnails.MINI_KIND);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 6;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception exception) {
            return null;
        }
    }

    private static final class Holder {
        final ImageView image;
        final TextView name;

        Holder(ImageView image, TextView name) {
            this.image = image;
            this.name = name;
        }
    }
}
