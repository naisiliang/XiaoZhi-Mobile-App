package com.lchuang.xiaozhimobile.artifacts

import com.lchuang.xiaozhimobile.conversation.ArtifactCardAction
import com.lchuang.xiaozhimobile.conversation.ArtifactResultCard
import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationResult
import com.lchuang.xiaozhimobile.artifacts.generators.TextArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.TextArtifactValidator
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactVersionManagerTest {
    @Test
    fun editCreatesNewVersionAndKeepsPreviousVersionImmutable() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")
        val manager = ArtifactVersionManager(workspace, repository, clock = { 200L })

        val started = assertStarted(manager.beginEdit(original.artifactId))
        assertEquals(1, started.baseVersion)
        assertEquals(2, started.nextVersion)
        val completed = assertCompleted(
            manager.commit(
                started,
                write = writeUtf8("v2"),
                validate = { file -> TextArtifactValidator.validate(file) },
            ),
        )

        assertEquals(2, completed.version)
        assertEquals(1, completed.parentVersion)
        assertNotEquals(original.privatePath, completed.privatePath)
        assertEquals("v1", File(original.privatePath).readText())
        assertEquals("v2", File(completed.privatePath).readText())
        assertEquals(listOf(1, 2), repository.versions(original.artifactId).map { it.version })
        assertEquals(completed, repository.find(original.artifactId))
        assertFalse(started.tempFile.exists())
        repository.close()
    }

    @Test
    fun restoreCopiesAValidPriorVersionIntoANewCurrentVersion() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")
        val manager = ArtifactVersionManager(workspace, repository, clock = { 300L })
        val second = assertCompleted(
            manager.commit(
                assertStarted(manager.beginEdit(original.artifactId)),
                write = writeUtf8("v2"),
                validate = { file -> TextArtifactValidator.validate(file) },
            ),
        )

        val restored = assertCompleted(manager.restore(original.artifactId, version = 1))

        assertEquals(3, restored.version)
        assertEquals(2, restored.parentVersion)
        assertEquals("v1", File(restored.privatePath).readText())
        assertEquals("v1", File(original.privatePath).readText())
        assertEquals("v2", File(second.privatePath).readText())
        assertEquals(listOf(1, 2, 3), repository.versions(original.artifactId).map { it.version })
        assertEquals(restored, repository.find(original.artifactId))
        repository.close()
    }

    @Test
    fun cancelDeletesOnlyTheTemporaryEditAndKeepsCompletedVersions() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")
        val manager = ArtifactVersionManager(workspace, repository)
        val started = assertStarted(manager.beginEdit(original.artifactId))
        started.tempFile.writeText("uncommitted draft")

        assertTrue(manager.cancel(started))
        assertFalse(started.tempFile.exists())
        assertEquals(listOf(1), repository.versions(original.artifactId).map { it.version })
        assertEquals(original, repository.find(original.artifactId))
        assertFalse(manager.cancel(started))
        repository.close()
    }

    @Test
    fun staleEditCannotCreateAVersionFromAnOldCurrentPointer() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")
        val manager = ArtifactVersionManager(workspace, repository)
        val first = assertStarted(manager.beginEdit(original.artifactId))
        val second = assertStarted(manager.beginEdit(original.artifactId))

        val current = assertCompleted(
            manager.commit(
                second,
                write = writeUtf8("v2"),
                validate = { file -> TextArtifactValidator.validate(file) },
            ),
        )
        val stale = manager.commit(
            first,
            write = writeUtf8("must not replace v2"),
            validate = { file -> TextArtifactValidator.validate(file) },
        )

        assertTrue(stale is ArtifactVersionOperationResult.Rejected)
        assertEquals(ArtifactVersionOperationCode.STALE_BASE, (stale as ArtifactVersionOperationResult.Rejected).code)
        assertEquals(current, repository.find(original.artifactId))
        assertEquals(listOf(1, 2), repository.versions(original.artifactId).map { it.version })
        assertFalse(first.tempFile.exists())
        repository.close()
    }

    @Test
    fun invalidEditIsNotRegisteredAndCleansTheStagedAndDestinationFiles() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")
        val manager = ArtifactVersionManager(workspace, repository)
        val started = assertStarted(manager.beginEdit(original.artifactId))

        val result = manager.commit(
            started,
            write = writeUtf8("\u0000invalid"),
            validate = { file -> TextArtifactValidator.validate(file) },
        )

        assertTrue(result is ArtifactVersionOperationResult.Rejected)
        assertEquals(ArtifactVersionOperationCode.VALIDATION_FAILED, (result as ArtifactVersionOperationResult.Rejected).code)
        assertEquals(listOf(1), repository.versions(original.artifactId).map { it.version })
        assertEquals(original, repository.find(original.artifactId))
        assertFalse(started.tempFile.exists())
        assertEquals(1, workspace.artifactDirectory(original.artifactId).listFiles()?.count { it.isFile } ?: 0)
        repository.close()
    }

    @Test
    fun restoreRefusesAHistoryFileWhoseDigestNoLongerMatches() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")
        val manager = ArtifactVersionManager(workspace, repository)
        val current = assertCompleted(
            manager.commit(
                assertStarted(manager.beginEdit(original.artifactId)),
                write = writeUtf8("v2"),
                validate = { file -> TextArtifactValidator.validate(file) },
            ),
        )
        File(original.privatePath).writeText("tampered history")

        val result = manager.restore(original.artifactId, version = 1)

        assertTrue(result is ArtifactVersionOperationResult.Rejected)
        assertEquals(ArtifactVersionOperationCode.VALIDATION_FAILED, (result as ArtifactVersionOperationResult.Rejected).code)
        assertEquals(current, repository.find(original.artifactId))
        assertEquals(listOf(1, 2), repository.versions(original.artifactId).map { it.version })
        assertEquals("v2", File(current.privatePath).readText())
        repository.close()
    }

    @Test
    fun resultCardExposesVersionActionsAndRestoreOnlyForExistingHistory() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val original = createTextArtifact(workspace, repository, "v1")

        val firstCard = ArtifactResultCard.fromArtifact(original, hasPreviousVersion = false)
        assertEquals(original.artifactId, firstCard.artifactId)
        assertEquals(1, firstCard.version)
        assertEquals(
            setOf(ArtifactCardAction.OPEN, ArtifactCardAction.SAVE, ArtifactCardAction.SHARE, ArtifactCardAction.EDIT),
            firstCard.actions,
        )

        val historyCard = ArtifactResultCard.fromArtifact(original, hasPreviousVersion = true)
        assertTrue(ArtifactCardAction.RESTORE in historyCard.actions)
        repository.close()
    }

    private fun createTextArtifact(
        workspace: ArtifactWorkspace,
        repository: ArtifactRepository,
        text: String,
    ): Artifact {
        val result = TextArtifactGenerator(workspace, repository, clock = { 100L }).generate(
            sessionId = "session-version",
            sourceAgent = "file-assistant",
            displayName = "notes.txt",
            text = text,
        )
        assertTrue("expected completed artifact, got $result", result is ArtifactGenerationResult.Completed)
        return (result as ArtifactGenerationResult.Completed).artifact
    }

    private fun writeUtf8(value: String): (OutputStream) -> Unit = { output ->
        output.write(value.toByteArray(Charsets.UTF_8))
    }

    private fun assertStarted(result: ArtifactVersionOperationResult): ArtifactEditSession {
        assertTrue("expected started edit, got $result", result is ArtifactVersionOperationResult.Started)
        return (result as ArtifactVersionOperationResult.Started).session
    }

    private fun assertCompleted(result: ArtifactVersionOperationResult): Artifact {
        assertTrue("expected completed version, got $result", result is ArtifactVersionOperationResult.Completed)
        return (result as ArtifactVersionOperationResult.Completed).artifact
    }

    private fun workspace(): ArtifactWorkspace = ArtifactWorkspace(
        Files.createTempDirectory("artifact-versions-").toFile(),
    )
}
