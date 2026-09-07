package com.lchuang.xiaozhimobile.artifacts

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class ArtifactStatus {
    STAGED,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/** Metadata for one validated artifact version kept in the private workspace. */
data class Artifact(
    val artifactId: String,
    val sessionId: String,
    val mimeType: String,
    val displayName: String,
    val privatePath: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val sourceAgent: String,
    val version: Int = 1,
    val status: ArtifactStatus = ArtifactStatus.COMPLETED,
    val parentVersion: Int? = null,
) {
    init {
        require(ArtifactIds.isSafe(artifactId)) { "invalid artifact id" }
        require(ArtifactText.isSafe(sessionId, MAX_SESSION_ID_LENGTH)) { "invalid session id" }
        require(ArtifactText.isSafe(mimeType, MAX_MIME_TYPE_LENGTH)) { "invalid mime type" }
        require(ArtifactText.isSafe(displayName, MAX_DISPLAY_NAME_LENGTH)) { "invalid display name" }
        require('/' !in displayName && '\\' !in displayName) { "display name must not contain a path" }
        require(File(privatePath).isAbsolute) { "artifact path must be absolute" }
        require(size >= 0L) { "artifact size must not be negative" }
        require(ArtifactDigest.isSha256(sha256)) { "artifact digest must be SHA-256" }
        require(createdAt >= 0L) { "artifact timestamp must not be negative" }
        require(ArtifactText.isSafe(sourceAgent, MAX_SOURCE_AGENT_LENGTH)) { "invalid source agent" }
        require(version >= 1) { "artifact version must be positive" }
        require(parentVersion == null || parentVersion == version - 1) {
            "artifact version lineage must point to the previous version"
        }
    }

    val versionNumber: Int
        get() = version

    companion object {
        private const val MAX_SESSION_ID_LENGTH = 256
        private const val MAX_MIME_TYPE_LENGTH = 128
        private const val MAX_DISPLAY_NAME_LENGTH = 255
        private const val MAX_SOURCE_AGENT_LENGTH = 128
    }
}

internal object ArtifactIds {
    private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun isSafe(value: String): Boolean = PATTERN.matches(value)
}

internal object ArtifactText {
    fun isSafe(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value == value.trim() && value.length <= maxLength &&
            value.none { it.code < 0x20 || it == '\u007f' }
}

object ArtifactDigest {
    const val MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L
    private const val BUFFER_SIZE = 8 * 1024
    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

    fun isSha256(value: String): Boolean = SHA256_PATTERN.matches(value)

    fun sha256(file: File, maxBytes: Long = MAX_ARTIFACT_BYTES): String {
        require(file.isFile) { "artifact content must be a file" }
        require(file.length() <= maxBytes) { "artifact exceeds the size limit" }
        return FileInputStream(file).use { sha256(it, maxBytes) }
    }

    fun sha256(input: java.io.InputStream, maxBytes: Long = MAX_ARTIFACT_BYTES): String {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "artifact exceeds the size limit" }
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
