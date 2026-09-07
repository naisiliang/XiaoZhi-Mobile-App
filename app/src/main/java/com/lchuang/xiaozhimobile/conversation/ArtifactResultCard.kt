package com.lchuang.xiaozhimobile.conversation

import com.lchuang.xiaozhimobile.artifacts.Artifact
import com.lchuang.xiaozhimobile.artifacts.ArtifactStatus
import com.lchuang.xiaozhimobile.image.ImageArtifact

enum class ArtifactCardAction {
    OPEN,
    SAVE,
    SHARE,
    EDIT,
    REGENERATE,
    RESTORE,
}

/** Safe conversation-facing summary; it intentionally does not expose a private filesystem path. */
data class ArtifactResultCard(
    val artifactId: String,
    val version: Int,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val actions: Set<ArtifactCardAction>,
) {
    init {
        require(artifactId.isNotBlank()) { "artifact card id must not be blank" }
        require(version >= 1) { "artifact card version must be positive" }
        require(displayName.isNotBlank()) { "artifact card name must not be blank" }
        require(mimeType.isNotBlank()) { "artifact card mime type must not be blank" }
        require(size >= 0L) { "artifact card size must not be negative" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "artifact card digest must be SHA-256" }
        require(actions.isNotEmpty()) { "artifact card must expose an action" }
    }

    companion object {
        fun fromArtifact(
            artifact: Artifact,
            hasPreviousVersion: Boolean,
        ): ArtifactResultCard {
            require(artifact.status == ArtifactStatus.COMPLETED) {
                "only completed artifacts can be shown as result cards"
            }
            val actions = linkedSetOf(
                ArtifactCardAction.OPEN,
                ArtifactCardAction.SAVE,
                ArtifactCardAction.SHARE,
                ArtifactCardAction.EDIT,
            )
            if (hasPreviousVersion) actions += ArtifactCardAction.RESTORE
            return ArtifactResultCard(
                artifactId = artifact.artifactId,
                version = artifact.version,
                displayName = artifact.displayName,
                mimeType = artifact.mimeType,
                size = artifact.size,
                sha256 = artifact.sha256,
                actions = actions,
            )
        }

        fun fromImageArtifact(
            image: ImageArtifact,
            hasPreviousVersion: Boolean,
        ): ArtifactResultCard {
            val card = fromArtifact(image.artifact, hasPreviousVersion)
            val actions = card.actions.toMutableSet().apply {
                add(ArtifactCardAction.REGENERATE)
            }
            return card.copy(actions = actions)
        }
    }
}
