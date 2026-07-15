package com.galeria.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import okio.Path.Companion.toPath

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        cacheDir.resolve("album_catalog_cache.json").delete()
        MediaScanScheduler.enqueue(this, includeHidden = false, replace = false)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_thumbnails").absolutePath.toPath())
                    .maxSizePercent(0.04)
                    .build()
            }
            .build()
}
