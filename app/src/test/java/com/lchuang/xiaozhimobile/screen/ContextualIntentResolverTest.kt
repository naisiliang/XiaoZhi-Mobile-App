package com.lchuang.xiaozhimobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualIntentResolverTest {
    private var nowMs = 1_000L
    private val store = ScreenContextStore(ttlMs = 5_000L, clockMs = { nowMs })
    private val context = store.publish(
        packageName = "com.example.video",
        windowFingerprint = "com.example.video:11",
        root = ScreenNode(id = "root"),
    )
    private val resolver = ContextualIntentResolver(store)

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

        val first = resolver.resolve("第一个", context, candidates)
        val second = resolver.resolve("第二个", context, candidates)

        assertEquals(ContextResolutionConfidence.HIGH, first.confidence)
        assertEquals("one", first.candidate?.id)
        assertEquals(ContextResolutionConfidence.HIGH, second.confidence)
        assertEquals("two", second.candidate?.id)
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
        val recentThatResult = resolver.resolve("返回刚才那个", context, listOf(current, recent), history)

        assertEquals(ContextResolutionConfidence.MEDIUM, thisResult.confidence)
        assertEquals(ContextResolutionConfidence.MEDIUM, thatResult.confidence)
        assertEquals(ContextResolutionConfidence.HIGH, recentThatResult.confidence)
        assertEquals("recent", recentThatResult.candidate?.id)
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
    fun `current video asks for clarification when current and session videos differ`() {
        val current = candidate("current-video", "当前视频", kind = ContextTargetKind.VIDEO)
        val session = candidate("session-video", "会话视频", kind = ContextTargetKind.VIDEO)

        val result = resolver.resolve(
            "当前视频",
            context,
            candidates = emptyList(),
            history = ContextualHistory(currentTarget = current, sessionTarget = session),
        )

        assertEquals(ContextResolutionConfidence.MEDIUM, result.confidence)
        assertNull(result.candidate)
        assertEquals(listOf("current-video", "session-video"), result.candidates.map(ContextCandidate::id))
    }

    @Test
    fun `pronouns resolve from the assistant prompt referent`() {
        val promptTarget = candidate("prompt-target", "小白刚才提到的目标")
        val history = ContextualHistory(assistantPromptTarget = promptTarget)

        val male = resolver.resolve("打开他", context, history = history)
        val female = resolver.resolve("打开她", context, history = history)

        assertEquals(ContextResolutionConfidence.HIGH, male.confidence)
        assertEquals("prompt-target", male.candidate?.id)
        assertEquals(ContextResolutionConfidence.HIGH, female.confidence)
        assertEquals("prompt-target", female.candidate?.id)
    }

    @Test
    fun `current app target can resolve a context reference without a candidate list`() {
        val appTarget = candidate("app-target", "当前应用目标")

        val result = resolver.resolve(
            "打开这个",
            context,
            history = ContextualHistory(currentAppTarget = appTarget),
        )

        assertEquals(ContextResolutionConfidence.HIGH, result.confidence)
        assertEquals("app-target", result.candidate?.id)
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
    fun `expired context cannot resolve a previously captured candidate`() {
        nowMs = context.capturedAtMs + 5_000L

        val result = resolver.resolve("第一个", context, listOf(candidate("one", "项目")))

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    @Test
    fun `invalidated context cannot resolve a previously captured candidate`() {
        store.invalidate()

        val result = resolver.resolve("第一个", context, listOf(candidate("one", "项目")))

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    @Test
    fun `same window replacement invalidates the old generation`() {
        store.publish(
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
            root = ScreenNode(id = "new-root"),
        )

        val result = resolver.resolve("第一个", context, listOf(candidate("one", "项目")))

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    @Test
    fun `next cannot use a history position when its target is absent from current candidates`() {
        val staleAnchor = candidate("old", "旧项目", position = 1)
        val nextCurrent = candidate("new", "新项目", position = 2)

        val result = resolver.resolve(
            "下一个",
            context,
            candidates = listOf(nextCurrent),
            history = ContextualHistory(currentTarget = staleAnchor),
        )

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    @Test
    fun `ordinal phrases are bounded and conflicting ordinals do not guess`() {
        val candidates = listOf(
            candidate("one", "项目", position = 1),
            candidate("two", "项目", position = 2),
        )

        val firstTime = resolver.resolve("第一次打开这个页面", context, candidates)
        val conflict = resolver.resolve("第一和第二个都打开", context, candidates)
        val negated = resolver.resolve("不要第一个", context, candidates)

        assertEquals(ContextResolutionConfidence.LOW, firstTime.confidence)
        assertEquals(ContextResolutionConfidence.LOW, conflict.confidence)
        assertEquals(ContextResolutionConfidence.LOW, negated.confidence)
    }

    @Test
    fun `next keyword is bounded and does not match next time`() {
        val candidates = listOf(
            candidate("one", "第一项", position = 1),
            candidate("two", "第二项", position = 2),
        )
        val history = ContextualHistory(currentTarget = candidates[0])

        val result = resolver.resolve("下次提醒我", context, candidates, history)

        assertEquals(ContextResolutionConfidence.LOW, result.confidence)
        assertNull(result.candidate)
    }

    @Test
    fun `context resolution constructor rejects invalid confidence invariants`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextResolution(confidence = ContextResolutionConfidence.HIGH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextResolution(confidence = ContextResolutionConfidence.MEDIUM)
        }
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
