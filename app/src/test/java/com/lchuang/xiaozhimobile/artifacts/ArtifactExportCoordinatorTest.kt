package com.lchuang.xiaozhimobile.artifacts

import com.lchuang.xiaozhimobile.image.ImageArtifact
import com.lchuang.xiaozhimobile.image.ImageIntentMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactExportCoordinatorTest {
    @Test
    fun privateDefaultDoesNotCallAnExternalExportOperation() {
        val fixture = fixture()
        val result = fixture.coordinator.privateDefault(fixture.artifact)

        assertTrue(result.success)
        assertEquals(ArtifactExportCode.PRIVATE_ONLY, result.code)
        assertEquals(0, fixture.operations.externalCalls)
    }

    @Test
    fun safSaveIsExplicitAndUsesTheChosenDocumentUri() {
        val fixture = fixture()
        val result = fixture.coordinator.saveToSaf(fixture.artifact, "content://documents/tree/selected")

        assertTrue(result.success)
        assertEquals(ArtifactExportCode.SAVED_TO_SAF, result.code)
        assertEquals("content://documents/tree/selected", fixture.operations.safUri)
        assertEquals(1, fixture.operations.externalCalls)
    }

    @Test
    fun imageGallerySaveIsExplicitAndImageOnly() {
        val fixture = fixture(mimeType = "image/png", bytes = PNG_BYTES)
        val image = ImageArtifact(
            artifact = fixture.artifact,
            prompt = "画一只猫",
            mode = ImageIntentMode.CREATE,
        )
        val exports = com.lchuang.xiaozhimobile.image.ImageExportCoordinator(
            workspace = fixture.workspace,
            artifacts = fixture.coordinator,
        )

        val result = exports.saveToGallery(image)

        assertTrue(result.success)
        assertEquals(ArtifactExportCode.SAVED_TO_MEDIA_STORE, result.code)
        assertEquals(1, fixture.operations.mediaStoreCalls)
    }

    @Test
    fun openAndShareUsePrivateContentUriWithoutCopyingToPublicStorage() {
        val fixture = fixture(mimeType = "image/png", bytes = PNG_BYTES)
        val opened = fixture.coordinator.open(fixture.artifact)
        val shared = fixture.coordinator.share(fixture.artifact)

        assertEquals(ArtifactExportCode.OPENED, opened.code)
        assertEquals(ArtifactExportCode.SHARED, shared.code)
        assertEquals("content://xiaozhi/artifact", fixture.operations.lastContentUri)
        assertEquals(0, fixture.operations.externalCalls)
    }

    @Test
    fun permissionFailureIsTruthfulAndDoesNotBecomeSuccess() {
        val fixture = fixture()
        fixture.operations.throwSecurityException = true

        val result = fixture.coordinator.saveToSaf(fixture.artifact, "content://documents/tree/selected")

        assertFalse(result.success)
        assertEquals(ArtifactExportCode.PERMISSION_REQUIRED, result.code)
    }

    @Test
    fun invalidDestinationAndInvalidArtifactFailClosed() {
        val fixture = fixture()
        val invalidDestination = fixture.coordinator.saveToSaf(fixture.artifact, "file:///sdcard/out.txt")
        val outside = fixture.artifact.copy(privatePath = File.createTempFile("outside-", ".txt").canonicalPath)
        val invalidArtifact = fixture.coordinator.privateDefault(outside)

        assertEquals(ArtifactExportCode.INVALID_DESTINATION, invalidDestination.code)
        assertEquals(ArtifactExportCode.INVALID_ARTIFACT, invalidArtifact.code)
        assertEquals(0, fixture.operations.externalCalls)
    }

    private fun fixture(
        mimeType: String = "text/plain",
        bytes: ByteArray = "private artifact".toByteArray(),
    ): Fixture {
        val workspace = ArtifactWorkspace(Files.createTempDirectory("artifact-export-").toFile())
        val file = workspace.allocateVersionFile("artifact-export", 1, "bin")
        file.writeBytes(bytes)
        val repository = ArtifactRepository(workspace)
        val artifact = repository.registerCompleted(
            sessionId = "export-session",
            mimeType = mimeType,
            displayName = "result.bin",
            privateFile = file,
            sourceAgent = "test",
            artifactId = "artifact-export",
            createdAt = 1L,
        )
        val operations = RecordingOperations()
        return Fixture(workspace, repository, artifact, operations, ArtifactExportCoordinator(workspace, operations))
    }

    private data class Fixture(
        val workspace: ArtifactWorkspace,
        val repository: ArtifactRepository,
        val artifact: Artifact,
        val operations: RecordingOperations,
        val coordinator: ArtifactExportCoordinator,
    )

    private class RecordingOperations : ArtifactExportOperations {
        var externalCalls = 0
        var safUri: String? = null
        var mediaStoreCalls = 0
        var lastContentUri: String? = null
        var throwSecurityException = false

        override fun privateContentUri(artifact: Artifact): String = "content://xiaozhi/artifact"

        override fun saveToSaf(artifact: Artifact, destinationUri: String): ArtifactExportResult {
            externalCalls++
            if (throwSecurityException) throw SecurityException("permission denied")
            safUri = destinationUri
            return ArtifactExportResult(true, ArtifactExportCode.SAVED_TO_SAF, destinationUri)
        }

        override fun saveImageToMediaStore(artifact: Artifact): ArtifactExportResult {
            externalCalls++
            mediaStoreCalls++
            return ArtifactExportResult(true, ArtifactExportCode.SAVED_TO_MEDIA_STORE, "content://media/image")
        }

        override fun open(contentUri: String, mimeType: String): ArtifactExportResult {
            lastContentUri = contentUri
            return ArtifactExportResult(true, ArtifactExportCode.OPENED, contentUri)
        }

        override fun share(contentUri: String, mimeType: String, displayName: String): ArtifactExportResult {
            lastContentUri = contentUri
            return ArtifactExportResult(true, ArtifactExportCode.SHARED, contentUri)
        }
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
