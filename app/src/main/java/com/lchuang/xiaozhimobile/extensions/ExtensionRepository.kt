package com.lchuang.xiaozhimobile.extensions

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

data class StoredExtension(
    val id: String,
    val version: String,
    val archiveFile: File,
    val enabled: Boolean,
    val confirmedPermissions: Set<ExtensionPermission>,
)

class ExtensionStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** App-private persistence for validated declarative extension archives. */
class ExtensionRepository(val rootDirectory: File) {
    init {
        require(rootDirectory.isAbsolute) { "extension storage must use an absolute app-private path" }
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw ExtensionStorageException("unable to create extension storage")
        }
        require(rootDirectory.isDirectory) { "extension storage must be a directory" }
    }

    companion object {
        private const val ARCHIVE_NAME = "package.xzpack"
        private const val STATE_NAME = "state.properties"
        private const val MAX_COMPRESSED_PACKAGE_BYTES = 32L * 1024L * 1024L
        private const val BUFFER_SIZE = 8 * 1024
        private const val STATE_ID = "id"
        private const val STATE_VERSION = "version"
        private const val STATE_ENABLED = "enabled"
        private const val STATE_PERMISSIONS = "permissions"

        fun inAppPrivateStorage(context: Context): ExtensionRepository =
            ExtensionRepository(File(context.filesDir, "extensions"))
    }

    /** Spools an import into this app-private directory with a compressed-size bound. */
    fun stage(input: InputStream, maxBytes: Long = MAX_COMPRESSED_PACKAGE_BYTES): File {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val staged = try {
            File.createTempFile("import-", ".xzpack.tmp", rootDirectory)
        } catch (error: IOException) {
            throw ExtensionStorageException("unable to create private import", error)
        }
        try {
            FileOutputStream(staged).use { output ->
                copyBounded(input, output, maxBytes)
            }
            return staged
        } catch (error: IOException) {
            staged.delete()
            if (error is ExtensionStorageException) throw error
            throw ExtensionStorageException("unable to stage private import", error)
        } catch (error: RuntimeException) {
            staged.delete()
            throw ExtensionStorageException("unable to stage private import", error)
        }
    }

    fun install(
        stagedArchive: File,
        extensionPackage: ExtensionPackage,
        enabled: Boolean,
        confirmedPermissions: Set<ExtensionPermission>,
    ): StoredExtension {
        require(stagedArchive.isFile) { "staged archive must be a file" }
        val manifest = extensionPackage.manifest
        val extensionDirectory = childDirectory(manifest.id)
        if (!extensionDirectory.exists() && !extensionDirectory.mkdirs()) {
            throw ExtensionStorageException("unable to create extension directory")
        }
        val archiveFile = File(extensionDirectory, ARCHIVE_NAME)
        val archiveTemp = try {
            File.createTempFile("package-", ".tmp", extensionDirectory)
        } catch (error: IOException) {
            throw ExtensionStorageException("unable to create package temp file", error)
        }
        val stateTemp = try {
            File.createTempFile("state-", ".tmp", extensionDirectory)
        } catch (error: IOException) {
            archiveTemp.delete()
            throw ExtensionStorageException("unable to create state temp file", error)
        }
        try {
            FileInputStream(stagedArchive).use { input ->
                FileOutputStream(archiveTemp).use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
            writeState(
                stateTemp,
                id = manifest.id,
                version = manifest.version,
                enabled = enabled,
                permissions = confirmedPermissions,
            )
            replaceAtomically(archiveTemp, archiveFile)
            replaceAtomically(stateTemp, File(extensionDirectory, STATE_NAME))
            return StoredExtension(
                id = manifest.id,
                version = manifest.version,
                archiveFile = archiveFile,
                enabled = enabled,
                confirmedPermissions = confirmedPermissions,
            )
        } catch (error: IOException) {
            archiveTemp.delete()
            stateTemp.delete()
            if (error is ExtensionStorageException) throw error
            throw ExtensionStorageException("unable to install private extension", error)
        } catch (error: RuntimeException) {
            archiveTemp.delete()
            stateTemp.delete()
            throw ExtensionStorageException("unable to install private extension", error)
        }
    }

    fun list(): List<StoredExtension> = rootDirectory.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && isSafeExtensionId(it.name) }
        ?.mapNotNull { readStored(it) }
        ?.sortedBy { it.id }
        ?.toList()
        ?: emptyList()

    fun updateState(
        id: String,
        version: String,
        enabled: Boolean,
        confirmedPermissions: Set<ExtensionPermission>,
    ): StoredExtension? {
        val extensionDirectory = childDirectory(id)
        val archive = File(extensionDirectory, ARCHIVE_NAME)
        if (!archive.isFile) return null
        val stateFile = File(extensionDirectory, STATE_NAME)
        val stateTemp = try {
            File.createTempFile("state-", ".tmp", extensionDirectory)
        } catch (error: IOException) {
            throw ExtensionStorageException("unable to create state temp file", error)
        }
        try {
            writeState(stateTemp, id, version, enabled, confirmedPermissions)
            replaceAtomically(stateTemp, stateFile)
            return StoredExtension(id, version, archive, enabled, confirmedPermissions)
        } catch (error: IOException) {
            stateTemp.delete()
            if (error is ExtensionStorageException) throw error
            throw ExtensionStorageException("unable to update extension state", error)
        }
    }

    fun remove(id: String): Boolean {
        if (!isSafeExtensionId(id)) return false
        val extensionDirectory = childDirectory(id)
        if (!extensionDirectory.exists()) return false
        deleteTree(extensionDirectory)
        return !extensionDirectory.exists()
    }

    private fun readStored(directory: File): StoredExtension? {
        val archive = File(directory, ARCHIVE_NAME)
        val stateFile = File(directory, STATE_NAME)
        if (!archive.isFile || !stateFile.isFile) return null
        return try {
            val properties = Properties()
            FileInputStream(stateFile).use { properties.load(it) }
            val id = properties.getProperty(STATE_ID) ?: return null
            val version = properties.getProperty(STATE_VERSION) ?: return null
            if (id != directory.name || version.isBlank()) return null
            val enabled = properties.getProperty(STATE_ENABLED)?.toBooleanStrictOrNull() ?: return null
            val permissions = parsePermissions(properties.getProperty(STATE_PERMISSIONS).orEmpty())
                ?: return null
            StoredExtension(id, version, archive, enabled, permissions)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parsePermissions(value: String): Set<ExtensionPermission>? {
        if (value.isBlank()) return emptySet()
        val result = linkedSetOf<ExtensionPermission>()
        for (wireName in value.split(',')) {
            val permission = ExtensionPermission.fromWire(wireName) ?: return null
            if (!result.add(permission)) return null
        }
        return result
    }

    private fun writeState(
        file: File,
        id: String,
        version: String,
        enabled: Boolean,
        permissions: Set<ExtensionPermission>,
    ) {
        FileOutputStream(file).use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                val properties = Properties()
                properties.setProperty(STATE_ID, id)
                properties.setProperty(STATE_VERSION, version)
                properties.setProperty(STATE_ENABLED, enabled.toString())
                properties.setProperty(
                    STATE_PERMISSIONS,
                    permissions.map { it.wireName }.sorted().joinToString(","),
                )
                properties.store(writer, "XiaoZhi extension state")
            }
        }
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream, maxBytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val bulkRead = input.read(buffer)
            val read = when {
                bulkRead < 0 -> break
                bulkRead > 0 -> bulkRead
                else -> {
                    val single = input.read()
                    if (single < 0) break
                    buffer[0] = single.toByte()
                    1
                }
            }
            if (copied > maxBytes - read) {
                throw ExtensionStorageException("private import exceeds size limit")
            }
            output.write(buffer, 0, read)
            copied += read
        }
    }

    private fun childDirectory(id: String): File {
        require(isSafeExtensionId(id)) { "invalid extension id" }
        val directory = File(rootDirectory, id)
        val rootCanonical = rootDirectory.canonicalFile
        val directoryCanonical = directory.canonicalFile
        require(directoryCanonical.parentFile == rootCanonical) { "extension path escaped private storage" }
        return directory
    }

    private fun isSafeExtensionId(id: String): Boolean =
        Regex("[a-z][a-z0-9._-]{2,63}").matches(id)

    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (error: IOException) {
                throw ExtensionStorageException("unable to commit private extension file", error)
            }
        } catch (error: IOException) {
            throw ExtensionStorageException("unable to commit private extension file", error)
        }
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteTree(child) }
        }
        if (!file.delete() && file.exists()) {
            throw ExtensionStorageException("unable to remove private extension")
        }
    }
}
