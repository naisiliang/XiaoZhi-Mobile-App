package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ZipArtifactEntry(val path: String, val bytes: ByteArray)

data class ZipValidationResult(
    val isValid: Boolean,
    val entryCount: Int = 0,
    val totalBytes: Long = 0L,
    val reason: String = "",
)

object ZipArtifactPathPolicy {
    const val MAX_PATH_LENGTH = 512

    fun normalize(path: String): String {
        require(path.isNotBlank() && path.length <= MAX_PATH_LENGTH) { "invalid ZIP entry path" }
        require(path == path.trim()) { "ZIP entry path must not have surrounding whitespace" }
        require('\u0000' !in path && '\\' !in path) { "ZIP entry path uses an unsafe separator" }
        require(!path.startsWith('/') && ':' !in path) { "ZIP entry path must be relative" }
        require(path.none { it.code < 0x20 || it == '\u007f' }) { "ZIP entry path contains control characters" }
        val segments = path.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "ZIP entry path contains traversal segments"
        }
        val canonicalPath = segments.joinToString("/")
        return canonicalPath
    }
}

object ZipArtifactValidator {
    const val MAX_ENTRIES = 512
    const val MAX_ENTRY_BYTES = 16L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val BUFFER_SIZE = 8 * 1024

    fun validate(file: File): ZipValidationResult {
        if (!file.isFile) return ZipValidationResult(false, reason = "ZIP artifact is not a file")
        if (file.length() > MAX_ARCHIVE_BYTES) {
            return ZipValidationResult(false, reason = "ZIP archive exceeds the size limit")
        }
        return try {
            ZipFile(file).use { zip ->
                val seen = HashSet<String>()
                val entries = zip.entries()
                var entryCount = 0
                var totalBytes = 0L
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        return ZipValidationResult(false, entryCount, totalBytes, "too many ZIP entries")
                    }
                    if (entry.isDirectory) {
                        return ZipValidationResult(false, entryCount, totalBytes, "directory ZIP entries are not allowed")
                    }
                    val normalizedPath = runCatching {
                        ZipArtifactPathPolicy.normalize(entry.name)
                    }.getOrElse {
                        return ZipValidationResult(false, entryCount, totalBytes, "unsafe ZIP entry path")
                    }
                    if (!seen.add(normalizedPath)) {
                        return ZipValidationResult(false, entryCount, totalBytes, "duplicate ZIP entry path")
                    }
                    if (entry.size > MAX_ENTRY_BYTES) {
                        return ZipValidationResult(false, entryCount, totalBytes, "ZIP entry exceeds the size limit")
                    }

                    var entryBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            entryBytes += count.toLong()
                            totalBytes += count.toLong()
                            if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                return ZipValidationResult(false, entryCount, totalBytes, "ZIP content exceeds the size limit")
                            }
                        }
                    }
                    if (entry.size >= 0L && entry.size != entryBytes) {
                        return ZipValidationResult(false, entryCount, totalBytes, "ZIP entry size is inconsistent")
                    }
                }
                ZipValidationResult(true, entryCount, totalBytes)
            }
        } catch (error: Exception) {
            ZipValidationResult(false, reason = "invalid ZIP archive: ${error::class.simpleName}")
        }
    }
}

class ZipArtifactGenerator(
    private val workspace: ArtifactWorkspace,
    private val repository: ArtifactRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        repository: ArtifactRepository,
        workspace: ArtifactWorkspace,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(workspace, repository, clock)

    fun generate(
        sessionId: String,
        sourceAgent: String,
        displayName: String,
        entries: List<ZipArtifactEntry>,
    ): ArtifactGenerationResult {
        val normalizedEntries = try {
            require(entries.size <= ZipArtifactValidator.MAX_ENTRIES) { "too many ZIP entries" }
            val seen = HashSet<String>()
            var totalBytes = 0L
            entries.map { entry ->
                val path = ZipArtifactPathPolicy.normalize(entry.path)
                require(seen.add(path)) { "duplicate ZIP entry path" }
                require(entry.bytes.size.toLong() <= ZipArtifactValidator.MAX_ENTRY_BYTES) {
                    "ZIP entry exceeds the size limit"
                }
                totalBytes += entry.bytes.size.toLong()
                require(totalBytes <= ZipArtifactValidator.MAX_TOTAL_BYTES) {
                    "ZIP content exceeds the size limit"
                }
                ZipArtifactEntry(path, entry.bytes)
            }
        } catch (error: IllegalArgumentException) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.INVALID_INPUT,
                error.message.orEmpty(),
            )
        }

        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = "application/zip",
            extension = "zip",
            maxBytes = ZipArtifactValidator.MAX_ARCHIVE_BYTES,
            clock = clock,
            write = { output ->
                ZipOutputStream(output).use { zip ->
                    normalizedEntries.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.path))
                        zip.write(entry.bytes)
                        zip.closeEntry()
                    }
                }
            },
            validate = { file -> ZipArtifactValidator.validate(file).isValid },
        )
    }
}
