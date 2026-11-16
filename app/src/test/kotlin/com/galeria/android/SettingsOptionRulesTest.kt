package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOptionRulesTest {
    @Test
    fun sideTapsUseOnlyTheEdges() {
        assertEquals(-1, ViewerGestureRules.sideTapDirection(10f, 1000f))
        assertEquals(0, ViewerGestureRules.sideTapDirection(500f, 1000f))
        assertEquals(1, ViewerGestureRules.sideTapDirection(990f, 1000f))
        assertEquals(0, ViewerGestureRules.sideTapDirection(10f, 0f))
    }

    @Test
    fun pullDownClosesAfterEnoughDistance() {
        assertFalse(ViewerGestureRules.closesOnRelease(100f, 2000f, 300f))
        assertFalse(ViewerGestureRules.closesOnRelease(300f, 2000f, 400f))
        assertTrue(ViewerGestureRules.closesOnRelease(500f, 2000f, 400f))
        assertTrue("a quarter of a short screen is enough", ViewerGestureRules.closesOnRelease(260f, 1000f, 400f))
    }

    @Test
    fun onlyADownwardVerticalSwipeIsAPullDown() {
        assertTrue(ViewerGestureRules.isPullDown(SwipeIntent(SwipeAxis.VERTICAL, -1)))
        assertFalse(ViewerGestureRules.isPullDown(SwipeIntent(SwipeAxis.VERTICAL, 1)))
        assertFalse(ViewerGestureRules.isPullDown(SwipeIntent(SwipeAxis.HORIZONTAL, -1)))
    }

    @Test
    fun brightnessGestureAloneUsesOnlyTheLeftHalf() {
        assertTrue(ViewerGestureRules.verticalAdjustAllowed(false, true, 100f, 1000f))
        assertFalse(ViewerGestureRules.verticalAdjustAllowed(false, true, 900f, 1000f))
        assertTrue(ViewerGestureRules.verticalAdjustAllowed(true, false, 900f, 1000f))
        assertFalse(ViewerGestureRules.verticalAdjustAllowed(false, false, 100f, 1000f))
    }

    @Test
    fun twistTurnsAQuarterOnlyPastFortyFiveDegrees() {
        assertEquals(0, ViewerGestureRules.quarterTurns(30f))
        assertEquals(1, ViewerGestureRules.quarterTurns(50f))
        assertEquals(-1, ViewerGestureRules.quarterTurns(-90f))
    }

    @Test
    fun systemFoldersAreNeverRemovedWhenEmpty() {
        assertFalse(EmptyFolderRules.mayRemove("DCIM"))
        assertFalse(EmptyFolderRules.mayRemove("DCIM/Camera/"))
        assertFalse(EmptyFolderRules.mayRemove("Pictures"))
        assertFalse(EmptyFolderRules.mayRemove("Android/media/app"))
        assertFalse(EmptyFolderRules.mayRemove("Pictures/../DCIM"))
        assertFalse(EmptyFolderRules.mayRemove(""))
        assertFalse(EmptyFolderRules.mayRemove(null))
        assertTrue(EmptyFolderRules.mayRemove("Pictures/Viagem"))
        assertTrue(EmptyFolderRules.mayRemove("DCIM\\Festa\\"))
    }

    @Test
    fun onlyANomediaMarkerCountsAsEmpty() {
        assertTrue(EmptyFolderRules.isEmpty(emptyList()))
        assertTrue(EmptyFolderRules.isEmpty(listOf(".nomedia")))
        assertFalse(EmptyFolderRules.isEmpty(listOf(".trashed-1700000000-foto.jpg")))
        assertFalse(EmptyFolderRules.isEmpty(listOf(".nomedia", "foto.jpg")))
    }

    @Test
    fun modifiedDateFollowsThePreference() {
        assertEquals(1_000L, ModifiedDatePolicy.dateAfterTransfer(true, 1_000L, 9_000L))
        assertEquals(9_000L, ModifiedDatePolicy.dateAfterTransfer(false, 1_000L, 9_000L))
        assertEquals("unknown source date uses now", 9_000L, ModifiedDatePolicy.dateAfterTransfer(true, 0L, 9_000L))
    }

    @Test
    fun choicesReadCodesAndOldPortugueseLabels() {
        val spec = ChoicePreferences.DATE_TIME
        assertEquals(0, spec.indexOf(null))
        assertEquals(1, spec.indexOf(DateTimeDisplay.SHORT_DATE))
        assertEquals("labels saved by older versions still work", 2, spec.indexOf("Data e hora"))
        assertEquals(0, spec.indexOf("Padrao do sistema"))
        assertEquals(0, spec.indexOf("something else"))
        assertEquals(ThumbnailStyle.ROUNDED, ChoicePreferences.FOLDER_THUMBNAIL.codeOf(null))
        assertEquals(ThumbnailStyle.CIRCULAR, ChoicePreferences.FOLDER_THUMBNAIL.codeOf("Circular"))
        assertEquals("sensor", ChoicePreferences.ROTATION.codeOf("Sensor"))
        assertEquals(LoadingPriority.BALANCED, ChoicePreferences.LOADING_PRIORITY.codeOf("Equilibrado"))
    }

    @Test
    fun loadingPriorityOnlyEnlargesThumbnailsWithinLimits() {
        assertEquals(300, LoadingPriority.thumbnailSize(300, LoadingPriority.thumbnailScale(LoadingPriority.SPEED)))
        assertEquals(450, LoadingPriority.thumbnailSize(300, LoadingPriority.thumbnailScale(LoadingPriority.QUALITY)))
        assertEquals(375, LoadingPriority.thumbnailSize(300, LoadingPriority.thumbnailScale(LoadingPriority.BALANCED)))
        assertEquals(LoadingPriority.MAX_THUMBNAIL_PX, LoadingPriority.thumbnailSize(900, 1.5f))
    }

    @Test
    fun thumbnailStylesKeepTheCurrentLookByDefault() {
        assertEquals(5, ThumbnailStyle.fileCornerDp(ThumbnailStyle.DEFAULT, 5))
        assertEquals(0, ThumbnailStyle.fileCornerDp(ThumbnailStyle.SQUARE, 5))
        assertTrue(ThumbnailStyle.fileCornerDp(ThumbnailStyle.ROUNDED, 5) > 5)
        assertEquals(8, ThumbnailStyle.folderCornerDp(ThumbnailStyle.ROUNDED))
        assertEquals(0, ThumbnailStyle.folderCornerDp(ThumbnailStyle.SQUARE))
        assertEquals(40, ThumbnailStyle.folderCornerDp(ThumbnailStyle.CIRCULAR))
    }

    @Test
    fun unknownLanguageFallsBackToPortuguese() {
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.normalized(null))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.normalized("fr"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.normalized("en"))
        assertEquals("es", AppLanguage.locale(AppLanguage.SPANISH).language)
    }

    @Test
    fun newViewerGesturesStartOffSoSwipingKeepsWorking() {
        assertFalse(GalleryPreferences.defaultOf(GalleryPreferences.SWIPE_DOWN_TO_CLOSE))
        assertFalse(GalleryPreferences.defaultOf(GalleryPreferences.IMAGE_ROTATION_GESTURES))
        assertTrue(GalleryPreferences.defaultOf(GalleryPreferences.KEEP_MODIFIED_DATE))
        assertFalse(GalleryPreferences.defaultOf(GalleryPreferences.DELETE_EMPTY_FOLDERS))
    }
}

class ReviewFixRulesTest {
    @Test
    fun sharingStopsAtTheAndroidLimit() {
        assertEquals(10, ShareLimit.limited(List(10) { it }).size)
        assertEquals(ShareLimit.MAX_ITEMS, ShareLimit.limited(List(5_000) { it }).size)
        assertEquals("keeps the selection order", 0, ShareLimit.limited(List(5_000) { it }).first())
    }

    @Test
    fun onlyTheGallerysOwnTokenIsTrusted() {
        assertTrue(ViewerLaunch.isTrusted("abc123", "abc123"))
        assertFalse(ViewerLaunch.isTrusted(null, "abc123"))
        assertFalse(ViewerLaunch.isTrusted("abc124", "abc123"))
        assertFalse(ViewerLaunch.isTrusted("", ""))
    }

    @Test
    fun externalLaunchesLoseEveryInternalOption() {
        listOf("uri", "album_key", "include_hidden_filesystem", "shuffle_mode", "path", AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)
            .forEach { assertTrue(it, it in ViewerLaunch.internalExtras) }
    }
}
