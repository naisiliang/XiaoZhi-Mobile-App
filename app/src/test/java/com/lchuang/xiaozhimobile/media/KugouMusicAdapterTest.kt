package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.media.adapters.KugouMusicAdapter
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouMusicAdapterTest {
    private val adapter = KugouMusicAdapter()

    @Test
    fun resolvesKugouSearchPauseAndNextByRoleTokens() {
        val context = fixture()
        val search = adapter.propose(
            MusicIntent(MusicIntentType.SEARCH, MusicSearchQuery(song = "晴天")),
            context,
        )
        val pause = adapter.propose(MusicIntent(MusicIntentType.PAUSE), context)
        val next = adapter.propose(MusicIntent(MusicIntentType.NEXT), context)
        val nextProposal = next as MusicAdapterResolution.Proposed

        assertEquals("search", (search as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("pause", (pause as MusicAdapterResolution.Proposed).proposal.target?.id)
        assertEquals("next", nextProposal.proposal.target?.id)
        assertEquals(UiActionType.CLICK, nextProposal.proposal.action)
    }

    @Test
    fun selectsSecondKugouResultWithCurrentGeneration() {
        val context = fixture(generation = 21L)
        val result = adapter.propose(MusicIntent(MusicIntentType.SELECT_SECOND), context)

        assertTrue(result is MusicAdapterResolution.Proposed)
        val proposed = result as MusicAdapterResolution.Proposed
        assertEquals("kugou-result-2", proposed.proposal.target?.id)
        assertEquals(21L, proposed.proposal.target?.generationId?.value)
        assertEquals(UiActionType.SELECT, proposed.proposal.action)
    }

    @Test
    fun refusesWrongPackage() {
        val result = adapter.propose(
            MusicIntent(MusicIntentType.PLAY),
            fixture(packageName = "com.tencent.qqmusic"),
        )

        assertTrue(result is MusicAdapterResolution.Unsupported)
        assertTrue(adapter.canHandle(MusicApp("com.kugou.android", "酷狗音乐", MusicAppKind.KUGOU)))
    }

    private fun fixture(
        packageName: String = "com.kugou.android",
        generation: Long = 20L,
    ): ScreenContext = ScreenContext(
        generationId = GenerationId(generation),
        packageName = packageName,
        windowFingerprint = "kugou-window-$generation",
        root = ScreenNode(
            "root",
            children = listOf(
                ScreenNode("search", role = "action_search", contentDescription = "搜索", clickable = true),
                ScreenNode("play", role = "media_play", text = "Play", clickable = true),
                ScreenNode("pause", role = "media_pause", text = "Pause", clickable = true),
                ScreenNode("previous", role = "media_previous", text = "Previous", clickable = true),
                ScreenNode("next", role = "media_next", text = "Next", clickable = true),
                ScreenNode("kugou-result-1", role = "music_list_item", text = "晴天", clickable = true),
                ScreenNode("kugou-result-2", role = "music_list_item", text = "稻香", clickable = true),
            ),
        ),
        capturedAtMs = generation,
    )
}
