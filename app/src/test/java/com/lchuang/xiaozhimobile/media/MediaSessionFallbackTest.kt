package com.lchuang.xiaozhimobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionFallbackTest {
    private val activeSession = ActiveMediaSession("com.example.player")

    @Test
    fun dispatchesPlaybackControlsOnlyForAnActiveSessionWithoutDedicatedAdapter() {
        val dispatched = mutableListOf<MediaKeyAction>()
        val fallback = fallback(active = activeSession, dispatched = dispatched)

        val play = fallback.execute(MusicIntent(MusicIntentType.PLAY), dedicatedAdapterAvailable = false)
        val pause = fallback.execute(MusicIntent(MusicIntentType.PAUSE), dedicatedAdapterAvailable = false)
        val previous = fallback.execute(MusicIntent(MusicIntentType.PREVIOUS), dedicatedAdapterAvailable = false)
        val next = fallback.execute(MusicIntent(MusicIntentType.NEXT), dedicatedAdapterAvailable = false)

        assertEquals(MediaSessionFallbackCode.DISPATCHED, play.code)
        assertEquals(MediaSessionFallbackCode.DISPATCHED, pause.code)
        assertEquals(MediaSessionFallbackCode.DISPATCHED, previous.code)
        assertEquals(MediaSessionFallbackCode.DISPATCHED, next.code)
        assertEquals(
            listOf(MediaKeyAction.PLAY, MediaKeyAction.PAUSE, MediaKeyAction.PREVIOUS, MediaKeyAction.NEXT),
            dispatched,
        )
        assertEquals(activeSession.packageName, next.sessionPackage)
    }

    @Test
    fun dedicatedAdapterWinsAndFallbackDoesNotDuplicateTheAction() {
        val dispatched = mutableListOf<MediaKeyAction>()
        val result = fallback(active = activeSession, dispatched = dispatched)
            .execute(MusicIntent(MusicIntentType.NEXT), dedicatedAdapterAvailable = true)

        assertEquals(MediaSessionFallbackCode.DELEGATE_TO_ADAPTER, result.code)
        assertTrue(dispatched.isEmpty())
        assertFalse(result.success)
    }

    @Test
    fun noActiveSessionDoesNotSendMediaKeys() {
        val dispatched = mutableListOf<MediaKeyAction>()
        val result = fallback(active = null, dispatched = dispatched)
            .execute(MusicIntent(MusicIntentType.PLAY), dedicatedAdapterAvailable = false)

        assertEquals(MediaSessionFallbackCode.NO_ACTIVE_SESSION, result.code)
        assertTrue(dispatched.isEmpty())
        assertFalse(result.success)
    }

    @Test
    fun searchAndSelectionAreNotFlattenedIntoMediaKeys() {
        val dispatched = mutableListOf<MediaKeyAction>()
        val fallback = fallback(active = activeSession, dispatched = dispatched)

        val search = fallback.execute(
            MusicIntent(MusicIntentType.SEARCH, MusicSearchQuery(song = "晴天")),
            dedicatedAdapterAvailable = false,
        )
        val select = fallback.execute(
            MusicIntent(MusicIntentType.SELECT_FIRST),
            dedicatedAdapterAvailable = false,
        )

        assertEquals(MediaSessionFallbackCode.UNSUPPORTED_INTENT, search.code)
        assertEquals(MediaSessionFallbackCode.UNSUPPORTED_INTENT, select.code)
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun dispatchFailureIsReportedAndNotClaimedAsSuccess() {
        val fallback = MediaSessionFallback(
            activeSession = { activeSession },
            mediaKeyDispatcher = { false },
        )

        val result = fallback.execute(MusicIntent(MusicIntentType.PAUSE), dedicatedAdapterAvailable = false)

        assertEquals(MediaSessionFallbackCode.DISPATCH_FAILED, result.code)
        assertFalse(result.success)
    }

    private fun fallback(
        active: ActiveMediaSession?,
        dispatched: MutableList<MediaKeyAction>,
    ) = MediaSessionFallback(
        activeSession = { active },
        mediaKeyDispatcher = { action ->
            dispatched += action
            true
        },
    )
}
