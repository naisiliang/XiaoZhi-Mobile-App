package com.lchuang.xiaozhimobile.image

import com.lchuang.xiaozhimobile.ApiMode
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import com.lchuang.xiaozhimobile.conversation.ArtifactCardAction
import com.lchuang.xiaozhimobile.conversation.ArtifactResultCard
import com.lchuang.xiaozhimobile.providers.CapabilitySupport
import com.lchuang.xiaozhimobile.providers.ProviderCapability
import com.lchuang.xiaozhimobile.providers.ProviderCapabilityProfile
import com.lchuang.xiaozhimobile.providers.ProviderConnectionConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolTest {
    @Test
    fun resolverCreatesImageIntentWithoutScreenContext() {
        val intent = ImageIntentResolver.resolve("生成一张小白在月球上的图片")

        assertNotNull(intent)
        assertEquals(ImageIntentMode.CREATE, intent?.mode)
        assertEquals("生成一张小白在月球上的图片", intent?.prompt)
        assertEquals(null, intent?.imageContext)
    }

    @Test
    fun resolverKeepsImageContextForEditAndRegenerate() {
        val context = ImageGenerationContext("artifact-image", 1, "上一张图")

        val edit = ImageIntentResolver.resolve("把天空改成紫色", context)
        val regenerate = ImageIntentResolver.resolve("再生成一张", context)

        assertEquals(ImageIntentMode.EDIT, edit?.mode)
        assertEquals(context, edit?.imageContext)
        assertEquals("把天空改成紫色", edit?.prompt)
        assertEquals(ImageIntentMode.REGENERATE, regenerate?.mode)
        assertEquals(context, regenerate?.imageContext)
        assertEquals("上一张图", regenerate?.prompt)
    }

    @Test
    fun toolRejectsProvidersWithoutImageGenerationCapabilityBeforeCallingProvider() {
        val transport = RecordingImageProvider { error("provider must not be called") }
        val tool = tool(transport)
        val result = tool.generate(
            intent = ImageIntent(ImageIntentMode.CREATE, "画一只猫"),
            provider = providerConfig(),
            capabilities = providerProfile(CapabilitySupport.UNSUPPORTED),
            sessionId = "image-session",
            sourceAgent = "image-designer",
        )

        assertTrue(result is ImageGenerationResult.Rejected)
        assertEquals(ImageGenerationCode.CAPABILITY_UNAVAILABLE, (result as ImageGenerationResult.Rejected).code)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun toolStoresRealPngPrivatelyAndCarriesContextForEdit() {
        val transport = RecordingImageProvider { request ->
            assertEquals("https://provider.example/v1/images/generations", request.endpoint)
            assertEquals(ImageIntentMode.CREATE, request.mode)
            assertEquals(null, request.sourceImageBytes)
            ImageProviderResult.Success(PNG_BYTES)
        }
        val tool = tool(transport)
        val profile = providerProfile(CapabilitySupport.SUPPORTED)
        val created = assertCompleted(
            tool.generate(
                intent = ImageIntent(ImageIntentMode.CREATE, "画一只猫"),
                provider = providerConfig(),
                capabilities = profile,
                sessionId = "image-session",
                sourceAgent = "image-designer",
            ),
        )
        assertEquals("image/png", created.artifact.mimeType)
        assertTrue(File(created.artifact.privatePath).isFile)
        assertArrayEquals(PNG_BYTES, File(created.artifact.privatePath).readBytes())

        transport.handler = { request ->
            assertEquals("https://provider.example/v1/images/edits", request.endpoint)
            assertEquals(ImageIntentMode.EDIT, request.mode)
            assertArrayEquals(PNG_BYTES, request.sourceImageBytes)
            ImageProviderResult.Success(PNG_BYTES)
        }
        val edited = assertCompleted(
            tool.generate(
                intent = ImageIntent(
                    mode = ImageIntentMode.EDIT,
                    prompt = "把天空改成紫色",
                    imageContext = ImageGenerationContext(
                        artifactId = created.artifact.artifactId,
                        version = created.artifact.version,
                        prompt = created.prompt,
                        artifact = created.artifact,
                    ),
                ),
                provider = providerConfig(),
                capabilities = profile,
                sessionId = "image-session",
                sourceAgent = "image-designer",
            ),
        )
        assertEquals(created.artifact.artifactId, edited.sourceArtifactId)
        assertEquals(created.artifact.version, edited.sourceVersion)
        assertEquals(2, transport.requests.size)
        assertTrue(ArtifactCardAction.EDIT in ArtifactResultCard.fromImageArtifact(edited, hasPreviousVersion = false).actions)
        assertTrue(ArtifactCardAction.REGENERATE in ArtifactResultCard.fromImageArtifact(edited, hasPreviousVersion = false).actions)
    }

    @Test
    fun providerFailureIsSanitizedAndDoesNotCreateAnArtifact() {
        val transport = RecordingImageProvider {
            ImageProviderResult.Failure("Bearer secret-token data:image/png;base64,AAAAAA")
        }
        val workspace = ArtifactWorkspace(Files.createTempDirectory("image-failure-").toFile())
        val repository = ArtifactRepository(workspace)
        val result = ImageGenerationTool(
            workspace = workspace,
            repository = repository,
            provider = transport,
        ).generate(
            intent = ImageIntent(ImageIntentMode.CREATE, "画一只猫"),
            provider = providerConfig(),
            capabilities = providerProfile(CapabilitySupport.SUPPORTED),
            sessionId = "image-session",
            sourceAgent = "image-designer",
        )

        assertTrue(result is ImageGenerationResult.Rejected)
        val rejection = result as ImageGenerationResult.Rejected
        assertEquals(ImageGenerationCode.PROVIDER_FAILED, rejection.code)
        assertFalse(rejection.detail.contains("secret-token"))
        assertFalse(rejection.detail.contains("data:image"))
        assertTrue(repository.list().isEmpty())
        repository.close()
    }

    @Test
    fun providerFailureDoesNotEchoConfiguredApiKey() {
        val transport = RecordingImageProvider {
            ImageProviderResult.Failure("provider rejected request for secret-token")
        }
        val tool = tool(transport)

        val result = tool.generate(
            intent = ImageIntent(ImageIntentMode.CREATE, "画一只猫"),
            provider = providerConfig(),
            capabilities = providerProfile(CapabilitySupport.SUPPORTED),
            sessionId = "image-session",
            sourceAgent = "image-designer",
        )

        assertTrue(result is ImageGenerationResult.Rejected)
        assertFalse((result as ImageGenerationResult.Rejected).detail.contains("secret-token"))
    }

    private fun tool(provider: RecordingImageProvider): ImageGenerationTool {
        val workspace = ArtifactWorkspace(Files.createTempDirectory("image-tool-").toFile())
        return ImageGenerationTool(workspace, ArtifactRepository(workspace), provider)
    }

    private fun providerConfig() = ProviderConnectionConfig(
        baseUrl = "https://provider.example",
        model = "image-model",
        apiMode = ApiMode.CHAT_COMPLETIONS,
        apiKey = "secret-token",
    )

    private fun providerProfile(support: CapabilitySupport) = ProviderCapabilityProfile(
        baseUrl = "https://provider.example",
        model = "image-model",
        apiMode = ApiMode.CHAT_COMPLETIONS,
        capabilities = mapOf(ProviderCapability.IMAGE_GENERATION to support),
    )

    private fun assertCompleted(result: ImageGenerationResult): ImageArtifact {
        assertTrue("expected completed image, got $result", result is ImageGenerationResult.Completed)
        return (result as ImageGenerationResult.Completed).image
    }

    private class RecordingImageProvider(
        var handler: (ImageProviderRequest) -> ImageProviderResult,
    ) : ImageGenerationProvider {
        val requests = mutableListOf<ImageProviderRequest>()

        override fun generate(request: ImageProviderRequest): ImageProviderResult {
            requests += request
            return handler(request)
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
