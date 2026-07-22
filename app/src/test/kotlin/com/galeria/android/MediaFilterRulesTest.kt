package com.galeria.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilterRulesTest {
    @Test
    fun specializedImagesRespectTheirOwnFilters() {
        val options = MediaFilterOptions(
            showImages = true,
            showVideos = false,
            showGifs = false,
            showRaw = false,
            showSvgs = false
        )

        assertTrue(MediaFilterRules.matches("foto.jpg", "image/jpeg", options))
        assertFalse(MediaFilterRules.matches("animacao.gif", "image/gif", options))
        assertFalse(MediaFilterRules.matches("icone.svg", "image/svg+xml", options))
        assertFalse(MediaFilterRules.matches("captura.dng", "image/x-adobe-dng", options))
    }

    @Test
    fun extensionsAreRecognizedWhenMimeTypeIsGeneric() {
        val onlySpecialized = MediaFilterOptions(
            showImages = false,
            showVideos = false,
            showGifs = true,
            showRaw = true,
            showSvgs = true
        )

        assertTrue(MediaFilterRules.matches("animacao.GIF", "application/octet-stream", onlySpecialized))
        assertTrue(MediaFilterRules.matches("icone.SVG", "text/plain", onlySpecialized))
        assertTrue(MediaFilterRules.matches("captura.CR2", "application/octet-stream", onlySpecialized))
    }

    @Test
    fun videosAndUnknownFilesDoNotLeakIntoImageFilter() {
        val imagesOnly = MediaFilterOptions(showVideos = false)

        assertFalse(MediaFilterRules.matches("filme.mp4", "video/mp4", imagesOnly))
        assertFalse(MediaFilterRules.matches("documento.pdf", "application/pdf", imagesOnly))
    }

    @Test
    fun portraitOptionKeepsImagesVisibleWhenGeneralImagesAreDisabled() {
        val portraitsOnly = MediaFilterOptions(showImages = false, showPortraits = true)

        assertTrue(MediaFilterRules.matches("retrato.jpg", "image/jpeg", portraitsOnly))
    }

    @Test
    fun externalMediaKeepsDeclaredImageAndVideoTypes() {
        assertTrue(ExternalMediaRules.isSupported(ExternalMediaRules.normalizedMime("foto.jpg", "image/jpeg")))
        assertTrue(ExternalMediaRules.isSupported(ExternalMediaRules.normalizedMime("filme.mp4", "video/mp4")))
    }

    @Test
    fun externalMediaInfersGenericDownloadsByExtension() {
        assertTrue(ExternalMediaRules.normalizedMime("captura.CR2", "application/octet-stream").startsWith("image/"))
        assertTrue(ExternalMediaRules.normalizedMime("gravacao.MKV", null).startsWith("video/"))
        assertFalse(ExternalMediaRules.isSupported(ExternalMediaRules.normalizedMime("documento.pdf", "application/pdf")))
    }
}
