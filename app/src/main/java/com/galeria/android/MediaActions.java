package com.galeria.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.IntentSender;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;

final class MediaActions {
    static final int RESULT_DONE = 1;
    static final int RESULT_NEEDS_PERMISSION = 2;
    static final int RESULT_FAILED = 3;

    private MediaActions() {
    }

    static void requestDelete(Activity activity, Uri uri, int requestCode) {
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
            } catch (IntentSender.SendIntentException exception) {
                Ui.toast(activity, "Nao foi possivel pedir permissao para excluir.");
            }
            return;
        }

        int deleted = activity.getContentResolver().delete(uri, null, null);
        Ui.toast(activity, deleted > 0 ? "Item excluido." : "Nao foi possivel excluir.");
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
            } catch (IntentSender.SendIntentException exception) {
                Ui.toast(activity, "Nao foi possivel pedir permissao para mover.");
            }
        }
    }

    static int moveToFolder(Activity activity, MediaItem item, String folderName) {
        String cleanName = cleanFolderName(folderName);
        if (cleanName.isEmpty()) {
            return RESULT_FAILED;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            String baseDir = item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir + "/" + cleanName + "/");
            try {
                int updated = activity.getContentResolver().update(item.uri, values, null, null);
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
            return RESULT_DONE;
        }
        return RESULT_FAILED;
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

        return file.delete();
    }

    static File hiddenDir(Activity activity) {
        return new File(activity.getExternalFilesDir(null), "Hidden");
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
