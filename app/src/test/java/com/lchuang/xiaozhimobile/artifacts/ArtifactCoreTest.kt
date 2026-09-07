package com.lchuang.xiaozhimobile.artifacts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactCoreTest {
    @Test
    fun workspaceKeepsFilesPrivateAndRejectsTraversal() {
        val workspace = workspace()
        val privateFile = workspace.allocateVersionFile("artifact-demo", 1, "txt")
        privateFile.writeText("hello")

        assertTrue(workspace.isPrivate(privateFile))
        assertTrue(privateFile.canonicalPath.startsWith(workspace.rootDirectory.canonicalPath + File.separator))
        assertThrows<IllegalArgumentException> { workspace.artifactDirectory("../outside") }
        assertThrows<IllegalArgumentException> { workspace.allocateVersionFile("artifact-demo", 1, "../txt") }
    }

    @Test
    fun importCopiesExternalFileWithoutOverwritingOriginal() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val external = Files.createTempFile("user-original-", ".txt").toFile()
        external.writeText("original bytes")
        val originalPath = external.canonicalPath

        val artifact = repository.importExternalFile(
            sourceFile = external,
            sessionId = "session-1",
            mimeType = "text/plain",
            displayName = "notes.txt",
            sourceAgent = "file-assistant",
        )

        assertNotEquals(originalPath, artifact.privatePath)
        assertTrue(workspace.isPrivate(File(artifact.privatePath)))
        assertEquals("original bytes", external.readText())
        assertEquals("original bytes", File(artifact.privatePath).readText())
        external.writeText("changed outside")
        assertEquals("original bytes", File(artifact.privatePath).readText())
        repository.close()
    }

    @Test
    fun repositoryPersistsTruthfulMetadataAndVersionLineage() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val firstFile = workspace.allocateVersionFile("artifact-lineage", 1, "txt")
        firstFile.writeText("v1")
        val first = repository.registerCompleted(
            artifactId = "artifact-lineage",
            sessionId = "session-1",
            mimeType = "text/plain",
            displayName = "notes.txt",
            privateFile = firstFile,
            sourceAgent = "xiaobai",
        )
        assertEquals(2L, first.size)
        assertEquals(1, first.version)
        assertEquals(first, repository.find(first.artifactId))

        val secondFile = workspace.allocateVersionFile("artifact-lineage", 2, "txt")
        secondFile.writeText("v2")
        val second = repository.registerCompleted(
            artifactId = "artifact-lineage",
            sessionId = first.sessionId,
            mimeType = first.mimeType,
            displayName = first.displayName,
            privateFile = secondFile,
            sourceAgent = "xiaobai",
            version = 2,
            parentVersion = 1,
        )
        assertEquals(2, second.version)
        assertEquals(listOf(1, 2), repository.versions("artifact-lineage").map { it.version })
        assertEquals("v1", firstFile.readText())
        assertEquals("v2", secondFile.readText())
        repository.close()
    }

    @Test
    fun savingAnOlderVersionDoesNotReplaceTheCurrentVersion() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val firstFile = workspace.allocateVersionFile("artifact-immutable", 1, "txt")
        firstFile.writeText("v1")
        val first = repository.registerCompleted(
            artifactId = "artifact-immutable",
            sessionId = "session-1",
            mimeType = "text/plain",
            displayName = "notes.txt",
            privateFile = firstFile,
            sourceAgent = "xiaobai",
        )
        val secondFile = workspace.allocateVersionFile("artifact-immutable", 2, "txt")
        secondFile.writeText("v2")
        repository.registerCompleted(
            artifactId = first.artifactId,
            sessionId = first.sessionId,
            mimeType = first.mimeType,
            displayName = first.displayName,
            privateFile = secondFile,
            sourceAgent = first.sourceAgent,
            version = 2,
            parentVersion = 1,
        )

        repository.save(first)

        assertEquals(2, repository.find(first.artifactId)?.version)
        assertEquals(listOf(1, 2), repository.versions(first.artifactId).map { it.version })
        repository.close()
    }

    @Test
    fun repositoryRejectsExternalOrMismatchedArtifacts() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val external = Files.createTempFile("outside-", ".txt").toFile()
        external.writeText("outside")
        assertThrows<IllegalArgumentException> {
            repository.save(
                Artifact(
                    artifactId = "external-artifact",
                    sessionId = "session-1",
                    mimeType = "text/plain",
                    displayName = "outside.txt",
                    privatePath = external.canonicalPath,
                    size = external.length(),
                    sha256 = ArtifactDigest.sha256(external),
                    createdAt = 1L,
                    sourceAgent = "xiaobai",
                ),
            )
        }

        val inside = workspace.allocateVersionFile("mismatch-artifact", 1, "txt")
        inside.writeText("actual")
        assertThrows<IllegalArgumentException> {
            repository.save(
                Artifact(
                    artifactId = "mismatch-artifact",
                    sessionId = "session-1",
                    mimeType = "text/plain",
                    displayName = "mismatch.txt",
                    privatePath = inside.canonicalPath,
                    size = inside.length(),
                    sha256 = "0".repeat(64),
                    createdAt = 1L,
                    sourceAgent = "xiaobai",
                ),
            )
        }
        repository.close()
        assertTrue(external.exists())
        assertTrue(external.delete())
    }

    private fun workspace(): ArtifactWorkspace = ArtifactWorkspace(
        Files.createTempDirectory("artifact-workspace-").toFile(),
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("unexpected exception: ${error::class.java.name}", error is T)
            return
        }
        throw AssertionError("expected ${T::class.java.name}")
    }
}
