package com.galeria.android;

final class AlbumItem {
    final String key;
    final String name;
    final int count;
    final MediaItem cover;
    final long latestDate;

    AlbumItem(String key, String name, int count, MediaItem cover, long latestDate) {
        this.key = key;
        this.name = name;
        this.count = count;
        this.cover = cover;
        this.latestDate = latestDate;
    }
}
