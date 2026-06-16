package com.galeria.android

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MediaCatalogCache {
    private const val FILE_NAME = "album_catalog_cache.json"

    @JvmStatic
    fun readAlbums(context: Context): List<AlbumItem> {
        val file = cacheFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(file.readText())
            ArrayList<AlbumItem>(array.length()).apply {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val coverObj = obj.optJSONObject("cover")
                    val cover = coverObj?.let {
                        MediaItem(
                            it.optLong("id"),
                            Uri.parse(it.optString("uri")),
                            it.optString("name"),
                            it.optString("mime"),
                            it.optLong("date"),
                            it.optLong("size"),
                            it.optString("path"),
                            obj.optString("key"),
                            obj.optString("name")
                        )
                    }
                    add(
                        AlbumItem(
                            obj.optString("key"),
                            obj.optString("name"),
                            obj.optInt("count"),
                            cover,
                            obj.optLong("latest"),
                            obj.optLong("first"),
                            obj.optLong("total"),
                            obj.optString("path")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @JvmStatic
    fun writeAlbums(context: Context, albums: List<AlbumItem>) {
        try {
            val array = JSONArray()
            for (album in albums.take(500)) {
                val obj = JSONObject()
                    .put("key", album.key)
                    .put("name", album.name)
                    .put("count", album.count)
                    .put("latest", album.latestDate)
                    .put("first", album.firstDate)
                    .put("total", album.totalSize)
                    .put("path", album.path)
                album.cover?.let {
                    obj.put(
                        "cover",
                        JSONObject()
                            .put("id", it.id)
                            .put("uri", it.uri.toString())
                            .put("name", it.name)
                            .put("mime", it.mimeType)
                            .put("date", it.dateAdded)
                            .put("size", it.size)
                            .put("path", it.relativePath)
                    )
                }
                array.put(obj)
            }
            cacheFile(context).writeText(array.toString())
        } catch (_: Exception) {
        }
    }

    private fun cacheFile(context: Context): File = File(context.cacheDir, FILE_NAME)
}
