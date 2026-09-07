package com.lchuang.xiaozhimobile.artifacts

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

enum class ArtifactExportCode {
    PRIVATE_ONLY,
    SAVED_TO_SAF,
    SAVED_TO_MEDIA_STORE,
    OPENED,
    SHARED,
    INVALID_ARTIFACT,
    INVALID_DESTINATION,
    UNSUPPORTED_MEDIA_TYPE,
    PERMISSION_REQUIRED,
    NO_HANDLER,
    EXPORT_FAILED,
}

data class ArtifactExportResult(
    val success: Boolean,
    val code: ArtifactExportCode,
    val exportedUri: String? = null,
    val detail: String = "",
)

/** Platform boundary for export side effects; pure coordinator tests inject this interface. */
interface ArtifactExportOperations {
    fun privateContentUri(artifact: Artifact): String?

    fun saveToSaf(artifact: Artifact, destinationUri: String): ArtifactExportResult

    fun saveImageToMediaStore(artifact: Artifact): ArtifactExportResult

    fun open(contentUri: String, mimeType: String): ArtifactExportResult

    fun share(contentUri: String, mimeType: String, displayName: String): ArtifactExportResult
}

/**
 * Keeps artifacts private unless the user explicitly chooses an export action.
 * It validates the private source before every operation and never accepts a
 * file:// destination for public writes or sharing.
 */
class ArtifactExportCoordinator(
    private val workspace: ArtifactWorkspace,
    private val operations: ArtifactExportOperations,
) {
    constructor(context: Context) : this(
        workspace = ArtifactWorkspace.inAppPrivateStorage(context),
        operations = AndroidArtifactExportOperations(context),
    )

    constructor(context: Context, workspace: ArtifactWorkspace) : this(
        workspace = workspace,
        operations = AndroidArtifactExportOperations(context, workspace),
    )

    /** The default result confirms private ownership without writing outside the app. */
    fun privateDefault(artifact: Artifact): ArtifactExportResult =
        if (isValidPrivateArtifact(artifact)) {
            ArtifactExportResult(true, ArtifactExportCode.PRIVATE_ONLY)
        } else {
            invalidArtifact()
        }

    /** Returns the intent the Activity may launch after the user requests an explicit save. */
    fun createSafSaveIntent(artifact: Artifact): Intent? {
        if (!isValidPrivateArtifact(artifact)) return null
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = artifact.mimeType
            putExtra(Intent.EXTRA_TITLE, artifact.displayName)
        }
    }

    /** Completes an explicit ACTION_CREATE_DOCUMENT result. */
    fun saveToSaf(artifact: Artifact, destinationUri: String): ArtifactExportResult {
        if (!isValidPrivateArtifact(artifact)) return invalidArtifact()
        if (!isContentUri(destinationUri)) {
            return ArtifactExportResult(false, ArtifactExportCode.INVALID_DESTINATION)
        }
        return safely { operations.saveToSaf(artifact, destinationUri) }
    }

    /** MediaStore export is deliberately separate from the private default. */
    fun saveImageToMediaStore(artifact: Artifact): ArtifactExportResult {
        if (!isValidPrivateArtifact(artifact)) return invalidArtifact()
        if (!artifact.mimeType.lowercase(Locale.ROOT).startsWith("image/")) {
            return ArtifactExportResult(false, ArtifactExportCode.UNSUPPORTED_MEDIA_TYPE)
        }
        return safely { operations.saveImageToMediaStore(artifact) }
    }

    /** Opens a private artifact through a temporary, read-only content URI. */
    fun open(artifact: Artifact): ArtifactExportResult {
        val contentUri = privateContentUri(artifact) ?: return invalidArtifact()
        return safely { operations.open(contentUri, artifact.mimeType) }
    }

    /** Shares a private artifact through a temporary, read-only content URI. */
    fun share(artifact: Artifact): ArtifactExportResult {
        val contentUri = privateContentUri(artifact) ?: return invalidArtifact()
        return safely { operations.share(contentUri, artifact.mimeType, artifact.displayName) }
    }

    internal fun isValidPrivateArtifact(artifact: Artifact): Boolean {
        if (artifact.status != ArtifactStatus.COMPLETED ||
            artifact.size < 0L || artifact.size > ArtifactDigest.MAX_ARTIFACT_BYTES
        ) return false
        val file = runCatching { File(artifact.privatePath).canonicalFile }.getOrNull() ?: return false
        if (!workspace.isPrivate(file) || !file.isFile || file.length() != artifact.size) return false
        return runCatching { ArtifactDigest.sha256(file) == artifact.sha256 }.getOrDefault(false)
    }

    private fun privateContentUri(artifact: Artifact): String? {
        if (!isValidPrivateArtifact(artifact)) return null
        return runCatching { operations.privateContentUri(artifact) }.getOrNull()
            ?.takeIf(::isContentUri)
    }

    private fun safely(operation: () -> ArtifactExportResult): ArtifactExportResult = try {
        operation()
    } catch (_: SecurityException) {
        ArtifactExportResult(false, ArtifactExportCode.PERMISSION_REQUIRED)
    } catch (_: IOException) {
        ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
    } catch (_: RuntimeException) {
        ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
    }

    private fun invalidArtifact() = ArtifactExportResult(false, ArtifactExportCode.INVALID_ARTIFACT)

    private fun isContentUri(value: String): Boolean {
        val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("content", ignoreCase = true) && !uri.rawAuthority.isNullOrBlank()
    }
}

/** Android implementation using SAF, scoped MediaStore, and FileProvider grants. */
class AndroidArtifactExportOperations(
    private val context: Context,
    private val workspace: ArtifactWorkspace = ArtifactWorkspace.inAppPrivateStorage(context),
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val fileProviderAuthority: String = "${context.packageName}.fileprovider",
) : ArtifactExportOperations {
    override fun privateContentUri(artifact: Artifact): String? {
        val file = privateSource(artifact) ?: return null
        return runCatching { FileProvider.getUriForFile(context, fileProviderAuthority, file).toString() }
            .getOrNull()
    }

    override fun saveToSaf(artifact: Artifact, destinationUri: String): ArtifactExportResult {
        val uri = parseContentUri(destinationUri)
            ?: return ArtifactExportResult(false, ArtifactExportCode.INVALID_DESTINATION)
        return copyArtifactToUri(artifact, uri, ArtifactExportCode.SAVED_TO_SAF)
    }

    override fun saveImageToMediaStore(artifact: Artifact): ArtifactExportResult {
        if (apiLevel < Build.VERSION_CODES.Q) {
            return ArtifactExportResult(
                false,
                ArtifactExportCode.PERMISSION_REQUIRED,
                detail = "scoped MediaStore image export requires Android 10 or newer",
            )
        }
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, artifact.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, artifact.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/XiaoZhi")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
            val copied = copyArtifactToUri(artifact, uri, ArtifactExportCode.SAVED_TO_MEDIA_STORE)
            if (!copied.success) {
                resolver.delete(uri, null, null)
                return copied
            }
            val published = resolver.update(
                uri,
                android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            if (published != 1) throw IOException("media item could not be published")
            return copied
        } catch (_: SecurityException) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            return ArtifactExportResult(false, ArtifactExportCode.PERMISSION_REQUIRED)
        } catch (_: IOException) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
        } catch (_: RuntimeException) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
        }
    }

    override fun open(contentUri: String, mimeType: String): ArtifactExportResult {
        val uri = parseContentUri(contentUri)
            ?: return ArtifactExportResult(false, ArtifactExportCode.INVALID_DESTINATION)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("XiaoZhi artifact", uri)
        }
        return launch(intent, ArtifactExportCode.OPENED, uri.toString())
    }

    override fun share(contentUri: String, mimeType: String, displayName: String): ArtifactExportResult {
        val uri = parseContentUri(contentUri)
            ?: return ArtifactExportResult(false, ArtifactExportCode.INVALID_DESTINATION)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("XiaoZhi artifact", uri)
        }
        val chooser = Intent.createChooser(send, "分享 $displayName").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(chooser, ArtifactExportCode.SHARED, uri.toString())
    }

    private fun copyArtifactToUri(
        artifact: Artifact,
        destination: Uri,
        successCode: ArtifactExportCode,
    ): ArtifactExportResult {
        val source = privateSource(artifact) ?: return ArtifactExportResult(false, ArtifactExportCode.INVALID_ARTIFACT)
        try {
            val output = context.contentResolver.openOutputStream(destination, "rwt")
                ?: return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
            FileInputStream(source).use { input ->
                output.use { target -> copyExactly(input, target, artifact.size) }
            }
            return ArtifactExportResult(true, successCode, destination.toString())
        } catch (_: SecurityException) {
            return ArtifactExportResult(false, ArtifactExportCode.PERMISSION_REQUIRED)
        } catch (_: IOException) {
            return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
        } catch (_: RuntimeException) {
            return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
        }
    }

    private fun privateSource(artifact: Artifact): File? {
        if (artifact.status != ArtifactStatus.COMPLETED ||
            artifact.size < 0L || artifact.size > ArtifactDigest.MAX_ARTIFACT_BYTES
        ) return null
        val file = runCatching { File(artifact.privatePath).canonicalFile }.getOrNull() ?: return null
        if (!workspace.isPrivate(file) || !file.isFile || file.length() != artifact.size) return null
        return file
    }

    private fun copyExactly(input: FileInputStream, output: OutputStream, expectedSize: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            if (total > expectedSize || total > ArtifactDigest.MAX_ARTIFACT_BYTES) {
                throw IOException("artifact exceeds the export limit")
            }
            output.write(buffer, 0, count)
        }
        if (total != expectedSize) throw IOException("artifact source changed during export")
        output.flush()
    }

    private fun parseContentUri(value: String): Uri? {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme.equals("content", ignoreCase = true) && !it.authority.isNullOrBlank()
        }
    }

    private fun launch(intent: Intent, successCode: ArtifactExportCode, uri: String): ArtifactExportResult {
        try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ArtifactExportResult(true, successCode, uri)
        } catch (_: SecurityException) {
            return ArtifactExportResult(false, ArtifactExportCode.PERMISSION_REQUIRED)
        } catch (_: android.content.ActivityNotFoundException) {
            return ArtifactExportResult(false, ArtifactExportCode.NO_HANDLER)
        } catch (_: RuntimeException) {
            return ArtifactExportResult(false, ArtifactExportCode.EXPORT_FAILED)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
