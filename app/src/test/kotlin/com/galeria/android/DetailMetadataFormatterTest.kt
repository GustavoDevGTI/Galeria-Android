package com.galeria.android

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailMetadataFormatterTest {
    private val formatter = DetailMetadataFormatter(
        fileSizeFormatter = { "$it bytes" },
        dateAddedFormatter = { "data-$it" },
        durationFormatter = { "duracao-$it" },
        displayLocale = Locale.US
    )

    @Test
    fun imageInformationKeepsEveryAvailableFieldInDisplayOrder() {
        val information = formatter.imageInformation(
            media = DetailMediaMetadata(
                name = "foto.jpg",
                mimeType = "image/jpeg",
                relativePath = "Pictures/Camera/",
                dateAddedSeconds = 123L
            ),
            metadata = DetailImageMetadata(
                width = 4000,
                height = 3000,
                make = "Canon",
                model = "EOS",
                capturedAt = "2026:08:20 10:30:00",
                iso = "200",
                aperture = "2.8",
                exposure = "1/125",
                focalLength = "50",
                latitude = -23.55052,
                longitude = -46.633308
            ),
            sizeBytes = 4096L
        )

        assertEquals(
            """
            Nome: foto.jpg
            Resolução: 4000 × 3000
            Tamanho: 4096 bytes
            Formato: image/jpeg
            Pasta: Pictures/Camera
            Adicionado em: data-123
            Câmera: Canon EOS
            Capturada em: 2026:08:20 10:30:00
            ISO: 200
            Abertura: f/2.8
            Exposição: 1/125s
            Distância focal: 50 mm
            Localização: -23.550520, -46.633308
            """.trimIndent(),
            information
        )
    }

    @Test
    fun imageInformationUsesUnknownFormatAndOmitsMissingOptionalFields() {
        val information = formatter.imageInformation(
            DetailMediaMetadata("sem-dados", "", "", 0L),
            DetailImageMetadata(),
            null
        )

        assertEquals("Nome: sem-dados\nFormato: Desconhecido", information)
    }

    @Test
    fun videoInformationKeepsFormattingAndDetectedMetadata() {
        val information = formatter.videoInformation(
            media = DetailMediaMetadata("video.mp4", "", "Movies/", 321L),
            metadata = DetailVideoMetadata(
                durationMs = 65_000L,
                width = 1920,
                height = 1080,
                rotationDegrees = 90,
                bitRate = 2_500_000L,
                frameRate = 29.97f,
                detectedMime = "video/mp4",
                codec = "H.265 / HEVC"
            ),
            sizeBytes = 8192L
        )

        assertEquals(
            """
            Nome: video.mp4
            Duração: duracao-65000
            Resolução: 1920 × 1080
            Rotação: 90°
            Tamanho: 8192 bytes
            Formato: video/mp4
            Codec: H.265 / HEVC
            Taxa de bits: 2.5 Mbps
            Quadros por segundo: 29.97
            Pasta: Movies
            Adicionado em: data-321
            """.trimIndent(),
            information
        )
    }

    @Test
    fun videoInformationFormatsIntegerFrameRateAndKilobits() {
        val information = formatter.videoInformation(
            media = DetailMediaMetadata("clip", "video/webm", "", 0L),
            metadata = DetailVideoMetadata(bitRate = 850_000L, frameRate = 30f),
            sizeBytes = null
        )

        assertEquals(
            "Nome: clip\nFormato: video/webm\nTaxa de bits: 850 kbps\nQuadros por segundo: 30",
            information
        )
    }

    @Test
    fun codecLabelsCoverKnownUnknownAndMissingMimeTypes() {
        assertEquals("H.264 / AVC", DetailMetadataRepository.videoCodecLabel("video/avc"))
        assertEquals("H.265 / HEVC", DetailMetadataRepository.videoCodecLabel("video/hevc"))
        assertEquals("VP9", DetailMetadataRepository.videoCodecLabel("video/x-vnd.on2.vp9"))
        assertEquals("DOLBY-VISION", DetailMetadataRepository.videoCodecLabel("video/dolby-vision"))
        assertEquals(null, DetailMetadataRepository.videoCodecLabel(null))
    }
}
