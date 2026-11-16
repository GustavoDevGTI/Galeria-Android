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
    private val displayLocale: Locale = Locale.getDefault(),
    /** Translates a Portuguese label; tests keep the Portuguese text. */
    private val label: (String) -> String = { it }
) {
    fun imageSections(media: DetailMediaMetadata, metadata: DetailImageMetadata, sizeBytes: Long?): List<DetailMetadataSection> = buildList {
        add(DetailMetadataSection(label("Arquivo"), buildList {
            add(DetailMetadataField(label("Nome"), media.name))
            add(DetailMetadataField(label("Data"), date(media)))
            sizeBytes?.let { add(DetailMetadataField(label("Tamanho"), fileSizeFormatter(it))) }
            add(DetailMetadataField(label("Formato"), media.mimeType.ifEmpty { label(UNKNOWN) }))
            if (media.relativePath.isNotBlank()) add(DetailMetadataField(label("Pasta"), media.relativePath.trimEnd('/')))
        }))
        val image = buildList {
            if (metadata.width != null && metadata.height != null) {
                add(DetailMetadataField(label("Resolução"), "${metadata.width} × ${metadata.height}"))
            }
            listOfNotNull(metadata.make, metadata.model).joinToString(" ").takeIf { it.isNotBlank() }?.let {
                add(DetailMetadataField(label("Câmera"), it))
            }
            metadata.capturedAt?.let { add(DetailMetadataField(label("Capturada em"), it)) }
            metadata.iso?.let { add(DetailMetadataField(label("ISO"), it)) }
            metadata.aperture?.let { add(DetailMetadataField(label("Abertura"), "f/$it")) }
            metadata.exposure?.let { add(DetailMetadataField(label("Exposição"), "${it}s")) }
            metadata.focalLength?.let { add(DetailMetadataField(label("Distância focal"), "$it mm")) }
        }
        if (image.isNotEmpty()) add(DetailMetadataSection(label("Imagem e câmera"), image))
        if (metadata.hasLocation) {
            add(DetailMetadataSection(label("Localização"), listOf(DetailMetadataField(
                label("Coordenadas"),
                "${String.format(Locale.US, "%.6f", metadata.latitude)}, " +
                    String.format(Locale.US, "%.6f", metadata.longitude)
            ))))
        }
    }

    fun videoSections(media: DetailMediaMetadata, metadata: DetailVideoMetadata, sizeBytes: Long?): List<DetailMetadataSection> = buildList {
        add(DetailMetadataSection(label("Arquivo"), buildList {
            add(DetailMetadataField(label("Nome"), media.name))
            add(DetailMetadataField(label("Data"), date(media)))
            sizeBytes?.takeIf { it > 0L }?.let { add(DetailMetadataField(label("Tamanho"), fileSizeFormatter(it))) }
            add(DetailMetadataField(label("Formato"), media.mimeType.ifEmpty { metadata.detectedMime ?: label(UNKNOWN) }))
            if (media.relativePath.isNotBlank()) add(DetailMetadataField(label("Pasta"), media.relativePath.trimEnd('/')))
        }))
        val video = buildList {
            metadata.durationMs?.takeIf { it > 0L }?.let { add(DetailMetadataField(label("Duração"), durationFormatter(it))) }
            if (metadata.width != null && metadata.height != null && metadata.width > 0 && metadata.height > 0) {
                add(DetailMetadataField(label("Resolução"), "${metadata.width} × ${metadata.height}"))
            }
            if (metadata.rotationDegrees != 0) add(DetailMetadataField(label("Rotação"), "${metadata.rotationDegrees}°"))
            metadata.codec?.let { add(DetailMetadataField(label("Codec"), it)) }
            metadata.bitRate?.takeIf { it > 0L }?.let { add(DetailMetadataField(label("Taxa de bits"), formatBitRate(it))) }
            metadata.frameRate?.takeIf { it > 0f }?.let {
                add(DetailMetadataField(label("Quadros por segundo"), formatFrameRate(it)))
            }
        }
        if (video.isNotEmpty()) add(DetailMetadataSection(label("Vídeo"), video))
    }

    fun imageInformation(media: DetailMediaMetadata, metadata: DetailImageMetadata, sizeBytes: Long?): String =
        plainText(imageSections(media, metadata, sizeBytes))

    fun videoInformation(media: DetailMediaMetadata, metadata: DetailVideoMetadata, sizeBytes: Long?): String =
        plainText(videoSections(media, metadata, sizeBytes))

    private fun plainText(sections: List<DetailMetadataSection>): String = sections
        .flatMap { it.fields }
        .joinToString("\n") { "${it.label}: ${it.value}" }

    private fun date(media: DetailMediaMetadata): String =
        if (media.dateAddedSeconds > 0L) dateAddedFormatter(media.dateAddedSeconds) else label("Desconhecida")

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
