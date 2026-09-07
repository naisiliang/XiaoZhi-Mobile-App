package com.lchuang.xiaozhimobile.image

import com.lchuang.xiaozhimobile.artifacts.Artifact
import com.lchuang.xiaozhimobile.artifacts.ArtifactStatus
import java.io.File

data class ImageFormat(
    val extension: String,
    val mimeType: String,
)

/** Minimal signature validation for generated image bytes before they become an Artifact. */
object ImageArtifactValidator {
    const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L

    fun formatFor(bytes: ByteArray, declaredMimeType: String? = null): ImageFormat? {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_IMAGE_BYTES) return null
        val detected = when {
            bytes.startsWith(PNG_SIGNATURE) -> ImageFormat("png", "image/png")
            bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() && bytes[bytes.lastIndex - 1] == 0xFF.toByte() &&
                bytes.last() == 0xD9.toByte() -> ImageFormat("jpg", "image/jpeg")
            bytes.startsWith(GIF87_SIGNATURE) || bytes.startsWith(GIF89_SIGNATURE) ->
                ImageFormat("gif", "image/gif")
            bytes.size >= 12 && bytes.startsWith(RIFF_SIGNATURE) &&
                bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) ->
                ImageFormat("webp", "image/webp")
            else -> null
        } ?: return null
        val declared = declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != null && declared.isNotBlank() && declared != detected.mimeType) return null
        return detected
    }

    fun validate(file: File, expectedMimeType: String? = null): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_IMAGE_BYTES) return false
        return runCatching { formatFor(file.readBytes(), expectedMimeType) != null }.getOrDefault(false)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val GIF87_SIGNATURE = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89_SIGNATURE = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
}

data class ImageArtifact(
    val artifact: Artifact,
    val prompt: String,
    val mode: ImageIntentMode,
    val sourceArtifactId: String? = null,
    val sourceVersion: Int? = null,
) {
    init {
        require(artifact.status == ArtifactStatus.COMPLETED) { "image artifact must be completed" }
        require(artifact.mimeType.startsWith("image/")) { "artifact must contain image content" }
        require(prompt.isNotBlank()) { "image prompt must not be blank" }
        require((sourceArtifactId == null) == (sourceVersion == null)) {
            "image source lineage must be complete"
        }
        require(sourceVersion == null || sourceVersion >= 1) { "image source version must be positive" }
    }
}
