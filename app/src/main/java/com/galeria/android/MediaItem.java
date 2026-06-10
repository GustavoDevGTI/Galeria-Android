package com.galeria.android;

import android.net.Uri;

final class MediaItem {
    final long id;
    final Uri uri;
    final String name;
    final String mimeType;
    final long dateAdded;
    final long size;
    final String relativePath;
    final String albumKey;
    final String albumName;

    MediaItem(long id, Uri uri, String name, String mimeType, long dateAdded, long size, String relativePath, String albumKey, String albumName) {
        this.id = id;
        this.uri = uri;
        this.name = name == null ? "Sem nome" : name;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.dateAdded = dateAdded;
        this.size = size;
        this.relativePath = relativePath == null ? "" : relativePath;
        this.albumKey = albumKey == null || albumKey.isEmpty() ? "root" : albumKey;
        this.albumName = albumName == null || albumName.isEmpty() ? "Galeria" : albumName;
    }

    boolean isVideo() {
        return mimeType.startsWith("video/");
    }

    boolean isImage() {
        return mimeType.startsWith("image/");
    }
}
