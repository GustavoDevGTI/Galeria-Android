package com.galeria.android

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import okio.Path.Companion.toPath

class GalleryApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.ERROR)
            .build()

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
