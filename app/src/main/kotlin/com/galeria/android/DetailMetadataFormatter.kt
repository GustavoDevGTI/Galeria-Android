package com.galeria.android

import java.util.Locale
import kotlin.math.abs

internal data class DetailMediaMetadata(
    val name: String,
    val mimeType: String,
    val relativePath: String,
    val dateAddedSeconds: Long
)

internal data class DetailMetadataField(val label: String, val value: String)
internal data class DetailMetadataSection(val title: String, val fields: List<DetailMetadataField>)

internal class DetailMetadataFormatter(
    private val fileSizeFormatter: (Long) -> String,
    private val dateAddedFormatter: (Long) -> String,
    private val durationFormatter: (Long) -> String,
    private val displayLocale: Locale = Locale.getDefault()
) {
    fun imageSections(media: DetailMediaMetadata, metadata: DetailImageMetadata, sizeBytes: Long?): List<DetailMetadataSection> = buildList {
        add(DetailMetadataSection("Arquivo", buildList {
            add(DetailMetadataField("Nome", media.name))
            add(DetailMetadataField("Data", date(media)))
            sizeBytes?.let { add(DetailMetadataField("Tamanho", fileSizeFormatter(it))) }
            add(DetailMetadataField("Formato", media.mimeType.ifEmpty { UNKNOWN }))
            if (media.relativePath.isNotBlank()) add(DetailMetadataField("Pasta", media.relativePath.trimEnd('/')))
        }))
        val image = buildList {
            if (metadata.width != null && metadata.height != null) {
                add(DetailMetadataField("Resolução", "${metadata.width} × ${metadata.height}"))
            }
            listOfNotNull(metadata.make, metadata.model).joinToString(" ").takeIf { it.isNotBlank() }?.let {
                add(DetailMetadataField("Câmera", it))
            }
            metadata.capturedAt?.let { add(DetailMetadataField("Capturada em", it)) }
            metadata.iso?.let { add(DetailMetadataField("ISO", it)) }
            metadata.aperture?.let { add(DetailMetadataField("Abertura", "f/$it")) }
            metadata.exposure?.let { add(DetailMetadataField("Exposição", "${it}s")) }
            metadata.focalLength?.let { add(DetailMetadataField("Distância focal", "$it mm")) }
        }
        if (image.isNotEmpty()) add(DetailMetadataSection("Imagem e câmera", image))
        if (metadata.hasLocation) {
            add(DetailMetadataSection("Localização", listOf(DetailMetadataField(
                "Coordenadas",
                "${String.format(Locale.US, "%.6f", metadata.latitude)}, " +
                    String.format(Locale.US, "%.6f", metadata.longitude)
            ))))
        }
    }

    fun videoSections(media: DetailMediaMetadata, metadata: DetailVideoMetadata, sizeBytes: Long?): List<DetailMetadataSection> = buildList {
        add(DetailMetadataSection("Arquivo", buildList {
            add(DetailMetadataField("Nome", media.name))
            add(DetailMetadataField("Data", date(media)))
            sizeBytes?.takeIf { it > 0L }?.let { add(DetailMetadataField("Tamanho", fileSizeFormatter(it))) }
            add(DetailMetadataField("Formato", media.mimeType.ifEmpty { metadata.detectedMime ?: UNKNOWN }))
            if (media.relativePath.isNotBlank()) add(DetailMetadataField("Pasta", media.relativePath.trimEnd('/')))
        }))
        val video = buildList {
            metadata.durationMs?.takeIf { it > 0L }?.let { add(DetailMetadataField("Duração", durationFormatter(it))) }
            if (metadata.width != null && metadata.height != null && metadata.width > 0 && metadata.height > 0) {
                add(DetailMetadataField("Resolução", "${metadata.width} × ${metadata.height}"))
            }
            if (metadata.rotationDegrees != 0) add(DetailMetadataField("Rotação", "${metadata.rotationDegrees}°"))
            metadata.codec?.let { add(DetailMetadataField("Codec", it)) }
            metadata.bitRate?.takeIf { it > 0L }?.let { add(DetailMetadataField("Taxa de bits", formatBitRate(it))) }
            metadata.frameRate?.takeIf { it > 0f }?.let {
                add(DetailMetadataField("Quadros por segundo", formatFrameRate(it)))
            }
        }
        if (video.isNotEmpty()) add(DetailMetadataSection("Vídeo", video))
    }

    fun imageInformation(media: DetailMediaMetadata, metadata: DetailImageMetadata, sizeBytes: Long?): String =
        plainText(imageSections(media, metadata, sizeBytes))

    fun videoInformation(media: DetailMediaMetadata, metadata: DetailVideoMetadata, sizeBytes: Long?): String =
        plainText(videoSections(media, metadata, sizeBytes))

    private fun plainText(sections: List<DetailMetadataSection>): String = sections
        .flatMap { it.fields }
        .joinToString("\n") { "${it.label}: ${it.value}" }

    private fun date(media: DetailMediaMetadata): String =
        if (media.dateAddedSeconds > 0L) dateAddedFormatter(media.dateAddedSeconds) else "Desconhecida"

    private fun formatBitRate(bitsPerSecond: Long): String =
        if (bitsPerSecond >= 1_000_000L) {
            String.format(displayLocale, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        } else {
            String.format(displayLocale, "%.0f kbps", bitsPerSecond / 1_000.0)
        }

    private fun formatFrameRate(frameRate: Float): String =
        if (abs(frameRate - frameRate.toInt()) < 0.01f) {
            frameRate.toInt().toString()
        } else {
            String.format(displayLocale, "%.2f", frameRate)
        }

    private companion object {
        const val UNKNOWN = "Desconhecido"
    }
}
