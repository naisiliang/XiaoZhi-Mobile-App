package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.media.adapters.NetEaseMusicAdapter
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseMusicAdapterTest {
    private val app = MusicApp("com.netease.cloudmusic", "网易云音乐", MusicAppKind.NETEASE)
    private val adapter = NetEaseMusicAdapter()

    @Test
    fun resolvesControlsAndKeepsSearchFieldsTyped() {
        val context = fixture()
        val result = adapter.propose(
            MusicIntent(
                type = MusicIntentType.SEARCH,
                query = MusicSearchQuery(song = "晴天", artist = "周杰伦"),
            ),
            context,
        )

        assertTrue(result is MusicAdapterResolution.Proposed)
        val proposed = result as MusicAdapterResolution.Proposed
        assertEquals(MusicIntentType.SEARCH, proposed.intent.type)
        assertEquals("晴天", proposed.intent.query?.song)
        assertEquals("周杰伦", proposed.intent.query?.artist)
        assertEquals("search", proposed.proposal.target?.id)
        assertEquals(UiActionType.CLICK, proposed.proposal.action)
    }

    @Test
    fun resolvesPauseAndSecondResultAsSemanticProposals() {
        val context = fixture()
        val pause = adapter.propose(MusicIntent(MusicIntentType.PAUSE), context)
        val second = adapter.propose(MusicIntent(MusicIntentType.SELECT_SECOND), context)
        val pauseProposal = pause as MusicAdapterResolution.Proposed
        val secondProposal = second as MusicAdapterResolution.Proposed

        assertEquals("pause", pauseProposal.proposal.target?.id)
        assertEquals(UiActionType.CLICK, pauseProposal.proposal.action)
        assertEquals("result-2", secondProposal.proposal.target?.id)
        assertEquals(UiActionType.SELECT, secondProposal.proposal.action)
    }

    @Test
    fun refusesOtherPackagesAndDoesNotFakeOpenAsAUiClick() {
        val context = fixture(packageName = "com.example.other")

        assertTrue(adapter.propose(MusicIntent(MusicIntentType.PLAY), context) is MusicAdapterResolution.Unsupported)
        assertTrue(adapter.propose(MusicIntent(MusicIntentType.OPEN), fixture()) is MusicAdapterResolution.Unsupported)
        assertTrue(adapter.canHandle(app))
        assertTrue(!adapter.canHandle(MusicApp("com.luna.music", "汽水音乐", MusicAppKind.QISHUI)))
    }

    @Test
    fun duplicateSearchControlsRequireClarificationAndProposalBindsGeneration() {
        val duplicate = fixture(
            extra = ScreenNode("search-duplicate", role = "search_button", text = "搜索", clickable = true),
        )
        val result = adapter.propose(
            MusicIntent(
                MusicIntentType.SEARCH,
                MusicSearchQuery(song = "晴天"),
            ),
            duplicate,
        )

        assertTrue(result is MusicAdapterResolution.NeedsClarification)
        assertEquals(MusicClarificationReason.AMBIGUOUS_TARGET, (result as MusicAdapterResolution.NeedsClarification).reason)

        val first = adapter.propose(MusicIntent(MusicIntentType.PAUSE), fixture(generation = 1L))
        val second = adapter.propose(MusicIntent(MusicIntentType.PAUSE), fixture(generation = 2L))
        assertNotEquals(
            (first as MusicAdapterResolution.Proposed).proposal.context,
            (second as MusicAdapterResolution.Proposed).proposal.context,
        )
        assertEquals(2L, second.proposal.context.generationId.value)
        assertEquals(2L, second.proposal.target?.generationId?.value)
    }

    private fun fixture(
        packageName: String = app.packageName,
        generation: Long = 7L,
        extra: ScreenNode? = null,
    ): ScreenContext {
        val children = listOf(
            ScreenNode("search", role = "search_button", contentDescription = "搜索", clickable = true),
            ScreenNode("play", role = "play_button", contentDescription = "播放", clickable = true),
            ScreenNode("pause", role = "pause_button", contentDescription = "暂停", clickable = true),
            ScreenNode("previous", role = "previous_button", contentDescription = "上一首", clickable = true),
            ScreenNode("next", role = "next_button", contentDescription = "下一首", clickable = true),
            ScreenNode("result-1", role = "song_result", text = "晴天", clickable = true),
            ScreenNode("result-2", role = "song_result", text = "稻香", clickable = true),
        ) + listOfNotNull(extra)
        return ScreenContext(
            generationId = GenerationId(generation),
            packageName = packageName,
            windowFingerprint = "netease-window-$generation",
            root = ScreenNode("root", children = children),
            capturedAtMs = generation,
        )
    }
}
