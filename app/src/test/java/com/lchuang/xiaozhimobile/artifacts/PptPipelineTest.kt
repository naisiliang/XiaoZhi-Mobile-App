package com.lchuang.xiaozhimobile.artifacts

import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationResult
import com.lchuang.xiaozhimobile.artifacts.ppt.PresentationRequest
import com.lchuang.xiaozhimobile.artifacts.ppt.PptPlanResult
import com.lchuang.xiaozhimobile.artifacts.ppt.PptPlanner
import com.lchuang.xiaozhimobile.artifacts.ppt.PptRenderer
import com.lchuang.xiaozhimobile.artifacts.ppt.PptValidator
import com.lchuang.xiaozhimobile.artifacts.ppt.SlideRequest
import com.lchuang.xiaozhimobile.artifacts.ppt.SlideMedia
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PptPipelineTest {
    @Test
    fun plannerRejectsOversizedPresentationBeforeRendering() {
        val request = PresentationRequest(
            title = "超长演示",
            slides = List(PptPlanner.MAX_SLIDES + 1) { SlideRequest(title = "第 $it 页") },
        )

        val result = PptPlanner.plan(request)

        assertTrue(result is PptPlanResult.Rejected)
    }

    @Test
    fun rendererCreatesValidatedPptxWithSlidesRelationshipsAndMedia() {
        val request = PresentationRequest(
            title = "小白演示",
            slides = listOf(
                SlideRequest(
                    title = "封面",
                    body = listOf("真实结构化内容"),
                    media = listOf(SlideMedia("logo.png", onePixelPng())),
                ),
                SlideRequest(title = "第二页", body = listOf("下一步")),
            ),
        )
        val planned = PptPlanner.plan(request)
        assertTrue(planned is PptPlanResult.Planned)

        val workspace = ArtifactWorkspace(Files.createTempDirectory("ppt-artifacts-").toFile())
        val repository = ArtifactRepository(workspace)
        val result = PptRenderer(workspace, repository).render(
            sessionId = "session-ppt",
            sourceAgent = "ppt-agent",
            displayName = "演示.pptx",
            plan = (planned as PptPlanResult.Planned).plan,
        )

        assertTrue(result is ArtifactGenerationResult.Completed)
        val artifact = (result as ArtifactGenerationResult.Completed).artifact
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", artifact.mimeType)
        val validation = PptValidator.validate(File(artifact.privatePath))
        assertTrue("expected valid PPTX, got $validation", validation.isValid)
        assertEquals(2, validation.slideCount)
        assertEquals(1, validation.mediaCount)
        ZipFile(artifact.privatePath).use { zip ->
            assertTrue(zip.getEntry("[Content_Types].xml") != null)
            assertTrue(zip.getEntry("ppt/presentation.xml") != null)
            assertTrue(zip.getEntry("ppt/slides/slide1.xml") != null)
            assertTrue(zip.getEntry("ppt/slides/_rels/slide1.xml.rels") != null)
            assertTrue(zip.getEntry("ppt/media/logo.png") != null)
        }
        repository.close()
    }

    @Test
    fun plannerRejectsUnsafeOrInvalidMediaAndValidatorRejectsSpoofedPptx() {
        val unsafeMedia = PptPlanner.plan(
            PresentationRequest(
                title = "不安全",
                slides = listOf(SlideRequest("一页", media = listOf(SlideMedia("../evil.png", onePixelPng())))),
            ),
        )
        assertTrue(unsafeMedia is PptPlanResult.Rejected)

        val fake = Files.createTempFile("fake-ppt-", ".pptx").toFile()
        fake.writeText("plain text with a pptx extension")
        val validation = PptValidator.validate(fake)
        assertFalse(validation.isValid)
        assertTrue(fake.delete())
    }

    private fun workspace(): ArtifactWorkspace = ArtifactWorkspace(
        Files.createTempDirectory("ppt-artifacts-").toFile(),
    )

    private fun onePixelPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )
}
