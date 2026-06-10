package com.galeria.android;

final class AlbumItem {
    final String key;
    final String name;
    final int count;
    final MediaItem cover;
    final long latestDate;
    final long firstDate;
    final long totalSize;
    final String path;

    AlbumItem(String key, String name, int count, MediaItem cover, long latestDate, long firstDate, long totalSize, String path) {
        this.key = key;
        this.name = name;
        this.count = count;
        this.cover = cover;
        this.latestDate = latestDate;
        this.firstDate = firstDate;
        this.totalSize = totalSize;
        this.path = path == null ? "" : path;
    }
}
