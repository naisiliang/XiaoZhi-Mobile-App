package com.lchuang.xiaozhimobile.video

import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSaveFlowTest {
    private val resolver = VideoIntentResolver()

    @Test
    fun resolvesSaveAndShareCurrentVideoIntents() {
        assertResolved("保存这个视频到相册", VideoIntent(VideoIntentType.SAVE_CURRENT))
        assertResolved("分享这个视频", VideoIntent(VideoIntentType.SHARE_CURRENT))
    }

    @Test
    fun refusesUnsafeVideoAcquisitionRequests() {
        assertTrue(resolver.resolve("绕过 DRM 下载这个视频") is VideoIntentResolution.Blocked)
        assertTrue(resolver.resolve("抓包下载会员视频") is VideoIntentResolution.Blocked)
        assertTrue(resolver.resolve("下载隐藏文件") is VideoIntentResolution.Blocked)
        assertTrue(resolver.resolve("打开相机") is VideoIntentResolution.Unsupported)
    }

    @Test
    fun genericVideoAdapterReturnsContextBoundSemanticProposal() {
        val screen = screen()
        val video = videoCandidate(screen)
        val adapter = GenericVideoAppAdapter("com.example.video")

        val result = adapter.propose(
            intent = VideoIntent(VideoIntentType.SAVE_CURRENT),
            screen = screen,
            currentVideo = video,
        )

        assertTrue(result is VideoAdapterResolution.Proposed)
        val proposed = result as VideoAdapterResolution.Proposed
        assertEquals(VideoSaveRoute.APP_NATIVE_SAVE, proposed.route)
        assertEquals(UiActionType.CLICK, proposed.proposal.action)
        assertEquals(video.id, proposed.proposal.target?.id)
        assertEquals(screen.generationId, proposed.proposal.target?.generationId)
    }

    @Test
    fun staleOrWrongPackageVideoCannotProduceAProposal() {
        val screen = screen()
        val adapter = GenericVideoAppAdapter("com.example.video")
        val stale = videoCandidate(screen(generation = 1L), generation = 1L)

        assertTrue(
            adapter.propose(
                VideoIntent(VideoIntentType.SAVE_CURRENT),
                screen(generation = 2L),
                stale,
            ) is VideoAdapterResolution.Unsupported,
        )
        assertTrue(
            adapter.propose(
                VideoIntent(VideoIntentType.SAVE_CURRENT),
                screen(packageName = "com.example.other"),
                videoCandidate(screen(packageName = "com.example.other")),
            ) is VideoAdapterResolution.Unsupported,
        )
    }

    @Test
    fun coordinatorUsesOnlyTheSelectedSafeRoute() {
        val calls = mutableListOf<String>()
        val coordinator = VideoSaveCoordinator(
            operations = object : VideoSaveOperations {
                override fun appNativeSave(packageName: String): Boolean {
                    calls += "native:$packageName"
                    return true
                }

                override fun androidShare(contentUri: String): Boolean {
                    calls += "share:$contentUri"
                    return true
                }

                override fun savePublicUrlToMediaStore(publicUrl: String): Boolean {
                    calls += "public:$publicUrl"
                    return true
                }
            },
        )

        val native = coordinator.execute(VideoSaveRequest(VideoSaveRoute.APP_NATIVE_SAVE, packageName = "com.example.video"))
        val share = coordinator.execute(VideoSaveRequest(VideoSaveRoute.ANDROID_SHARE, contentUri = "content://video/1"))
        val publicUrl = coordinator.execute(
            VideoSaveRequest(
                VideoSaveRoute.PUBLIC_URL_TO_MEDIA_STORE,
                publicUrl = "https://cdn.example/video.mp4",
            ),
        )

        assertTrue(native.success)
        assertTrue(share.success)
        assertTrue(publicUrl.success)
        assertEquals(
            listOf(
                "native:com.example.video",
                "share:content://video/1",
                "public:https://cdn.example/video.mp4",
            ),
            calls,
        )
    }

    @Test
    fun coordinatorRejectsUnsafeRoutesAndNeverCallsOperations() {
        val calls = mutableListOf<String>()
        val coordinator = VideoSaveCoordinator(
            operations = object : VideoSaveOperations {
                override fun appNativeSave(packageName: String): Boolean {
                    calls += "native"
                    return true
                }

                override fun androidShare(contentUri: String): Boolean {
                    calls += "share"
                    return true
                }

                override fun savePublicUrlToMediaStore(publicUrl: String): Boolean {
                    calls += "public"
                    return true
                }
            },
        )

        val blocked = coordinator.execute(VideoSaveRequest(VideoSaveRoute.BLOCKED, reason = "DRM_BYPASS"))
        val http = coordinator.execute(
            VideoSaveRequest(VideoSaveRoute.PUBLIC_URL_TO_MEDIA_STORE, publicUrl = "http://example.com/video"),
        )
        val file = coordinator.execute(
            VideoSaveRequest(VideoSaveRoute.ANDROID_SHARE, contentUri = "file:///sdcard/video.mp4"),
        )
        val local = coordinator.execute(
            VideoSaveRequest(VideoSaveRoute.PUBLIC_URL_TO_MEDIA_STORE, publicUrl = "https://localhost/video"),
        )
        val ipv6Loopback = coordinator.execute(
            VideoSaveRequest(VideoSaveRoute.PUBLIC_URL_TO_MEDIA_STORE, publicUrl = "https://[::1]/video"),
        )

        assertEquals(VideoSaveCode.BLOCKED_POLICY, blocked.code)
        assertEquals(VideoSaveCode.INVALID_PUBLIC_URL, http.code)
        assertEquals(VideoSaveCode.INVALID_SHARE_URI, file.code)
        assertEquals(VideoSaveCode.INVALID_PUBLIC_URL, local.code)
        assertEquals(VideoSaveCode.INVALID_PUBLIC_URL, ipv6Loopback.code)
        assertFalse(blocked.success)
        assertFalse(http.success)
        assertFalse(file.success)
        assertFalse(local.success)
        assertFalse(ipv6Loopback.success)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun operationFailureIsReportedWithoutSuccess() {
        val coordinator = VideoSaveCoordinator(
            operations = object : VideoSaveOperations {
                override fun appNativeSave(packageName: String) = false
                override fun androidShare(contentUri: String) = false
                override fun savePublicUrlToMediaStore(publicUrl: String) = false
            },
        )

        val result = coordinator.execute(
            VideoSaveRequest(VideoSaveRoute.APP_NATIVE_SAVE, packageName = "com.example.video"),
        )

        assertEquals(VideoSaveCode.NATIVE_SAVE_FAILED, result.code)
        assertFalse(result.success)
    }

    private fun assertResolved(command: String, expected: VideoIntent) {
        val result = resolver.resolve(command)
        assertTrue(result is VideoIntentResolution.Resolved)
        assertEquals(expected, (result as VideoIntentResolution.Resolved).intent)
    }

    private fun screen(
        packageName: String = "com.example.video",
        generation: Long = 8L,
    ) = ScreenContext(
        generationId = GenerationId(generation),
        packageName = packageName,
        windowFingerprint = "video-window-$generation",
        root = ScreenNode("root"),
        capturedAtMs = generation,
    )

    private fun videoCandidate(
        screen: ScreenContext,
        generation: Long = screen.generationId.value,
    ) = ContextCandidate(
        id = "current-video",
        label = "当前视频",
        kind = ContextTargetKind.VIDEO,
        generationId = GenerationId(generation),
        packageName = screen.packageName,
        windowFingerprint = screen.windowFingerprint,
    )
}
