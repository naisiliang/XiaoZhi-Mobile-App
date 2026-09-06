package com.lchuang.xiaozhimobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualIntentResolverTest {
    private val resolver = ContextualIntentResolver()
    private val context = ScreenContext(
        generationId = GenerationId(7L),
        packageName = "com.example.video",
        windowFingerprint = "com.example.video:11",
        root = ScreenNode(id = "root"),
        capturedAtMs = 1_000L,
    )

    @Test
    fun `first and second resolve unique current candidates with generation binding`() {
        val candidates = listOf(
            candidate("one", "第一项", position = 1),
            candidate("two", "第二项", position = 2),
        )

        val first = resolver.resolve("打开第一个", context, candidates)
        val second = resolver.resolve("选择第二个", context, candidates)

        assertEquals(ContextResolutionConfidence.HIGH, first.confidence)
        assertEquals("one", first.candidate?.id)
        assertEquals(context.generationId, first.generationId)
        assertEquals(context.packageName, first.packageName)
        assertEquals(context.windowFingerprint, first.windowFingerprint)
        assertEquals(ContextResolutionConfidence.HIGH, second.confidence)
        assertEquals("two", second.candidate?.id)
    }

    @Test
    fun `first and second use explicit list order when positions are absent`() {
        val candidates = listOf(candidate("one", "第一项"), candidate("two", "第二项"))

        val result = resolver.resolve("第一个", context, candidates)

        assertEquals(ContextResolutionConfidence.HIGH, result.confidence)
        assertEquals("one", result.candidate?.id)
    }

    @Test
    fun `ambiguous target requires clarification rather than guessing`() {
        val candidates = listOf(
            candidate("a", "同名项目", position = 1),
            candidate("b", "同名项目", position = 1),
        )

        val result = resolver.resolve("第一个", context, candidates)

        assertEquals(ContextResolutionConfidence.MEDIUM, result.confidence)
        assertNull(result.candidate)
        assertEquals(listOf("a", "b"), result.candidates.map(ContextCandidate::id))
        assertTrue(result.requiresClarification)
    }

    @Test
    fun `resolution alternatives cannot be mutated by callers`() {
        val result = resolver.resolve(
            "第一个",
            context,
            listOf(
                candidate("a", "项目", position = 1),
                candidate("b", "项目", position = 1),
            ),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            (result.candidates as MutableList<ContextCandidate>).clear()
        }
    }

    @Test
    fun `this and that use explicit recent session context`() {
        val current = candidate("current", "当前视频", kind = ContextTargetKind.VIDEO)
        val recent = candidate("recent", "刚才打开的视频", kind = ContextTargetKind.VIDEO)
        val history = ContextualHistory(currentTarget = current, recentOperation = recent)

        val thisResult = resolver.resolve("打开这个", context, listOf(current, recent), history)
        val thatResult = resolver.resolve("返回那个", context, listOf(current, recent), history)

        assertEquals(ContextResolutionConfidence.MEDIUM, thisResult.confidence)
        assertEquals(ContextResolutionConfidence.HIGH, thatResult.confidence)
        assertEquals("recent", thatResult.candidate?.id)
    }

    @Test
    fun `next uses the current candidate position`() {
        val candidates = listOf(
            candidate("one", "第一项", position = 1),
            candidate("two", "第二项", position = 2),
            candidate("three", "第三项", position = 3),
        )
        val history = ContextualHistory(currentTarget = candidates[0])

        val result = resolver.resolve("下一个", context, candidates, history)

        assertEquals(ContextResolutionConfidence.HIGH, result.confidence)
        assertEquals("two", result.candidate?.id)
    }

    @Test
    fun `current video resolves only a unique video candidate`() {
        val candidates = listOf(
            candidate("article", "文章", kind = ContextTargetKind.GENERIC),
            candidate("video", "正在播放", kind = ContextTargetKind.VIDEO),
        )

        val result = resolver.resolve("当前视频", context, candidates)

        assertEquals(ContextResolutionConfidence.HIGH, result.confidence)
        assertEquals("video", result.candidate?.id)
    }

    @Test
    fun `current video can use an explicit current session target`() {
        val current = candidate("video", "正在播放", kind = ContextTargetKind.VIDEO)

        val result = resolver.resolve(
            "当前视频",
            context,
            candidates = emptyList(),
            history = ContextualHistory(currentTarget = current),
        )

        assertEquals(ContextResolutionConfidence.HIGH, result.confidence)
        assertEquals("video", result.candidate?.id)
    }

    @Test
    fun `insufficient context returns low without guessing`() {
        val result = resolver.resolve("打开这个", context, candidates = emptyList())

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `stale candidates from another generation are rejected`() {
        val stale = candidate("old", "旧项目", generationId = GenerationId(6L))

        val result = resolver.resolve("第一个", context, listOf(stale))

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    @Test
    fun `blank or unknown query is low confidence`() {
        val result = resolver.resolve("", context, listOf(candidate("one", "项目")))

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    private fun candidate(
        id: String,
        label: String,
        position: Int? = null,
        kind: ContextTargetKind = ContextTargetKind.GENERIC,
        generationId: GenerationId = context.generationId,
    ) = ContextCandidate(
        id = id,
        label = label,
        kind = kind,
        position = position,
        generationId = generationId,
        packageName = context.packageName,
        windowFingerprint = context.windowFingerprint,
    )
}
