package com.galeria.android;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MediaStoreRepository {
    private MediaStoreRepository() {
    }

    static List<MediaItem> loadMedia(Context context) {
        ArrayList<MediaItem> items = new ArrayList<>();
        loadFromCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, items);
        loadFromCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, items);
        Collections.sort(items, new Comparator<MediaItem>() {
            @Override
            public int compare(MediaItem first, MediaItem second) {
                return Long.compare(second.dateAdded, first.dateAdded);
            }
        });
        return items;
    }

    static List<AlbumItem> buildAlbums(List<MediaItem> mediaItems) {
        LinkedHashMap<String, AlbumBuilder> builders = new LinkedHashMap<>();
        for (MediaItem item : mediaItems) {
            AlbumBuilder builder = builders.get(item.albumKey);
            if (builder == null) {
                builder = new AlbumBuilder(item.albumKey, item.albumName, item.relativePath);
                builders.put(item.albumKey, builder);
            }
            builder.add(item);
        }

        ArrayList<AlbumItem> albums = new ArrayList<>();
        for (Map.Entry<String, AlbumBuilder> entry : builders.entrySet()) {
            albums.add(entry.getValue().build());
        }
        return albums;
    }

    static List<MediaItem> loadMediaForAlbum(Context context, String albumKey) {
        ArrayList<MediaItem> filtered = new ArrayList<>();
        for (MediaItem item : loadMedia(context)) {
            if (item.albumKey.equals(albumKey)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    static List<AlbumItem> loadAlbums(Context context) {
        List<AlbumItem> albums = buildAlbums(loadMedia(context));
        Collections.sort(albums, new Comparator<AlbumItem>() {
            @Override
            public int compare(AlbumItem first, AlbumItem second) {
                return Long.compare(second.latestDate, first.latestDate);
            }
        });
        return albums;
    }

    private static void loadFromCollection(Context context, Uri collection, List<MediaItem> output) {
        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[] {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.BUCKET_ID,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            };
        } else {
            projection = new String[] {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.BUCKET_ID,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            };
        }

        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return;
            }

            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int pathIndex = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    : -1;
            int bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID);
            int bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                String relativePath = pathIndex >= 0 ? cursor.getString(pathIndex) : "";
                String bucketId = cursor.getString(bucketIdIndex);
                String bucketName = cursor.getString(bucketNameIndex);
                String albumKey = relativePath == null || relativePath.isEmpty() ? bucketId : relativePath;
                String albumName = cleanAlbumName(relativePath, bucketName);
                output.add(new MediaItem(
                        id,
                        itemUri,
                        cursor.getString(nameIndex),
                        cursor.getString(mimeIndex),
                        cursor.getLong(dateIndex),
                        cursor.getLong(sizeIndex),
                        relativePath,
                        albumKey,
                        albumName
                ));
            }
        } catch (SecurityException ignored) {
            // The user may grant only photos or only videos on recent Android versions.
        }
    }

    private static String cleanAlbumName(String relativePath, String fallback) {
        if (relativePath != null && !relativePath.isEmpty()) {
            String cleaned = relativePath;
            if (cleaned.endsWith("/")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            int slash = cleaned.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < cleaned.length()) {
                cleaned = cleaned.substring(slash + 1);
            }
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return fallback == null || fallback.isEmpty() ? "Galeria" : fallback;
    }

    private static final class AlbumBuilder {
        final String key;
        final String name;
        final String path;
        int count;
        MediaItem cover;
        long latestDate;
        long firstDate = Long.MAX_VALUE;
        long totalSize;

        AlbumBuilder(String key, String name, String path) {
            this.key = key;
            this.name = name;
            this.path = path;
        }

        void add(MediaItem item) {
            count++;
            totalSize += Math.max(0, item.size);
            if (cover == null || item.dateAdded > latestDate) {
                cover = item;
                latestDate = item.dateAdded;
            }
            if (item.dateAdded > 0 && item.dateAdded < firstDate) {
                firstDate = item.dateAdded;
            }
        }

        AlbumItem build() {
            long created = firstDate == Long.MAX_VALUE ? latestDate : firstDate;
            return new AlbumItem(key, name, count, cover, latestDate, created, totalSize, path);
        }
    }
}
