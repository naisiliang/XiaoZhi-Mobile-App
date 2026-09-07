package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.Artifact
import com.lchuang.xiaozhimobile.artifacts.ArtifactDigest
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

enum class ArtifactGenerationCode {
    INVALID_INPUT,
    SIZE_LIMIT,
    VALIDATION_FAILED,
    STORAGE_FAILED,
}

sealed interface ArtifactGenerationResult {
    data class Completed(val artifact: Artifact) : ArtifactGenerationResult

    data class Rejected(
        val code: ArtifactGenerationCode,
        val detail: String = "",
    ) : ArtifactGenerationResult
}

internal fun generateValidatedArtifact(
    workspace: ArtifactWorkspace,
    repository: ArtifactRepository,
    sessionId: String,
    sourceAgent: String,
    displayName: String,
    mimeType: String,
    extension: String,
    maxBytes: Long,
    clock: () -> Long,
    write: (OutputStream) -> Unit,
    validate: (File) -> Boolean,
): ArtifactGenerationResult {
    if (maxBytes <= 0L || maxBytes > ArtifactDigest.MAX_ARTIFACT_BYTES) {
        return ArtifactGenerationResult.Rejected(ArtifactGenerationCode.INVALID_INPUT, "invalid artifact size limit")
    }
    val artifactId = "artifact-${UUID.randomUUID()}"
    var staged: File? = null
    var destination: File? = null
    var completed = false
    try {
        staged = workspace.createTempFile(artifactId)
        java.io.FileOutputStream(staged).use { output -> write(output) }
        if (staged.length() > maxBytes) {
            return ArtifactGenerationResult.Rejected(ArtifactGenerationCode.SIZE_LIMIT, "artifact exceeds the size limit")
        }
        destination = workspace.allocateVersionFile(artifactId, 1, extension)
        workspace.moveIntoWorkspace(staged, destination)
        if (!validate(destination)) {
            return ArtifactGenerationResult.Rejected(ArtifactGenerationCode.VALIDATION_FAILED, "artifact validation failed")
        }
        val artifact = repository.registerCompleted(
            sessionId = sessionId,
            mimeType = mimeType,
            displayName = displayName,
            privateFile = destination,
            sourceAgent = sourceAgent,
            artifactId = artifactId,
            createdAt = clock(),
        )
        completed = true
        return ArtifactGenerationResult.Completed(artifact)
    } catch (_: IOException) {
        return ArtifactGenerationResult.Rejected(ArtifactGenerationCode.STORAGE_FAILED, "unable to write artifact")
    } catch (_: SecurityException) {
        return ArtifactGenerationResult.Rejected(ArtifactGenerationCode.STORAGE_FAILED, "artifact storage was rejected")
    } catch (_: IllegalArgumentException) {
        return ArtifactGenerationResult.Rejected(ArtifactGenerationCode.INVALID_INPUT, "invalid artifact input")
    } finally {
        staged?.delete()
        if (!completed) destination?.delete()
    }
}
