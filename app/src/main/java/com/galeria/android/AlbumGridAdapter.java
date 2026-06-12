package com.galeria.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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

final class AlbumGridAdapter extends BaseAdapter {
    private static final LruCache<String, Bitmap> COVER_CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    private static final java.util.Set<String> LOADING_KEYS = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ArrayList<AlbumItem> allAlbums = new ArrayList<>();
    private final ArrayList<AlbumItem> visibleAlbums = new ArrayList<>();
    private final HashSet<String> selectedKeys = new HashSet<>();
    private String filter = "";
    private boolean selectionMode;

    AlbumGridAdapter(Context context) {
        this.context = context;
    }

    void submit(List<AlbumItem> albums) {
        allAlbums.clear();
        allAlbums.addAll(albums);
        selectedKeys.retainAll(albumKeySet(albums));
        applyFilter(filter);
    }

    void applyFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase(Locale.US);
        visibleAlbums.clear();
        for (AlbumItem album : allAlbums) {
            if (filter.isEmpty() || album.name.toLowerCase(Locale.US).contains(filter)) {
                visibleAlbums.add(album);
            }
        }
        notifyDataSetChanged();
    }

    void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedKeys.clear();
        }
        notifyDataSetChanged();
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    void toggleSelection(int position) {
        if (position < 0 || position >= visibleAlbums.size()) {
            return;
        }
        String key = visibleAlbums.get(position).key;
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key);
        } else {
            selectedKeys.add(key);
        }
        notifyDataSetChanged();
    }

    void selectPosition(int position) {
        if (position >= 0 && position < visibleAlbums.size()) {
            selectedKeys.add(visibleAlbums.get(position).key);
            notifyDataSetChanged();
        }
    }

    void selectAllVisible() {
        for (AlbumItem album : visibleAlbums) {
            selectedKeys.add(album.key);
        }
        notifyDataSetChanged();
    }

    void clearSelection() {
        selectedKeys.clear();
        selectionMode = false;
        notifyDataSetChanged();
    }

    boolean allVisibleSelected() {
        return !visibleAlbums.isEmpty() && selectedKeys.size() >= visibleAlbums.size();
    }

    int selectedCount() {
        return selectedKeys.size();
    }

    List<AlbumItem> selectedAlbums() {
        ArrayList<AlbumItem> albums = new ArrayList<>();
        for (AlbumItem album : visibleAlbums) {
            if (selectedKeys.contains(album.key)) {
                albums.add(album);
            }
        }
        return albums;
    }

    private Set<String> albumKeySet(List<AlbumItem> albums) {
        HashSet<String> keys = new HashSet<>();
        for (AlbumItem album : albums) {
            keys.add(album.key);
        }
        return keys;
    }

    @Override
    public int getCount() {
        return visibleAlbums.size();
    }

    @Override
    public AlbumItem getItem(int position) {
        return visibleAlbums.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Holder holder;
        if (convertView == null) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.TOP);
            item.setPadding(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 12));

            FrameLayout thumb = new FrameLayout(context);
            SquareImageView cover = new SquareImageView(context);
            cover.setScaleType(SquareImageView.ScaleType.CENTER_CROP);
            cover.setBackground(Ui.rounded(Ui.surface(context), folderRadius(), context));
            cover.setClipToOutline(true);
            thumb.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

            item.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = new TextView(context);
            name.setTextColor(Ui.text(context));
            name.setTextSize(14);
            name.setGravity(Gravity.LEFT);
            name.setMaxLines(2);
            name.setIncludeFontPadding(true);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 48));
            nameParams.topMargin = Ui.dp(context, 8);
            item.addView(name, nameParams);

            holder = new Holder(cover, name, check);
            item.setTag(holder);
            convertView = item;
        } else {
            holder = (Holder) convertView.getTag();
        }

        AlbumItem album = getItem(position);
        boolean selected = selectedKeys.contains(album.key);
        convertView.setAlpha(selected ? 0.78f : 1f);
        convertView.setScaleX(selected ? 0.94f : 1f);
        convertView.setScaleY(selected ? 0.94f : 1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            convertView.setTranslationZ(selected ? -Ui.dp(context, 2) : 0f);
        }
        holder.check.setVisibility(selectionMode || selected ? View.VISIBLE : View.GONE);
        holder.check.setText(selected ? "\u2713" : "");
        holder.name.setText(album.name + " (" + album.count + ")");
        holder.name.setTextColor(Ui.text(context));
        holder.cover.setBackground(Ui.rounded(Ui.surface(context), folderRadius(), context));
        holder.cover.setImageBitmap(null);
        if (album.cover != null) {
            holder.cover.setTag(album.cover.uri);
            Bitmap cached = COVER_CACHE.get(album.cover.uri.toString());
            if (cached != null) {
                holder.cover.setImageBitmap(cached);
            } else {
                loadThumbnail(album.cover.uri, holder.cover);
            }
        }
        return convertView;
    }

    private void loadThumbnail(final Uri uri, final SquareImageView target) {
        final String key = uri.toString();
        if (!LOADING_KEYS.add(key)) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bitmap = readThumbnail(uri);
                    if (bitmap != null) {
                        COVER_CACHE.put(key, bitmap);
                    }
                    target.post(new Runnable() {
                        @Override
                        public void run() {
                            if (uri.equals(target.getTag())) {
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
                    return ThumbnailUtils.createVideoThumbnail(new File(uri.getPath()), new Size(420, 420), null);
                }
                return ThumbnailUtils.createVideoThumbnail(uri.getPath(), android.provider.MediaStore.Video.Thumbnails.MINI_KIND);
            } catch (Exception ignored) {
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(uri, new Size(420, 420), null);
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

    private int folderRadius() {
        String style = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
                .getString("folder_thumb_style", "Cantos arredondados");
        if ("Quadrado".equals(style)) {
            return 0;
        }
        if ("Circular".equals(style)) {
            return 40;
        }
        return 8;
    }

    private static final class Holder {
        final SquareImageView cover;
        final TextView name;
        final TextView check;

        Holder(SquareImageView cover, TextView name, TextView check) {
            this.cover = cover;
            this.name = name;
            this.check = check;
        }
    }
}
