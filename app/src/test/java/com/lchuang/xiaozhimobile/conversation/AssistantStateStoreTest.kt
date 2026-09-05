package com.lchuang.xiaozhimobile.conversation

import com.lchuang.xiaozhimobile.ConversationState
import com.lchuang.xiaozhimobile.AssistantOverlayRender
import com.lchuang.xiaozhimobile.applyLegacyOverlayState
import com.lchuang.xiaozhimobile.toOverlayRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AssistantStateStoreTest {
    @Test
    fun processStoreSharesIdentityAndLiveObserversAcrossOwners() {
        val mainActivityStore = AssistantStateStoreProvider.instance()
        val wakeServiceStore = AssistantStateStoreProvider.instance()
        val observed = mutableListOf<AssistantState>()
        val observer: (AssistantState) -> Unit = { observed += it }

        assertSame(mainActivityStore, wakeServiceStore)
        wakeServiceStore.addObserver(observer)
        try {
            mainActivityStore.onAudioCaptureStarted()
            assertEquals(listOf(AssistantState.LISTENING), observed)
            assertEquals(AssistantState.LISTENING, wakeServiceStore.current)
        } finally {
            wakeServiceStore.removeObserver(observer)
            wakeServiceStore.onConversationEnded()
        }

        mainActivityStore.onTtsStarted()
        assertEquals(listOf(AssistantState.LISTENING), observed)
        assertEquals(AssistantState.SPEAKING, wakeServiceStore.current)
        wakeServiceStore.onConversationEnded()
    }

    @Test
    fun `wake detection waits for capture before listening and tts enters speaking`() {
        val store = AssistantStateStore()

        store.onWakeDetected()
        assertNotEquals(AssistantState.LISTENING, store.current)

        store.onAudioCaptureStarted()
        assertEquals(AssistantState.LISTENING, store.current)

        store.onTtsStarted()
        assertEquals(AssistantState.SPEAKING, store.current)
    }

    @Test
    fun `ready to listen stays non listening and renders its legacy resume state`() {
        val store = AssistantStateStore()

        val viewState = store.applyLegacyOverlayState(ConversationState.READY_TO_LISTEN)

        assertEquals(ConversationState.READY_TO_LISTEN, viewState)
        assertEquals(AssistantState.WAITING_WAKE, store.current)
    }

    @Test
    fun `exiting renders legacy exit state until the real conversation end event`() {
        val store = AssistantStateStore()
        store.onAudioCaptureStarted()

        val viewState = store.applyLegacyOverlayState(ConversationState.EXITING)

        assertEquals(ConversationState.EXITING, viewState)
        assertEquals(AssistantState.LISTENING, store.current)

        store.onConversationEnded()
        assertEquals(AssistantState.WAITING_WAKE, store.current)
    }

    @Test
    fun `confirmation is an exact store state with a dedicated outbound render decision`() {
        val store = AssistantStateStore()

        store.onConfirmationRequired()

        assertEquals(AssistantState.WAITING_CONFIRMATION, store.current)
        assertEquals(AssistantOverlayRender.Confirmation, store.current.toOverlayRender())
        assertEquals(ConversationState.IDLE_WAKE, AssistantOverlayRender.Confirmation.legacyState)
    }

    @Test
    fun `ready to listen and exiting preserve completion notification at waiting wake`() {
        val observed = mutableListOf<AssistantState>()
        val store = AssistantStateStore()
        store.addObserver { observed += it }

        assertEquals(ConversationState.READY_TO_LISTEN, store.applyLegacyOverlayState(ConversationState.READY_TO_LISTEN))
        assertEquals(ConversationState.EXITING, store.applyLegacyOverlayState(ConversationState.EXITING))
        store.onConversationEnded()

        assertEquals(AssistantState.WAITING_WAKE, store.current)
        assertEquals(listOf(AssistantState.WAITING_WAKE), observed)
    }

    @Test
    fun `legacy idle wake completes the resume and exit compatibility path`() {
        val observed = mutableListOf<AssistantState>()
        val store = AssistantStateStore()
        store.addObserver { observed += it }

        assertEquals(ConversationState.READY_TO_LISTEN, store.applyLegacyOverlayState(ConversationState.READY_TO_LISTEN))
        assertEquals(ConversationState.EXITING, store.applyLegacyOverlayState(ConversationState.EXITING))
        assertEquals(ConversationState.IDLE_WAKE, store.applyLegacyOverlayState(ConversationState.IDLE_WAKE))

        assertEquals(AssistantState.WAITING_WAKE, store.current)
        assertEquals(listOf(AssistantState.WAITING_WAKE), observed)
    }
}
