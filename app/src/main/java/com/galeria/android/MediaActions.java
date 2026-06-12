package com.galeria.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

final class MediaActions {
    static final int RESULT_DONE = 1;
    static final int RESULT_NEEDS_PERMISSION = 2;
    static final int RESULT_FAILED = 3;

    private MediaActions() {
    }

    static boolean hasAllFilesAccess(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager());
    }

    static void requestAllFilesAccess(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception exception) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    static int requestDelete(Activity activity, Uri uri, int requestCode) {
        if (hasAllFilesAccess(activity)) {
            return deleteDirect(activity, uri) ? RESULT_DONE : RESULT_FAILED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                boolean moveToTrash = activity.getSharedPreferences(Ui.PREFS, Activity.MODE_PRIVATE)
                        .getBoolean("move_to_trash", false);
                PendingIntent pendingIntent = moveToTrash
                        ? MediaStore.createTrashRequest(activity.getContentResolver(), Collections.singletonList(uri), true)
                        : MediaStore.createDeleteRequest(activity.getContentResolver(), Collections.singletonList(uri));
                activity.startIntentSenderForResult(
                        pendingIntent.getIntentSender(),
                        requestCode,
                        null,
                        0,
                        0,
                        0
                );
                return RESULT_NEEDS_PERMISSION;
            } catch (Exception exception) {
                boolean deleted = deleteDirect(activity, uri);
                if (!deleted) {
                    Ui.toast(activity, "Não foi possível pedir permissão para excluir.");
                }
                return deleted ? RESULT_DONE : RESULT_FAILED;
            }
        }

        int deleted = deleteDirect(activity, uri) ? 1 : 0;
        Ui.toast(activity, deleted > 0 ? "Item excluído." : "Não foi possível excluir.");
        return deleted > 0 ? RESULT_DONE : RESULT_FAILED;
    }

    static int requestPermanentDelete(Activity activity, Uri uri, int requestCode) {
        if (hasAllFilesAccess(activity)) {
            return deleteDirect(activity, uri) ? RESULT_DONE : RESULT_FAILED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pendingIntent = MediaStore.createDeleteRequest(
                        activity.getContentResolver(),
                        Collections.singletonList(uri)
                );
                activity.startIntentSenderForResult(
                        pendingIntent.getIntentSender(),
                        requestCode,
                        null,
                        0,
                        0,
                        0
                );
                return RESULT_NEEDS_PERMISSION;
            } catch (Exception exception) {
                boolean deleted = deleteDirect(activity, uri);
                if (!deleted) {
                    Ui.toast(activity, "Não foi possível pedir permissão para excluir.");
                }
                return deleted ? RESULT_DONE : RESULT_FAILED;
            }
        }
        return deleteDirect(activity, uri) ? RESULT_DONE : RESULT_FAILED;
    }

    static boolean deleteDirect(Activity activity, Uri uri) {
        File file = fileFromMediaStore(activity, uri);
        if (hasAllFilesAccess(activity) && file != null && file.exists()) {
            String path = file.getAbsolutePath();
            boolean deleted = file.delete();
            if (deleted) {
                try {
                    activity.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
                MediaScannerConnection.scanFile(activity, new String[] { path }, null, null);
                MediaStoreRepository.invalidateCache();
                return true;
            }
        }
        try {
            boolean deleted = activity.getContentResolver().delete(uri, null, null) > 0;
            if (deleted) {
                MediaStoreRepository.invalidateCache();
            }
            return deleted;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean mediaExists(Activity activity, Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(
                uri,
                new String[] { MediaStore.MediaColumns._ID },
                null,
                null,
                null
        )) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    static void requestWrite(Activity activity, Uri uri, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pendingIntent = MediaStore.createWriteRequest(
                        activity.getContentResolver(),
                        Collections.singletonList(uri)
                );
                activity.startIntentSenderForResult(
                        pendingIntent.getIntentSender(),
                        requestCode,
                        null,
                        0,
                        0,
                        0
                );
            } catch (Exception exception) {
                Ui.toast(activity, "Não foi possível pedir permissão para mover.");
            }
        }
    }

    static int moveToFolder(Activity activity, MediaItem item, String folderName) {
        String cleanName = cleanFolderName(folderName);
        if (cleanName.isEmpty()) {
            return RESULT_FAILED;
        }

        if (hasAllFilesAccess(activity)) {
            return moveToFolderDirect(activity, item, cleanName);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            String baseDir = item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir + "/" + cleanName + "/");
            try {
                int updated = activity.getContentResolver().update(item.uri, values, null, null);
                if (updated > 0) {
                    MediaStoreRepository.invalidateCache();
                }
                return updated > 0 ? RESULT_DONE : RESULT_FAILED;
            } catch (SecurityException exception) {
                return RESULT_NEEDS_PERMISSION;
            }
        }

        File currentFile = fileFromMediaStore(activity, item.uri);
        if (currentFile == null || !currentFile.exists()) {
            return RESULT_FAILED;
        }

        String baseDir = item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        File targetDir = new File(Environment.getExternalStoragePublicDirectory(baseDir), cleanName);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return RESULT_FAILED;
        }

        File targetFile = uniqueFile(targetDir, currentFile.getName());
        boolean moved = currentFile.renameTo(targetFile);
        if (moved) {
            MediaScannerConnection.scanFile(activity, new String[] { targetFile.getAbsolutePath(), currentFile.getAbsolutePath() }, null, null);
            MediaStoreRepository.invalidateCache();
            return RESULT_DONE;
        }
        return RESULT_FAILED;
    }

    static int copyToFolder(Activity activity, MediaItem item, String folderName) {
        String cleanName = cleanFolderName(folderName);
        if (cleanName.isEmpty()) {
            return RESULT_FAILED;
        }

        boolean isVideo = item.isVideo();
        String baseDir = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        if (hasAllFilesAccess(activity)) {
            return copyToFolderDirect(activity, item, cleanName, baseDir);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, item.name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir + "/" + cleanName + "/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            ContentResolver resolver = activity.getContentResolver();
            Uri targetUri = resolver.insert(collection, values);
            if (targetUri == null) {
                return RESULT_FAILED;
            }
            boolean copied = copyUri(activity, item.uri, targetUri);
            if (!copied) {
                resolver.delete(targetUri, null, null);
                return RESULT_FAILED;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(targetUri, done, null, null);
            MediaStoreRepository.invalidateCache();
            return RESULT_DONE;
        }

        File currentFile = fileFromMediaStore(activity, item.uri);
        if (currentFile == null || !currentFile.exists()) {
            return RESULT_FAILED;
        }
        File targetDir = new File(Environment.getExternalStoragePublicDirectory(baseDir), cleanName);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return RESULT_FAILED;
        }
        File targetFile = uniqueFile(targetDir, currentFile.getName());
        try (InputStream input = activity.getContentResolver().openInputStream(item.uri);
             FileOutputStream output = new FileOutputStream(targetFile)) {
            if (input == null) {
                return RESULT_FAILED;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            MediaScannerConnection.scanFile(activity, new String[] { targetFile.getAbsolutePath() }, null, null);
            MediaStoreRepository.invalidateCache();
            return RESULT_DONE;
        } catch (Exception exception) {
            targetFile.delete();
            return RESULT_FAILED;
        }
    }

    static File copyToHidden(Activity activity, MediaItem item) {
        File hiddenDir = hiddenDir(activity);
        if (!hiddenDir.exists() && !hiddenDir.mkdirs()) {
            return null;
        }

        File noMedia = new File(hiddenDir, ".nomedia");
        try {
            if (!noMedia.exists()) {
                noMedia.createNewFile();
            }
        } catch (Exception ignored) {
        }

        File target = uniqueFile(hiddenDir, item.name);
        try (InputStream input = activity.getContentResolver().openInputStream(item.uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return target;
        } catch (Exception exception) {
            target.delete();
            return null;
        }
    }

    static boolean restoreHiddenFile(Activity activity, File file, String mimeType) {
        if (!file.exists()) {
            return false;
        }

        boolean isVideo = mimeType.startsWith("video/");
        Uri collection = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String baseDir = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir + "/Galeria Restaurada/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        ContentResolver resolver = activity.getContentResolver();
        Uri targetUri = resolver.insert(collection, values);
        if (targetUri == null) {
            return false;
        }

        try (InputStream input = new java.io.FileInputStream(file);
             java.io.OutputStream output = resolver.openOutputStream(targetUri)) {
            if (output == null) {
                return false;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            resolver.delete(targetUri, null, null);
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(targetUri, done, null, null);
        }

        boolean restored = file.delete();
        if (restored) {
            MediaStoreRepository.invalidateCache();
        }
        return restored;
    }

    private static boolean copyUri(Activity activity, Uri sourceUri, Uri targetUri) {
        try (InputStream input = activity.getContentResolver().openInputStream(sourceUri);
             OutputStream output = activity.getContentResolver().openOutputStream(targetUri)) {
            if (input == null || output == null) {
                return false;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    static File hiddenDir(Activity activity) {
        return new File(activity.getExternalFilesDir(null), "Hidden");
    }

    static boolean createFolder(Activity activity, File target) {
        if (target.exists()) {
            return target.isDirectory();
        }
        boolean created = target.mkdirs();
        if (created) {
            MediaStoreRepository.invalidateCache();
        }
        return created;
    }

    static String cleanFolderName(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned;
    }

    private static File fileFromMediaStore(Activity activity, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return new File(uri.getPath());
        }
        String[] projection = new String[] { MediaStore.MediaColumns.DATA };
        try (Cursor cursor = activity.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                return new File(cursor.getString(index));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int moveToFolderDirect(Activity activity, MediaItem item, String cleanName) {
        File currentFile = fileFromMediaStore(activity, item.uri);
        if (currentFile == null || !currentFile.exists()) {
            return RESULT_FAILED;
        }

        String baseDir = item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        File targetDir = new File(Environment.getExternalStoragePublicDirectory(baseDir), cleanName);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return RESULT_FAILED;
        }

        File targetFile = uniqueFile(targetDir, currentFile.getName());
        boolean moved = currentFile.renameTo(targetFile);
        if (!moved) {
            try (InputStream input = activity.getContentResolver().openInputStream(item.uri);
                 FileOutputStream output = new FileOutputStream(targetFile)) {
                if (input == null) {
                    return RESULT_FAILED;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                moved = currentFile.delete();
            } catch (Exception exception) {
                targetFile.delete();
                return RESULT_FAILED;
            }
        }

        if (moved) {
            try {
                activity.getContentResolver().delete(item.uri, null, null);
            } catch (Exception ignored) {
            }
            MediaScannerConnection.scanFile(activity, new String[] { targetFile.getAbsolutePath(), currentFile.getAbsolutePath() }, null, null);
            MediaStoreRepository.invalidateCache();
            return RESULT_DONE;
        }
        targetFile.delete();
        return RESULT_FAILED;
    }

    private static int copyToFolderDirect(Activity activity, MediaItem item, String cleanName, String baseDir) {
        File currentFile = fileFromMediaStore(activity, item.uri);
        if (currentFile == null || !currentFile.exists()) {
            return RESULT_FAILED;
        }
        File targetDir = new File(Environment.getExternalStoragePublicDirectory(baseDir), cleanName);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return RESULT_FAILED;
        }
        File targetFile = uniqueFile(targetDir, currentFile.getName());
        try (InputStream input = activity.getContentResolver().openInputStream(item.uri);
             FileOutputStream output = new FileOutputStream(targetFile)) {
            if (input == null) {
                return RESULT_FAILED;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            MediaScannerConnection.scanFile(activity, new String[] { targetFile.getAbsolutePath() }, null, null);
            MediaStoreRepository.invalidateCache();
            return RESULT_DONE;
        } catch (Exception exception) {
            targetFile.delete();
            return RESULT_FAILED;
        }
    }

    private static File uniqueFile(File dir, String originalName) {
        String safeName = originalName == null || originalName.trim().isEmpty() ? "media" : originalName.trim();
        File candidate = new File(dir, safeName);
        if (!candidate.exists()) {
            return candidate;
        }

        String name = safeName;
        String ext = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            name = safeName.substring(0, dot);
            ext = safeName.substring(dot);
        }

        int count = 1;
        do {
            candidate = new File(dir, name + "-" + count + ext);
            count++;
        } while (candidate.exists());
        return candidate;
    }
}
