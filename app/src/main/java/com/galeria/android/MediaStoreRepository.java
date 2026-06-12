package com.galeria.android;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MediaStoreRepository {
    private MediaStoreRepository() {
    }

    static List<MediaItem> loadMedia(Context context) {
        ArrayList<MediaItem> items = new ArrayList<>();
        loadFromFilesCollection(context, items);
        loadFromHiddenFilesystem(context, items);
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
        if ("all_media".equals(albumKey)) {
            return loadMedia(context);
        }
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

    private static void loadFromFilesCollection(Context context, List<MediaItem> output) {
        Uri collection = MediaStore.Files.getContentUri("external");
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
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
            };
        } else {
            projection = new String[] {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.BUCKET_ID,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
            };
        }

        ContentResolver resolver = context.getContentResolver();
        String selection = "("
                + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?,?)"
                + " OR " + MediaStore.MediaColumns.MIME_TYPE + " LIKE ?"
                + " OR " + MediaStore.MediaColumns.MIME_TYPE + " LIKE ?"
                + ") AND " + MediaStore.MediaColumns.SIZE + " > 0";
        String[] args = new String[] {
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
                "image/%",
                "video/%"
        };
        try (Cursor cursor = resolver.query(collection, projection, selection, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
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
            int dataIndex = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    ? cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    : -1;

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                String relativePath = pathIndex >= 0 ? cursor.getString(pathIndex) : pathFromData(dataIndex >= 0 ? cursor.getString(dataIndex) : null);
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

    private static void loadFromHiddenFilesystem(Context context, List<MediaItem> output) {
        if (!MediaActions.hasAllFilesAccess(context)) {
            return;
        }
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.exists()) {
            return;
        }
        HashSet<String> known = new HashSet<>();
        for (MediaItem item : output) {
            known.add(dedupeKey(item.relativePath, item.name, item.size));
        }
        scanDirectory(context, root, root, output, known, 0);
    }

    private static void scanDirectory(Context context, File root, File dir, List<MediaItem> output, Set<String> known, int depth) {
        if (dir == null || depth > 24) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (file.isDirectory()) {
                scanDirectory(context, root, file, output, known, depth + 1);
            } else if (file.isFile() && file.length() > 0 && isSupportedMediaFile(file)) {
                String relativePath = relativeFolder(root, file);
                String key = dedupeKey(relativePath, file.getName(), file.length());
                if (known.contains(key)) {
                    continue;
                }
                known.add(key);
                String albumName = cleanAlbumName(relativePath, file.getParentFile() == null ? "Galeria" : file.getParentFile().getName());
                output.add(new MediaItem(
                        -Math.abs(file.getAbsolutePath().hashCode()),
                        Uri.fromFile(file),
                        file.getName(),
                        mimeFor(file),
                        Math.max(1L, file.lastModified() / 1000L),
                        file.length(),
                        relativePath,
                        relativePath == null || relativePath.isEmpty() ? file.getParent() : relativePath,
                        albumName
                ));
            }
        }
    }

    private static String dedupeKey(String relativePath, String name, long size) {
        return (relativePath == null ? "" : relativePath).toLowerCase()
                + "|"
                + (name == null ? "" : name).toLowerCase()
                + "|"
                + size;
    }

    private static String relativeFolder(File root, File file) {
        File parent = file.getParentFile();
        if (parent == null) {
            return "";
        }
        String rootPath = root.getAbsolutePath();
        String parentPath = parent.getAbsolutePath();
        if (parentPath.startsWith(rootPath)) {
            String relative = parentPath.substring(rootPath.length()).replace('\\', '/');
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative.isEmpty() ? "" : relative + "/";
        }
        return parent.getName() + "/";
    }

    private static boolean isSupportedMediaFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".webp")
                || name.endsWith(".gif")
                || name.endsWith(".heic")
                || name.endsWith(".heif")
                || name.endsWith(".bmp")
                || name.endsWith(".svg")
                || name.endsWith(".dng")
                || name.endsWith(".raw")
                || name.endsWith(".cr2")
                || name.endsWith(".nef")
                || name.endsWith(".arw")
                || name.endsWith(".orf")
                || name.endsWith(".rw2")
                || name.endsWith(".mp4")
                || name.endsWith(".mkv")
                || name.endsWith(".webm")
                || name.endsWith(".mov")
                || name.endsWith(".avi")
                || name.endsWith(".3gp")
                || name.endsWith(".m4v")
                || name.endsWith(".ts");
    }

    private static String mimeFor(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toLowerCase() : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null && !mime.isEmpty()) {
            return mime;
        }
        if ("mkv".equals(ext)) {
            return "video/x-matroska";
        }
        if ("ts".equals(ext)) {
            return "video/mp2t";
        }
        if ("svg".equals(ext)) {
            return "image/svg+xml";
        }
        return isVideoExtension(ext) ? "video/*" : "image/*";
    }

    private static boolean isVideoExtension(String ext) {
        return "mp4".equals(ext)
                || "mkv".equals(ext)
                || "webm".equals(ext)
                || "mov".equals(ext)
                || "avi".equals(ext)
                || "3gp".equals(ext)
                || "m4v".equals(ext)
                || "ts".equals(ext);
    }

    private static String pathFromData(String data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        int slash = data.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        String parent = data.substring(0, slash);
        int parentSlash = parent.lastIndexOf('/');
        return parentSlash >= 0 ? parent.substring(parentSlash + 1) + "/" : parent + "/";
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
