package com.lchuang.xiaozhimobile.artifacts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

enum class ArtifactVersionOperationCode {
    ARTIFACT_NOT_FOUND,
    INVALID_VERSION,
    INVALID_SESSION,
    STALE_BASE,
    SIZE_LIMIT,
    VALIDATION_FAILED,
    STORAGE_FAILED,
}

sealed interface ArtifactVersionOperationResult {
    data class Started(val session: ArtifactEditSession) : ArtifactVersionOperationResult

    data class Completed(val artifact: Artifact) : ArtifactVersionOperationResult

    data class Rejected(
        val code: ArtifactVersionOperationCode,
        val detail: String = "",
    ) : ArtifactVersionOperationResult
}

/** A private, single-use edit transaction rooted at one immutable version. */
class ArtifactEditSession internal constructor(
    val artifactId: String,
    val baseVersion: Int,
    val nextVersion: Int,
    val tempFile: File,
    internal val extension: String,
    internal val mimeType: String,
    internal val displayName: String,
    internal val sourceAgent: String,
    internal val sessionId: String,
) {
    internal var state: State = State.OPEN

    internal enum class State {
        OPEN,
        COMPLETED,
        CANCELLED,
    }
}

/** Creates immutable artifact versions without modifying any completed version. */
class ArtifactVersionManager(
    private val workspace: ArtifactWorkspace,
    private val repository: ArtifactRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val activeSessions = mutableSetOf<ArtifactEditSession>()

    fun beginEdit(
        artifactId: String,
        baseVersion: Int? = null,
    ): ArtifactVersionOperationResult = synchronized(lock) {
        val current = repository.find(artifactId)
            ?: return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.ARTIFACT_NOT_FOUND,
                "artifact was not found",
            )
        if (current.status != ArtifactStatus.COMPLETED) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.INVALID_VERSION,
                "only a completed artifact can be edited",
            )
        }
        if (current.version == Int.MAX_VALUE) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.INVALID_VERSION,
                "artifact version limit was reached",
            )
        }
        val requestedVersion = baseVersion ?: current.version
        if (requestedVersion != current.version) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.STALE_BASE,
                "edit must start from the current version",
            )
        }
        val currentVersion = repository.versions(artifactId)
            .singleOrNull { it.version == current.version }
            ?: return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.INVALID_VERSION,
                "current artifact version metadata is missing",
            )
        val currentFile = File(current.privatePath).canonicalFile
        if (!workspace.isPrivate(currentFile) || !currentFile.isFile) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "current artifact content is unavailable",
            )
        }
        if (currentFile.length() != currentVersion.size ||
            !runCatching { ArtifactDigest.sha256(currentFile) == currentVersion.sha256 }.getOrDefault(false)
        ) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.VALIDATION_FAILED,
                "current artifact content does not match its metadata",
            )
        }

        val temp = try {
            workspace.createTempFile(artifactId, ".edit")
        } catch (_: IOException) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "unable to create an edit staging file",
            )
        } catch (_: SecurityException) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "edit staging storage was rejected",
            )
        } catch (_: IllegalArgumentException) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "invalid edit staging path",
            )
        }
        val session = ArtifactEditSession(
            artifactId = artifactId,
            baseVersion = current.version,
            nextVersion = current.version + 1,
            tempFile = temp,
            extension = currentFile.extension.ifBlank { "bin" },
            mimeType = current.mimeType,
            displayName = current.displayName,
            sourceAgent = current.sourceAgent,
            sessionId = current.sessionId,
        )
        activeSessions += session
        ArtifactVersionOperationResult.Started(session)
    }

    /** Writes, validates, and atomically registers one new version. */
    fun commit(
        session: ArtifactEditSession,
        write: (OutputStream) -> Unit,
        validate: (File) -> Boolean,
        extension: String = session.extension,
        mimeType: String = session.mimeType,
        displayName: String = session.displayName,
        sourceAgent: String = session.sourceAgent,
        sessionId: String = session.sessionId,
    ): ArtifactVersionOperationResult = synchronized(lock) {
        if (session !in activeSessions || session.state != ArtifactEditSession.State.OPEN) {
            return@synchronized ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.INVALID_SESSION,
                "edit session is no longer active",
            )
        }

        val current = repository.find(session.artifactId)
        if (current == null || current.status != ArtifactStatus.COMPLETED ||
            current.version != session.baseVersion
        ) {
            return@synchronized finishRejected(
                session,
                ArtifactVersionOperationCode.STALE_BASE,
                "artifact changed while the edit was open",
            )
        }

        if (!workspace.isPrivate(session.tempFile)) {
            return@synchronized finishRejected(
                session,
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "edit staging file is outside private storage",
            )
        }

        var destination: File? = null
        var completed = false
        try {
            FileOutputStream(session.tempFile).use { fileOutput ->
                SizeLimitedOutputStream(fileOutput, ArtifactDigest.MAX_ARTIFACT_BYTES).use { output ->
                    write(output)
                    output.flush()
                }
            }
            destination = workspace.allocateVersionFile(session.artifactId, session.nextVersion, extension)
            workspace.moveIntoWorkspace(session.tempFile, destination)
            val valid = runCatching { validate(destination) }.getOrDefault(false)
            if (!valid) {
                return@synchronized finishRejected(
                    session,
                    ArtifactVersionOperationCode.VALIDATION_FAILED,
                    "edited artifact validation failed",
                    destination,
                )
            }
            val artifact = repository.registerCompleted(
                sessionId = sessionId,
                mimeType = mimeType,
                displayName = displayName,
                privateFile = destination,
                sourceAgent = sourceAgent,
                artifactId = session.artifactId,
                createdAt = clock(),
                version = session.nextVersion,
                parentVersion = session.baseVersion,
            )
            completed = true
            session.state = ArtifactEditSession.State.COMPLETED
            activeSessions.remove(session)
            ArtifactVersionOperationResult.Completed(artifact)
        } catch (_: SizeLimitExceededException) {
            finishRejected(
                session,
                ArtifactVersionOperationCode.SIZE_LIMIT,
                "edited artifact exceeds the size limit",
                destination,
            )
        } catch (_: IOException) {
            finishRejected(
                session,
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "unable to write edited artifact",
                destination,
            )
        } catch (_: SecurityException) {
            finishRejected(
                session,
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "edited artifact storage was rejected",
                destination,
            )
        } catch (_: IllegalArgumentException) {
            finishRejected(
                session,
                ArtifactVersionOperationCode.INVALID_VERSION,
                "edited artifact metadata is invalid",
                destination,
            )
        } catch (_: RuntimeException) {
            finishRejected(
                session,
                ArtifactVersionOperationCode.STORAGE_FAILED,
                "edited artifact operation failed",
                destination,
            )
        } finally {
            session.tempFile.delete()
            if (!completed) destination?.delete()
        }
    }

    /** Cancels one edit and removes only its temporary staging file. */
    fun cancel(session: ArtifactEditSession): Boolean = synchronized(lock) {
        if (session !in activeSessions || session.state != ArtifactEditSession.State.OPEN) return@synchronized false
        activeSessions.remove(session)
        session.state = ArtifactEditSession.State.CANCELLED
        !session.tempFile.exists() || session.tempFile.delete()
    }

    /** Restores a prior immutable version by copying it into a new current version. */
    fun restore(
        artifactId: String,
        version: Int,
        sessionId: String? = null,
        sourceAgent: String? = null,
    ): ArtifactVersionOperationResult {
        val current = repository.find(artifactId)
            ?: return ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.ARTIFACT_NOT_FOUND,
                "artifact was not found",
            )
        if (version < 1 || version >= current.version) {
            return ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.INVALID_VERSION,
                "restore must target a prior version",
            )
        }
        val prior = repository.versions(artifactId).singleOrNull { it.version == version }
            ?: return ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.INVALID_VERSION,
                "requested artifact version was not found",
            )
        val source = File(prior.privatePath).canonicalFile
        if (!workspace.isPrivate(source) || !source.isFile || source.length() != prior.size) {
            return ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.VALIDATION_FAILED,
                "prior artifact content is unavailable",
            )
        }
        if (!runCatching { ArtifactDigest.sha256(source) == prior.sha256 }.getOrDefault(false)) {
            return ArtifactVersionOperationResult.Rejected(
                ArtifactVersionOperationCode.VALIDATION_FAILED,
                "prior artifact content does not match its metadata",
            )
        }

        val started = beginEdit(artifactId)
        if (started !is ArtifactVersionOperationResult.Started) return started
        return commit(
            session = started.session,
            write = { output -> copyBounded(source, output) },
            validate = { file ->
                file.length() == prior.size &&
                    runCatching { ArtifactDigest.sha256(file) == prior.sha256 }.getOrDefault(false)
            },
            extension = source.extension.ifBlank { "bin" },
            mimeType = prior.mimeType,
            displayName = prior.displayName,
            sourceAgent = sourceAgent ?: current.sourceAgent,
            sessionId = sessionId ?: current.sessionId,
        )
    }

    private fun finishRejected(
        session: ArtifactEditSession,
        code: ArtifactVersionOperationCode,
        detail: String,
        destination: File? = null,
    ): ArtifactVersionOperationResult.Rejected {
        activeSessions.remove(session)
        session.state = ArtifactEditSession.State.CANCELLED
        destination?.delete()
        session.tempFile.delete()
        return ArtifactVersionOperationResult.Rejected(code, detail)
    }

    private fun copyBounded(source: File, output: OutputStream) {
        FileInputStream(source).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > ArtifactDigest.MAX_ARTIFACT_BYTES) throw SizeLimitExceededException()
                output.write(buffer, 0, count)
            }
        }
    }

    private class SizeLimitExceededException : IOException()

    private class SizeLimitedOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long,
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            ensure(1L)
            delegate.write(value)
            written += 1L
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            ensure(length.toLong())
            delegate.write(bytes, offset, length)
            written += length.toLong()
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun ensure(incoming: Long) {
            if (incoming > maxBytes - written) throw SizeLimitExceededException()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
    }
}
