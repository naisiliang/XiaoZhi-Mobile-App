package com.lchuang.xiaozhimobile.artifacts

import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationResult
import com.lchuang.xiaozhimobile.artifacts.generators.CsvArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.CsvArtifactValidator
import com.lchuang.xiaozhimobile.artifacts.generators.TextArtifactFormat
import com.lchuang.xiaozhimobile.artifacts.generators.TextArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.TextArtifactValidator
import com.lchuang.xiaozhimobile.artifacts.generators.ZipArtifactEntry
import com.lchuang.xiaozhimobile.artifacts.generators.ZipArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.ZipArtifactValidator
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactGeneratorsTest {
    @Test
    fun textGeneratorWritesActualUtf8AndCompletesOnlyAfterValidation() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val result = TextArtifactGenerator(workspace, repository).generate(
            sessionId = "session-text",
            sourceAgent = "file-assistant",
            displayName = "说明.txt",
            text = "小白\n真实 UTF-8 内容",
        )

        val artifact = assertCompleted(result)
        assertEquals("text/plain", artifact.mimeType)
        assertEquals("小白\n真实 UTF-8 内容", File(artifact.privatePath).readText())
        assertTrue(TextArtifactValidator.validate(File(artifact.privatePath)))
        assertEquals(ArtifactStatus.COMPLETED, artifact.status)
        repository.close()
    }

    @Test
    fun markdownGeneratorUsesMarkdownMimeAndRealTextBytes() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val result = TextArtifactGenerator(workspace, repository).generate(
            sessionId = "session-markdown",
            sourceAgent = "research-assistant",
            displayName = "报告.md",
            text = "# 标题\n\n- 项目",
            format = TextArtifactFormat.MARKDOWN,
        )

        val artifact = assertCompleted(result)
        assertEquals("text/markdown", artifact.mimeType)
        assertEquals("# 标题\n\n- 项目", TextArtifactValidator.readUtf8(File(artifact.privatePath)))
        repository.close()
    }

    @Test
    fun csvGeneratorQuotesCellsAndValidatorParsesTheWrittenFile() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val rows = listOf(
            listOf("姓名", "备注"),
            listOf("小白", "a,b"),
            listOf("引号", "他说\"hi\""),
        )
        val result = CsvArtifactGenerator(workspace, repository).generate(
            sessionId = "session-csv",
            sourceAgent = "file-assistant",
            displayName = "表格.csv",
            rows = rows,
        )

        val artifact = assertCompleted(result)
        assertEquals(rows, CsvArtifactValidator.parse(File(artifact.privatePath)).rows)
        assertTrue(File(artifact.privatePath).readBytes().isNotEmpty())
        repository.close()
    }

    @Test
    fun zipGeneratorRejectsTraversalAndCreatesSafeArchive() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val rejected = ZipArtifactGenerator(workspace, repository).generate(
            sessionId = "session-zip",
            sourceAgent = "file-assistant",
            displayName = "bad.zip",
            entries = listOf(ZipArtifactEntry("../escape.txt", "blocked".toByteArray())),
        )
        assertTrue(rejected is ArtifactGenerationResult.Rejected)
        assertTrue(repository.list().isEmpty())

        val result = ZipArtifactGenerator(workspace, repository).generate(
            sessionId = "session-zip",
            sourceAgent = "file-assistant",
            displayName = "safe.zip",
            entries = listOf(
                ZipArtifactEntry("docs/readme.txt", "hello".toByteArray()),
                ZipArtifactEntry("assets/icon.bin", byteArrayOf(1, 2, 3)),
            ),
        )
        val artifact = assertCompleted(result)
        assertTrue(ZipArtifactValidator.validate(File(artifact.privatePath)).isValid)
        ZipFile(artifact.privatePath).use { zip ->
            assertEquals("hello", zip.getInputStream(zip.getEntry("docs/readme.txt")).reader().readText())
            assertEquals(2, zip.size())
        }
        repository.close()
    }

    @Test
    fun validatorsRejectMalformedUtf8AndZipTraversal() {
        val invalidText = Files.createTempFile("invalid-utf8-", ".txt").toFile()
        invalidText.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertFalse(TextArtifactValidator.validate(invalidText))

        val invalidZip = Files.createTempFile("invalid-zip-", ".zip").toFile()
        ZipOutputStream(FileOutputStream(invalidZip)).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape.txt"))
            zip.write("blocked".toByteArray())
            zip.closeEntry()
        }
        assertFalse(ZipArtifactValidator.validate(invalidZip).isValid)
        assertTrue(invalidText.delete())
        assertTrue(invalidZip.delete())
    }

    private fun workspace(): ArtifactWorkspace = ArtifactWorkspace(
        Files.createTempDirectory("artifact-generators-").toFile(),
    )

    private fun assertCompleted(result: ArtifactGenerationResult): Artifact {
        assertTrue("expected completed artifact, got $result", result is ArtifactGenerationResult.Completed)
        return (result as ArtifactGenerationResult.Completed).artifact
    }
}
