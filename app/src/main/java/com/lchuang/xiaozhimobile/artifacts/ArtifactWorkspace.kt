package com.lchuang.xiaozhimobile.artifacts

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** App-private artifact root with traversal-safe, no-overwrite file allocation. */
class ArtifactWorkspace(val rootDirectory: File) {
    private val canonicalRoot = rootDirectory.canonicalFile

    init {
        require(rootDirectory.isAbsolute) { "artifact workspace must use an absolute path" }
        if (!rootDirectory.exists()) {
            require(rootDirectory.mkdirs()) { "unable to create artifact workspace" }
        }
        require(rootDirectory.isDirectory) { "artifact workspace must be a directory" }
        require(canonicalRoot.isDirectory) { "artifact workspace must resolve to a directory" }
    }

    fun artifactDirectory(artifactId: String): File {
        require(ArtifactIds.isSafe(artifactId)) { "invalid artifact id" }
        val directory = File(canonicalRoot, artifactId).canonicalFile
        require(isPrivate(directory)) { "artifact directory escaped workspace" }
        if (!directory.exists()) {
            require(directory.mkdirs()) { "unable to create artifact directory" }
        }
        require(directory.isDirectory) { "artifact path is not a directory" }
        return directory
    }

    /** Allocates a unique version filename and never replaces an existing file. */
    fun allocateVersionFile(artifactId: String, version: Int, extension: String): File {
        require(version >= 1) { "artifact version must be positive" }
        val safeExtension = normalizeExtension(extension)
        val directory = artifactDirectory(artifactId)
        val candidate = File(directory, "content-v$version.$safeExtension").canonicalFile
        require(isPrivate(candidate)) { "artifact file escaped workspace" }
        require(!candidate.exists()) { "artifact version file already exists" }
        return candidate
    }

    fun createTempFile(artifactId: String, suffix: String = ".part"): File {
        require(suffix.isNotBlank() && suffix == suffix.trim() && '/' !in suffix && '\\' !in suffix) {
            "invalid artifact temp suffix"
        }
        return File.createTempFile("artifact-${UUID.randomUUID()}-", suffix, artifactDirectory(artifactId))
    }

    /** Moves a staged file into this workspace without replacing a destination. */
    fun moveIntoWorkspace(staged: File, destination: File): File {
        val source = staged.canonicalFile
        val target = destination.canonicalFile
        require(source.isFile && isPrivate(source)) { "staged artifact is outside workspace" }
        require(isPrivate(target)) { "artifact destination is outside workspace" }
        require(!target.exists()) { "artifact destination already exists" }
        require(target.parentFile?.isDirectory == true) { "artifact destination directory is missing" }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        return target
    }

    fun isPrivate(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val root = canonicalRoot.path
        return path.startsWith(root + File.separator)
    }

    fun deleteArtifactDirectory(artifactId: String): Boolean {
        val directory = artifactDirectory(artifactId)
        if (!directory.exists()) return true
        deleteTree(directory)
        return !directory.exists()
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        require(isPrivate(file)) { "refusing to delete outside workspace" }
        require(!file.delete() || !file.exists()) { "unable to delete artifact file" }
    }

    private fun normalizeExtension(extension: String): String {
        val normalized = extension.trim().removePrefix(".").lowercase()
        require(normalized.isNotBlank() && normalized.length <= 16) { "invalid artifact extension" }
        require(normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "invalid artifact extension"
        }
        return normalized
    }

    companion object {
        fun inAppPrivateStorage(context: Context): ArtifactWorkspace =
            ArtifactWorkspace(File(context.applicationContext.filesDir, "artifacts"))
    }
}
