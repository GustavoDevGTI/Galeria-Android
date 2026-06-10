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
import java.util.List;

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

    private static void loadFromCollection(Context context, Uri collection, List<MediaItem> output) {
        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[] {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
        } else {
            projection = new String[] {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.SIZE
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

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                output.add(new MediaItem(
                        id,
                        itemUri,
                        cursor.getString(nameIndex),
                        cursor.getString(mimeIndex),
                        cursor.getLong(dateIndex),
                        cursor.getLong(sizeIndex),
                        pathIndex >= 0 ? cursor.getString(pathIndex) : ""
                ));
            }
        } catch (SecurityException ignored) {
            // The user may grant only photos or only videos on recent Android versions.
        }
    }
}
