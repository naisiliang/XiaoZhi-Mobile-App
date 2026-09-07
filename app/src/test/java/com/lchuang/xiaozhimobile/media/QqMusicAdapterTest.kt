package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.media.adapters.QqMusicAdapter
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QqMusicAdapterTest {
    private val adapter = QqMusicAdapter()

    @Test
    fun resolvesQqMusicToolbarAndPlaybackControls() {
        val context = fixture()
        val search = adapter.propose(
            MusicIntent(MusicIntentType.SEARCH, MusicSearchQuery(song = "晴天", artist = "周杰伦")),
            context,
        )
        val play = adapter.propose(MusicIntent(MusicIntentType.PLAY), context)
        val pause = adapter.propose(MusicIntent(MusicIntentType.PAUSE), context)
        val previous = adapter.propose(MusicIntent(MusicIntentType.PREVIOUS), context)
        val next = adapter.propose(MusicIntent(MusicIntentType.NEXT), context)
        val nextProposal = next as MusicAdapterResolution.Proposed

        assertEquals("search", (search as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("play", (play as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("pause", (pause as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("previous", (previous as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("next", nextProposal.proposal.target?.id)
        assertEquals(UiActionType.CLICK, nextProposal.proposal.action)
    }

    @Test
    fun resolvesFirstAndSecondQqSearchResults() {
        val context = fixture(generation = 32L)
        val first = adapter.propose(MusicIntent(MusicIntentType.SELECT_FIRST), context)
        val second = adapter.propose(MusicIntent(MusicIntentType.SELECT_SECOND), context)

        assertEquals("qq-result-1", (first as MusicAdapterResolution.Proposed).proposal.target?.id)
        val secondProposal = second as MusicAdapterResolution.Proposed
        assertEquals("qq-result-2", secondProposal.proposal.target?.id)
        assertEquals(UiActionType.SELECT, secondProposal.proposal.action)
        assertEquals(32L, secondProposal.proposal.target?.generationId?.value)
    }

    @Test
    fun refusesWrongPackageAndMissingSecondResult() {
        val wrongPackage = adapter.propose(
            MusicIntent(MusicIntentType.PLAY),
            fixture(packageName = "com.example.player"),
        )
        val noSecond = adapter.propose(
            MusicIntent(MusicIntentType.SELECT_SECOND),
            fixture(includeSecond = false),
        )

        assertTrue(wrongPackage is MusicAdapterResolution.Unsupported)
        assertTrue(noSecond is MusicAdapterResolution.Unsupported)
        assertTrue(adapter.canHandle(MusicApp("com.tencent.qqmusic", "QQ音乐", MusicAppKind.QQ_MUSIC)))
    }

    private fun fixture(
        packageName: String = "com.tencent.qqmusic",
        generation: Long = 31L,
        includeSecond: Boolean = true,
    ): ScreenContext {
        val results = listOfNotNull(
            ScreenNode("qq-result-1", role = "search_result_item", text = "晴天", clickable = true),
            ScreenNode("qq-result-2", role = "search_result_item", text = "稻香", clickable = true)
                .takeIf { includeSecond },
        )
        return ScreenContext(
            generationId = GenerationId(generation),
            packageName = packageName,
            windowFingerprint = "qq-window-$generation",
            root = ScreenNode(
                "root",
                children = listOf(
                    ScreenNode("search", role = "toolbar_search", text = "搜索", clickable = true),
                    ScreenNode("play", role = "play_button", text = "Play", clickable = true),
                    ScreenNode("pause", role = "pause_button", text = "Pause", clickable = true),
                    ScreenNode("previous", role = "skip_previous", text = "上一首", clickable = true),
                    ScreenNode("next", role = "skip_next", text = "下一首", clickable = true),
                ) + results,
            ),
            capturedAtMs = generation,
        )
    }
}
