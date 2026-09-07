package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.media.adapters.QishuiMusicAdapter
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QishuiMusicAdapterTest {
    private val adapter = QishuiMusicAdapter()

    @Test
    fun resolvesEnglishAndChineseQishuiSemanticControls() {
        val context = fixture()
        val play = adapter.propose(MusicIntent(MusicIntentType.PLAY), context)
        val previous = adapter.propose(MusicIntent(MusicIntentType.PREVIOUS), context)
        val next = adapter.propose(MusicIntent(MusicIntentType.NEXT), context)
        val search = adapter.propose(
            MusicIntent(MusicIntentType.SEARCH, MusicSearchQuery(artist = "周杰伦")),
            context,
        )
        val searchProposal = search as MusicAdapterResolution.Proposed

        assertEquals("play", (play as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("previous", (previous as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("next", (next as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("search", searchProposal.proposal.target?.id)
        assertEquals(UiActionType.CLICK, searchProposal.proposal.action)
    }

    @Test
    fun selectsFirstResultOnlyFromTheCurrentQishuiSnapshot() {
        val context = fixture()
        val result = adapter.propose(MusicIntent(MusicIntentType.SELECT_FIRST), context)

        assertTrue(result is MusicAdapterResolution.Proposed)
        val proposed = result as MusicAdapterResolution.Proposed
        assertEquals("qishui-result-1", proposed.proposal.target?.id)
        assertEquals(UiActionType.SELECT, proposed.proposal.action)
        assertEquals(context.generationId, proposed.proposal.target?.generationId)
    }

    @Test
    fun packageMismatchIsUnsupported() {
        val result = adapter.propose(
            MusicIntent(MusicIntentType.PAUSE),
            fixture(packageName = "com.netease.cloudmusic"),
        )

        assertTrue(result is MusicAdapterResolution.Unsupported)
    }

    private fun fixture(
        packageName: String = "com.luna.music",
        generation: Long = 11L,
    ): ScreenContext = ScreenContext(
        generationId = GenerationId(generation),
        packageName = packageName,
        windowFingerprint = "qishui-window-$generation",
        root = ScreenNode(
            "root",
            children = listOf(
                ScreenNode("search", role = "search", text = "Search", clickable = true),
                ScreenNode("play", role = "player_play_button", contentDescription = "继续", clickable = true),
                ScreenNode("pause", role = "player_pause_button", contentDescription = "Pause", clickable = true),
                ScreenNode("previous", role = "player_prev", contentDescription = "上一曲", clickable = true),
                ScreenNode("next", role = "player_next", contentDescription = "Next", clickable = true),
                ScreenNode("qishui-result-1", role = "track_item", text = "晴天", clickable = true),
                ScreenNode("qishui-result-2", role = "track_item", text = "稻香", clickable = true),
            ),
        ),
        capturedAtMs = generation,
    )
}
