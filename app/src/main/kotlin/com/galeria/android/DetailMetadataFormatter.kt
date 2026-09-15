package com.galeria.android

import java.util.Locale
import kotlin.math.abs

internal data class DetailMediaMetadata(
    val name: String,
    val mimeType: String,
    val relativePath: String,
    val dateAddedSeconds: Long
)

internal class DetailMetadataFormatter(
    private val fileSizeFormatter: (Long) -> String,
    private val dateAddedFormatter: (Long) -> String,
    private val durationFormatter: (Long) -> String,
    private val displayLocale: Locale = Locale.getDefault()
) {
    fun imageInformation(
        media: DetailMediaMetadata,
        metadata: DetailImageMetadata,
        sizeBytes: Long?
    ): String = buildString {
        appendLine("Nome: ${media.name}")
        if (metadata.width != null && metadata.height != null) {
            appendLine("Resolução: ${metadata.width} × ${metadata.height}")
        }
        sizeBytes?.let { appendLine("Tamanho: ${fileSizeFormatter(it)}") }
        appendLine("Formato: ${media.mimeType.ifEmpty { UNKNOWN }}")
        if (media.relativePath.isNotBlank()) appendLine("Pasta: ${media.relativePath.trimEnd('/')}")
        if (media.dateAddedSeconds > 0L) {
            appendLine("Adicionado em: ${dateAddedFormatter(media.dateAddedSeconds)}")
        }
        listOfNotNull(metadata.make, metadata.model).joinToString(" ").takeIf { it.isNotBlank() }?.let {
            appendLine("Câmera: $it")
        }
        metadata.capturedAt?.let { appendLine("Capturada em: $it") }
        metadata.iso?.let { appendLine("ISO: $it") }
        metadata.aperture?.let { appendLine("Abertura: f/$it") }
        metadata.exposure?.let { appendLine("Exposição: ${it}s") }
        metadata.focalLength?.let { appendLine("Distância focal: ${it} mm") }
        if (metadata.hasLocation) {
            append(
                "Localização: ${String.format(Locale.US, "%.6f", metadata.latitude)}, " +
                    String.format(Locale.US, "%.6f", metadata.longitude)
            )
        }
    }.trim()

    fun videoInformation(
        media: DetailMediaMetadata,
        metadata: DetailVideoMetadata,
        sizeBytes: Long?
    ): String = buildString {
        appendLine("Nome: ${media.name}")
        metadata.durationMs?.takeIf { it > 0L }?.let { appendLine("Duração: ${durationFormatter(it)}") }
        if (metadata.width != null && metadata.height != null && metadata.width > 0 && metadata.height > 0) {
            appendLine("Resolução: ${metadata.width} × ${metadata.height}")
        }
        if (metadata.rotationDegrees != 0) appendLine("Rotação: ${metadata.rotationDegrees}°")
        sizeBytes?.takeIf { it > 0L }?.let { appendLine("Tamanho: ${fileSizeFormatter(it)}") }
        appendLine("Formato: ${media.mimeType.ifEmpty { metadata.detectedMime ?: UNKNOWN }}")
        metadata.codec?.let { appendLine("Codec: $it") }
        metadata.bitRate?.takeIf { it > 0L }?.let { appendLine("Taxa de bits: ${formatBitRate(it)}") }
        metadata.frameRate?.takeIf { it > 0f }?.let {
            appendLine("Quadros por segundo: ${formatFrameRate(it)}")
        }
        if (media.relativePath.isNotBlank()) appendLine("Pasta: ${media.relativePath.trimEnd('/')}")
        if (media.dateAddedSeconds > 0L) {
            append("Adicionado em: ${dateAddedFormatter(media.dateAddedSeconds)}")
        }
    }.trim()

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
