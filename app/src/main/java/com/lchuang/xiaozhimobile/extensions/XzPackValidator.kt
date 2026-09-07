package com.lchuang.xiaozhimobile.extensions

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

enum class XzPackValidationCode {
    EMPTY_ARCHIVE,
    ZIP_INVALID,
    PACKAGE_TOO_LARGE,
    ENTRY_LIMIT_EXCEEDED,
    ENTRY_TOO_LARGE,
    PATH_TRAVERSAL,
    DISALLOWED_PATH,
    DISALLOWED_FILE_TYPE,
    DUPLICATE_ENTRY,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID,
    INVALID_ID,
    INVALID_VERSION,
    UNKNOWN_PERMISSION,
    INVALID_FILE_DIGEST,
    DUPLICATE_DECLARED_FILE,
    UNDECLARED_FILE,
    DECLARED_FILE_MISSING,
    SHA_MISMATCH,
}

sealed interface XzPackValidationResult {
    data class Valid(val packageValue: ExtensionPackage) : XzPackValidationResult

    data class Invalid(
        val code: XzPackValidationCode,
        val detail: String = "",
    ) : XzPackValidationResult
}

class XzPackValidator(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxCompressedBytes: Long = DEFAULT_MAX_COMPRESSED_BYTES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxCompressedBytes > 0) { "maxCompressedBytes must be positive" }
    }

    fun validate(bytes: ByteArray): XzPackValidationResult =
        validate(bytes.inputStream())

    fun validate(input: InputStream): XzPackValidationResult {
        val countedInput = CountingInputStream(input, maxCompressedBytes)
        val zip = ZipInputStream(countedInput)
        val seenPaths = linkedSetOf<String>()
        val entries = mutableListOf<ExtensionPackageEntry>()
        var manifestJson: String? = null
        var totalBytes = 0L
        var entryCount = 0

        return try {
            while (true) {
                val zipEntry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > maxEntries) {
                    return XzPackValidationResult.Invalid(XzPackValidationCode.ENTRY_LIMIT_EXCEEDED)
                }

                val path = try {
                    XzPackPath.normalize(zipEntry.name)
                } catch (error: XzPackPathException) {
                    return XzPackValidationResult.Invalid(error.code, error.message ?: "invalid path")
                }
                if (!seenPaths.add(path)) {
                    return XzPackValidationResult.Invalid(XzPackValidationCode.DUPLICATE_ENTRY)
                }

                val isDirectory = zipEntry.isDirectory || zipEntry.name.endsWith("/")
                if (!XzPackPath.isAllowed(path, isDirectory)) {
                    return XzPackValidationResult.Invalid(XzPackValidationCode.DISALLOWED_PATH)
                }
                if (!isDirectory && !XzPackPath.isAllowedFileType(path)) {
                    return XzPackValidationResult.Invalid(XzPackValidationCode.DISALLOWED_FILE_TYPE)
                }
                if (isDirectory) {
                    zip.closeEntry()
                    continue
                }

                if (zipEntry.size > maxEntryBytes) {
                    return XzPackValidationResult.Invalid(XzPackValidationCode.ENTRY_TOO_LARGE)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val firstBytes = ByteArray(2)
                var firstCount = 0
                var entryBytes = 0L
                val manifestOutput = if (path == ExtensionManifest.FILE_NAME) {
                    ByteArrayOutputStream(MAX_MANIFEST_BYTES)
                } else {
                    null
                }
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    entryBytes += read
                    totalBytes += read
                    if (entryBytes > maxEntryBytes) {
                        return XzPackValidationResult.Invalid(XzPackValidationCode.ENTRY_TOO_LARGE)
                    }
                    if (totalBytes > maxTotalBytes) {
                        return XzPackValidationResult.Invalid(XzPackValidationCode.PACKAGE_TOO_LARGE)
                    }
                    digest.update(buffer, 0, read)
                    if (firstCount < firstBytes.size) {
                        val copyCount = minOf(firstBytes.size - firstCount, read)
                        buffer.copyInto(firstBytes, firstCount, 0, copyCount)
                        firstCount += copyCount
                    }
                    if (manifestOutput != null) {
                        if (manifestOutput.size() + read > MAX_MANIFEST_BYTES) {
                            return XzPackValidationResult.Invalid(XzPackValidationCode.MANIFEST_TOO_LARGE)
                        }
                        manifestOutput.write(buffer, 0, read)
                    }
                }
                if (firstCount == 2 && firstBytes[0] == '#'.code.toByte() && firstBytes[1] == '!'.code.toByte()) {
                    return XzPackValidationResult.Invalid(XzPackValidationCode.DISALLOWED_FILE_TYPE)
                }
                val sha256 = digest.digest().toHex()
                entries += ExtensionPackageEntry(path = path, size = entryBytes, sha256 = sha256)
                if (manifestOutput != null) {
                    if (manifestJson != null) {
                        return XzPackValidationResult.Invalid(XzPackValidationCode.DUPLICATE_ENTRY)
                    }
                    manifestJson = decodeUtf8(manifestOutput.toByteArray())
                }
                zip.closeEntry()
            }

            if (entryCount == 0) {
                return XzPackValidationResult.Invalid(XzPackValidationCode.EMPTY_ARCHIVE)
            }
            val manifestText = manifestJson
                ?: return XzPackValidationResult.Invalid(XzPackValidationCode.MANIFEST_MISSING)
            val manifest = try {
                ExtensionManifest.parse(manifestText)
            } catch (error: ExtensionManifestException) {
                return XzPackValidationResult.Invalid(error.code, error.message ?: "invalid manifest")
            }
            val entryByPath = entries.associateBy { it.path }
            for ((declaredPath, expectedDigest) in manifest.files) {
                val actual = entryByPath[declaredPath]
                    ?: return XzPackValidationResult.Invalid(
                        XzPackValidationCode.DECLARED_FILE_MISSING,
                        declaredPath,
                    )
                if (!actual.sha256.equals(expectedDigest, ignoreCase = true)) {
                    return XzPackValidationResult.Invalid(
                        XzPackValidationCode.SHA_MISMATCH,
                        declaredPath,
                    )
                }
            }
            val payloadEntries = entries.filter { it.path != ExtensionManifest.FILE_NAME }
            if (payloadEntries.any { it.path !in manifest.files }) {
                return XzPackValidationResult.Invalid(XzPackValidationCode.UNDECLARED_FILE)
            }

            XzPackValidationResult.Valid(ExtensionPackage(manifest, entries.toList()))
        } catch (error: XzPackLimitException) {
            XzPackValidationResult.Invalid(error.code, error.message ?: "package exceeds limit")
        } catch (_: ZipException) {
            XzPackValidationResult.Invalid(XzPackValidationCode.ZIP_INVALID)
        } catch (_: IOException) {
            XzPackValidationResult.Invalid(XzPackValidationCode.ZIP_INVALID)
        } catch (_: CharacterCodingException) {
            XzPackValidationResult.Invalid(XzPackValidationCode.MANIFEST_INVALID, "manifest is not UTF-8")
        } finally {
            runCatching { zip.close() }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private class CountingInputStream(
        input: InputStream,
        private val limit: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) increment(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) increment(read.toLong())
            return read
        }

        private fun increment(delta: Long) {
            if (delta < 0 || count > limit - delta) {
                throw XzPackLimitException(XzPackValidationCode.PACKAGE_TOO_LARGE)
            }
            count += delta
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val DEFAULT_MAX_ENTRIES = 256
        const val DEFAULT_MAX_ENTRY_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_TOTAL_BYTES = 32L * 1024L * 1024L
        const val DEFAULT_MAX_COMPRESSED_BYTES = 32L * 1024L * 1024L
    }
}

internal object XzPackPath {
    private val ALLOWED_ROOTS = setOf("skills", "agents", "tools", "assets")
    private val ALLOWED_EXTENSIONS = setOf(
        ".csv", ".gif", ".jpeg", ".jpg", ".json", ".md", ".png", ".svg", ".txt",
        ".webp", ".yaml", ".yml",
    )

    fun normalize(raw: String): String {
        if (raw.isBlank() || raw.contains('\\') || raw.contains('\u0000')) {
            throw XzPackPathException(XzPackValidationCode.PATH_TRAVERSAL, "unsafe archive path")
        }
        val path = if (raw.endsWith('/')) {
            if (raw.length == 1 || raw.dropLast(1).endsWith('/')) {
                throw XzPackPathException(XzPackValidationCode.PATH_TRAVERSAL, "unsafe directory path")
            }
            raw.dropLast(1)
        } else {
            raw
        }
        if (path.startsWith('/') || path.startsWith("//") || Regex("^[A-Za-z]:").containsMatchIn(path)) {
            throw XzPackPathException(XzPackValidationCode.PATH_TRAVERSAL, "absolute archive path")
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw XzPackPathException(XzPackValidationCode.PATH_TRAVERSAL, "traversal archive path")
        }
        return segments.joinToString("/")
    }

    fun isAllowed(path: String, directory: Boolean): Boolean {
        if (path == ExtensionManifest.FILE_NAME || (!directory && path == "README.md")) return true
        if (directory && path in ALLOWED_ROOTS) return true
        return ALLOWED_ROOTS.any { root -> path.startsWith("$root/") }
    }

    fun isAllowedFileType(path: String): Boolean {
        val fileName = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            fileName == "readme.md" || fileName == "skill.md" -> true
            fileName.substringAfterLast('.', missingDelimiterValue = "")
                .isEmpty() -> false
            else -> ALLOWED_EXTENSIONS.any { extension -> fileName.endsWith(extension) }
        }
    }
}

internal class XzPackPathException(
    val code: XzPackValidationCode,
    message: String,
) : IllegalArgumentException(message)

private class XzPackLimitException(
    val code: XzPackValidationCode,
    message: String = "package size limit exceeded",
) : IOException(message)
