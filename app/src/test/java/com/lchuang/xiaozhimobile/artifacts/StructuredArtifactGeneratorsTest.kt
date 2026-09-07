package com.lchuang.xiaozhimobile.artifacts

import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationResult
import com.lchuang.xiaozhimobile.artifacts.generators.DocxArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.DocxArtifactValidator
import com.lchuang.xiaozhimobile.artifacts.generators.PdfArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.PdfArtifactValidator
import com.lchuang.xiaozhimobile.artifacts.generators.XlsxArtifactGenerator
import com.lchuang.xiaozhimobile.artifacts.generators.XlsxArtifactValidator
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredArtifactGeneratorsTest {
    @Test
    fun docxGeneratorCreatesValidatedOpcPackage() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val result = DocxArtifactGenerator(workspace, repository).generate(
            sessionId = "session-docx",
            sourceAgent = "file-assistant",
            displayName = "报告.docx",
            title = "小白报告",
            paragraphs = listOf("第一段", "第二段"),
        )

        val artifact = assertCompleted(result)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", artifact.mimeType)
        assertEquals(ArtifactStatus.COMPLETED, artifact.status)
        assertTrue(DocxArtifactValidator.validate(File(artifact.privatePath)).isValid)
        ZipFile(artifact.privatePath).use { zip ->
            assertTrue(zip.getEntry("[Content_Types].xml") != null)
            assertTrue(zip.getEntry("_rels/.rels") != null)
            assertTrue(zip.getEntry("word/document.xml") != null)
        }
        repository.close()
    }

    @Test
    fun xlsxGeneratorCreatesValidatedWorkbookAndSheet() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val result = XlsxArtifactGenerator(workspace, repository).generate(
            sessionId = "session-xlsx",
            sourceAgent = "file-assistant",
            displayName = "表格.xlsx",
            sheetName = "数据",
            rows = listOf(
                listOf("姓名", "备注"),
                listOf("小白", "真实数据"),
            ),
        )

        val artifact = assertCompleted(result)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", artifact.mimeType)
        assertTrue(XlsxArtifactValidator.validate(File(artifact.privatePath)).isValid)
        ZipFile(artifact.privatePath).use { zip ->
            assertTrue(zip.getEntry("xl/workbook.xml") != null)
            assertTrue(zip.getEntry("xl/worksheets/sheet1.xml") != null)
            assertTrue(zip.getEntry("xl/_rels/workbook.xml.rels") != null)
        }
        repository.close()
    }

    @Test
    fun pdfGeneratorCreatesHeaderCrossReferenceAndNonEmptyPages() {
        val workspace = workspace()
        val repository = ArtifactRepository(workspace)
        val result = PdfArtifactGenerator(workspace, repository).generate(
            sessionId = "session-pdf",
            sourceAgent = "file-assistant",
            displayName = "报告.pdf",
            pages = listOf("第一页\n小白", "第二页"),
        )

        val artifact = assertCompleted(result)
        assertEquals("application/pdf", artifact.mimeType)
        val bytes = File(artifact.privatePath).readBytes()
        assertTrue(bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()))
        assertTrue(PdfArtifactValidator.validate(File(artifact.privatePath)).isValid)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("/Type /Page"))
        repository.close()
    }

    @Test
    fun structuredValidatorsRejectPlainTextExtensionSpoofingAndCorruptPackages() {
        val fakeDocx = Files.createTempFile("fake-", ".docx").toFile()
        val fakeXlsx = Files.createTempFile("fake-", ".xlsx").toFile()
        val fakePdf = Files.createTempFile("fake-", ".pdf").toFile()
        fakeDocx.writeText("this is not an OPC package")
        fakeXlsx.writeText("this is not an OPC package")
        fakePdf.writeText("%PDF-1.4 but no pages")

        assertFalse(DocxArtifactValidator.validate(fakeDocx).isValid)
        assertFalse(XlsxArtifactValidator.validate(fakeXlsx).isValid)
        assertFalse(PdfArtifactValidator.validate(fakePdf).isValid)
        assertTrue(fakeDocx.delete())
        assertTrue(fakeXlsx.delete())
        assertTrue(fakePdf.delete())
    }

    private fun workspace(): ArtifactWorkspace = ArtifactWorkspace(
        Files.createTempDirectory("structured-artifacts-").toFile(),
    )

    private fun assertCompleted(result: ArtifactGenerationResult): Artifact {
        assertTrue("expected completed artifact, got $result", result is ArtifactGenerationResult.Completed)
        return (result as ArtifactGenerationResult.Completed).artifact
    }
}
