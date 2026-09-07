package com.lchuang.xiaozhimobile.artifacts

/** Immutable metadata for one point in an artifact's version lineage. */
data class ArtifactVersion(
    val artifactId: String,
    val version: Int,
    val privatePath: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val sourceAgent: String,
    val parentVersion: Int? = null,
    val mimeType: String = "application/octet-stream",
    val displayName: String = "artifact",
) {
    init {
        require(ArtifactIds.isSafe(artifactId)) { "invalid artifact id" }
        require(version >= 1) { "artifact version must be positive" }
        require(java.io.File(privatePath).isAbsolute) { "artifact path must be absolute" }
        require(size >= 0L) { "artifact size must not be negative" }
        require(ArtifactDigest.isSha256(sha256)) { "artifact digest must be SHA-256" }
        require(createdAt >= 0L) { "artifact timestamp must not be negative" }
        require(ArtifactText.isSafe(sourceAgent, 128)) { "invalid source agent" }
        require(parentVersion == null || parentVersion == version - 1) {
            "artifact version lineage must point to the previous version"
        }
    }

    val versionNumber: Int
        get() = version

    companion object {
        fun fromArtifact(artifact: Artifact): ArtifactVersion = ArtifactVersion(
            artifactId = artifact.artifactId,
            version = artifact.version,
            privatePath = artifact.privatePath,
            size = artifact.size,
            sha256 = artifact.sha256.lowercase(),
            createdAt = artifact.createdAt,
            sourceAgent = artifact.sourceAgent,
            parentVersion = artifact.parentVersion,
            mimeType = artifact.mimeType,
            displayName = artifact.displayName,
        )
    }
}
