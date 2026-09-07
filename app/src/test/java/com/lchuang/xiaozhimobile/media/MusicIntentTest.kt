package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicIntentTest {
    private val resolver = MusicIntentResolver()

    @Test
    fun resolvesOpenPlayPauseAndTrackControlsIntoTypedIntents() {
        assertResolved("打开音乐", MusicIntent(MusicIntentType.OPEN))
        assertResolved("继续播放", MusicIntent(MusicIntentType.PLAY))
        assertResolved("暂停音乐", MusicIntent(MusicIntentType.PAUSE))
        assertResolved("上一首", MusicIntent(MusicIntentType.PREVIOUS))
        assertResolved("下一首", MusicIntent(MusicIntentType.NEXT))
    }

    @Test
    fun resolvesSongAndArtistSearchWithoutFlatteningArgumentsIntoClicks() {
        assertResolved(
            "搜索周杰伦的晴天",
            MusicIntent(
                type = MusicIntentType.SEARCH,
                query = MusicSearchQuery(song = "晴天", artist = "周杰伦"),
            ),
        )
        assertResolved(
            "播放歌曲稻香，歌手周杰伦",
            MusicIntent(
                type = MusicIntentType.SEARCH,
                query = MusicSearchQuery(song = "稻香", artist = "周杰伦"),
            ),
        )
    }

    @Test
    fun resolvesFirstAndSecondResultSelection() {
        assertResolved("选第一首", MusicIntent(MusicIntentType.SELECT_FIRST))
        assertResolved("选择第二个结果", MusicIntent(MusicIntentType.SELECT_SECOND))
    }

    @Test
    fun conflictingOrUnderspecifiedSelectionRequiresClarification() {
        assertTrue(resolver.resolve("上一首还是下一首") is MusicIntentResolution.NeedsClarification)
        assertTrue(resolver.resolve("选前两个结果") is MusicIntentResolution.NeedsClarification)
        assertTrue(resolver.resolve("播放") is MusicIntentResolution.NeedsClarification)
    }

    @Test
    fun emptyAndUnsupportedCommandsFailClosed() {
        assertTrue(resolver.resolve("   ") is MusicIntentResolution.Empty)
        assertTrue(resolver.resolve("打开相机") is MusicIntentResolution.Unsupported)
        assertTrue(resolver.resolve("搜索歌曲") is MusicIntentResolution.NeedsClarification)
        assertTrue(resolver.resolve("播放歌曲") is MusicIntentResolution.NeedsClarification)
    }

    @Test
    fun searchIntentRequiresAtLeastOneNonBlankField() {
        assertEquals(null, runCatching {
            MusicIntent(MusicIntentType.SEARCH, MusicSearchQuery())
        }.getOrNull())
        assertEquals(null, runCatching {
            MusicIntent(MusicIntentType.PLAY, MusicSearchQuery(song = "晴天"))
        }.getOrNull())
    }

    @Test
    fun genericMusicAdapterReturnsOnlyContextBoundSemanticProposal() {
        val context = context()
        val target = ContextCandidate(
            id = "play-button",
            label = "播放",
            generationId = context.generationId,
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
        )
        val app = MusicApp("com.example.music", "示例音乐", MusicAppKind.OTHER)
        val adapter = object : MusicAppAdapter {
            override val id: String = "example"

            override fun canHandle(candidate: MusicApp): Boolean = candidate == app

            override fun propose(
                intent: MusicIntent,
                screen: ScreenContext,
            ): MusicAdapterResolution = MusicAdapterResolution.Proposed(
                intent = intent,
                proposal = UiActionProposal(UiActionType.CLICK, screen, target),
            )
        }

        val result = adapter.propose(MusicIntent(MusicIntentType.PLAY), context)

        assertTrue(result is MusicAdapterResolution.Proposed)
        val proposal = (result as MusicAdapterResolution.Proposed).proposal
        assertEquals(context, proposal.context)
        assertEquals(target.id, proposal.target?.id)
    }

    @Test
    fun adapterCanAskForSemanticResultClarificationWithoutExecuting() {
        val context = context()
        val first = ContextCandidate(
            id = "result-1",
            label = "晴天",
            position = 1,
            generationId = context.generationId,
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
        )
        val second = first.copy(id = "result-2", position = 2)

        val clarification = MusicAdapterResolution.NeedsClarification(
            candidates = listOf(first, second),
            reason = MusicClarificationReason.MULTIPLE_RESULTS,
        )

        assertTrue(clarification.candidates.size == 2)
        assertEquals(MusicClarificationReason.MULTIPLE_RESULTS, clarification.reason)
    }

    private fun assertResolved(command: String, expected: MusicIntent) {
        val result = resolver.resolve(command)
        assertTrue("Expected a resolved intent for '$command'", result is MusicIntentResolution.Resolved)
        assertEquals(expected, (result as MusicIntentResolution.Resolved).intent)
    }

    private fun context() = ScreenContext(
        generationId = GenerationId(7L),
        packageName = "com.example.music",
        windowFingerprint = "window-7",
        root = ScreenNode("root"),
        capturedAtMs = 100L,
    )
}
