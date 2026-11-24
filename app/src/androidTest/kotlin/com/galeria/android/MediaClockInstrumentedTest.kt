package com.galeria.android

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MediaClockInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val overdue get() = System.currentTimeMillis() - MediaClockRules.DURATION_MS - 1_000L

    @Test fun anExpiredClockSendsTheVideoToTheTrashEvenWithTheTrashOff() = withSettings(trash = false, permanent = false) {
        val video = insertVideo("Movies/GaleriaClockTrash-${System.nanoTime()}/")
        try {
            MediaClocks.start(context, listOf(video.toString()), now = overdue)
            assertEquals(0, MediaClockExpiry.runDue(context))
            assertEquals("O vídeo deve ir para a Lixeira", 1, trashedFlag(video))
            assertNull("O relógio termina", MediaClocks.of(context, video.toString()))
        } finally {
            MediaClocks.cancel(context, video.toString())
            context.contentResolver.delete(video, null, null)
        }
    }

    @Test fun withTheTrashOnTheClockStillOnlyTrashes() = withSettings(trash = true, permanent = true) {
        val video = insertVideo("Movies/GaleriaClockTrashOn-${System.nanoTime()}/")
        try {
            MediaClocks.start(context, listOf(video.toString()), now = overdue)
            MediaClockExpiry.runDue(context)
            assertEquals("Com a Lixeira ligada a exclusão nunca é definitiva", 1, trashedFlag(video))
        } finally {
            MediaClocks.cancel(context, video.toString())
            context.contentResolver.delete(video, null, null)
        }
    }

    @Test fun withNoTrashAndPermanentOnTheClockDeletesForGood() = withSettings(trash = false, permanent = true) {
        val video = insertVideo("Movies/GaleriaClockDelete-${System.nanoTime()}/")
        try {
            MediaClocks.start(context, listOf(video.toString()), now = overdue)
            assertEquals(0, MediaClockExpiry.runDue(context))
            assertNull("O vídeo deve ser excluído definitivamente", trashedFlag(video))
        } finally {
            MediaClocks.cancel(context, video.toString())
            runCatching { context.contentResolver.delete(video, null, null) }
        }
    }

    @Test fun aClockStillRunningLeavesTheMediaAlone() = withSettings(trash = true, permanent = false) {
        val video = insertVideo("Movies/GaleriaClockRunning-${System.nanoTime()}/")
        try {
            MediaClocks.start(context, listOf(video.toString()))
            MediaClockExpiry.runDue(context)
            assertEquals(0, trashedFlag(video))
            assertNotNull(MediaClocks.of(context, video.toString()))
        } finally {
            MediaClocks.cancel(context, video.toString())
            context.contentResolver.delete(video, null, null)
        }
    }

    @Test fun tappingTheClockOnTheGridKeepsTheMedia() {
        val folder = "Movies/GaleriaClockGrid-${System.nanoTime()}/"
        val video = insertVideo(folder)
        try {
            MediaClocks.start(context, listOf(video.toString()))
            val intent = Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", folder)
                putExtra("album_name", "Relógio")
            }
            ActivityScenario.launch<AlbumMediaActivity>(intent).use { scenario ->
                val badge = awaitBadge { scenario.onActivity(it) }
                var label = ""
                scenario.onActivity { label = badge.text.toString() }
                assertEquals("48 h", label)
                scenario.onActivity { assertTrue(badge.performClick()) }
                assertNull("Tocar no relógio cancela a exclusão", MediaClocks.of(context, video.toString()))
                awaitGone(badge) { scenario.onActivity(it) }
            }
        } finally {
            MediaClocks.cancel(context, video.toString())
            context.contentResolver.delete(video, null, null)
        }
    }

    @Test fun tappingTheClockInTheViewerKeepsTheMedia() {
        val video = insertVideo("Movies/GaleriaClockViewer-${System.nanoTime()}/")
        try {
            MediaClocks.start(context, listOf(video.toString()))
            val intent = ViewerLaunch.internal(context, Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", video.toString())
                putExtra("mime", "video/mp4")
            })
            ActivityScenario.launch<DetailActivity>(intent).use { scenario ->
                val badge = awaitBadge { scenario.onActivity(it) }
                scenario.onActivity { assertTrue(badge.performClick()) }
                assertNull(MediaClocks.of(context, video.toString()))
                awaitGone(badge) { scenario.onActivity(it) }
            }
        } finally {
            MediaClocks.cancel(context, video.toString())
            context.contentResolver.delete(video, null, null)
        }
    }

    private fun <A : android.app.Activity> awaitBadge(onActivity: (ActivityScenario.ActivityAction<A>) -> Unit): TextView {
        var found: TextView? = null
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            onActivity(ActivityScenario.ActivityAction { activity ->
                found = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .firstOrNull { it.tag == ClockBadge.TAG && it.isShown }
            })
            if (found == null) SystemClock.sleep(150L)
        }
        return requireNotNull(found) { "O relógio deve aparecer" }
    }

    private fun <A : android.app.Activity> awaitGone(badge: View, onActivity: (ActivityScenario.ActivityAction<A>) -> Unit) {
        var visible = true
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (visible && SystemClock.uptimeMillis() < deadline) {
            onActivity(ActivityScenario.ActivityAction { visible = badge.visibility == View.VISIBLE })
            if (visible) SystemClock.sleep(100L)
        }
        assertEquals("O relógio sai depois do toque", false, visible)
    }

    private fun descendants(view: View): List<View> =
        if (view is android.view.ViewGroup) listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else listOf(view)

    /** 1 in the Trash, 0 still in the gallery, null deleted for good. */
    private fun trashedFlag(uri: Uri): Int? {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    private fun withSettings(trash: Boolean, permanent: Boolean, block: () -> Unit) {
        val prefs = GalleryPreferences.prefs(context)
        val hadTrash = prefs.contains(TrashPreferences.ENABLED)
        val oldTrash = prefs.getBoolean(TrashPreferences.ENABLED, true)
        val hadPermanent = prefs.contains(GalleryPreferences.CLOCK_DELETES_PERMANENTLY)
        val oldPermanent = prefs.getBoolean(GalleryPreferences.CLOCK_DELETES_PERMANENTLY, false)
        prefs.edit().putBoolean(TrashPreferences.ENABLED, trash).putBoolean(GalleryPreferences.CLOCK_DELETES_PERMANENTLY, permanent).commit()
        try {
            block()
        } finally {
            prefs.edit().apply {
                if (hadTrash) putBoolean(TrashPreferences.ENABLED, oldTrash) else remove(TrashPreferences.ENABLED)
                if (hadPermanent) putBoolean(GalleryPreferences.CLOCK_DELETES_PERMANENTLY, oldPermanent) else remove(GalleryPreferences.CLOCK_DELETES_PERMANENTLY)
            }.commit()
        }
    }

    private fun insertVideo(folder: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "clock-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            instrumentation.context.assets.open("playback-sample.mp4").use { it.copyTo(out) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
