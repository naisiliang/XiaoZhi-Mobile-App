package com.lchuang.xiaozhimobile.conversation

import android.content.Context
import com.lchuang.xiaozhimobile.SettingsStore

/**
 * Process-local session boundary shared by the foreground UI and WakeService.
 * The manager serializes mutations; observers receive every persisted snapshot.
 */
class ConversationSessionEventSource(
    val repository: ConversationSessionRepository,
    ids: () -> String = { java.util.UUID.randomUUID().toString() },
    now: () -> Long = { System.currentTimeMillis() },
    initialSession: ConversationSession? = null,
    assistantName: String = "小智",
) {
    private val observers = LinkedHashSet<(ConversationSession) -> Unit>()
    private val observerLock = Any()

    val manager = ConversationSessionManager(
        repository = repository,
        ids = ids,
        now = now,
        initialSession = initialSession,
        onChanged = ::publish,
        assistantName = assistantName,
    )

    fun currentSession(): ConversationSession? = manager.currentSession()

    fun startWakeSession(): ConversationSession = manager.startWakeSession()

    fun appendUser(text: String): ConversationSession = manager.appendUser(text)

    fun appendAssistant(text: String): ConversationSession = manager.appendAssistant(text)

    fun appendSystemAction(text: String): ConversationSession = manager.appendSystemAction(text)

    fun appendSystemResult(text: String): ConversationSession = manager.appendSystemResult(text)

    fun appendConfirmation(text: String): ConversationSession = manager.appendConfirmation(text)

    fun endSession(reason: String? = null): ConversationSession? = manager.endSession(reason)

    fun addObserver(observer: (ConversationSession) -> Unit) {
        synchronized(observerLock) { observers += observer }
        val current = manager.currentSession()
        current?.let(observer)
    }

    fun removeObserver(observer: (ConversationSession) -> Unit) {
        synchronized(observerLock) { observers -= observer }
    }

    private fun publish(session: ConversationSession) {
        val snapshot = synchronized(observerLock) { observers.toList() }
        snapshot.forEach { it(session) }
    }
}

object ConversationSessionStore {
    private val lock = Any()
    private var source: ConversationSessionEventSource? = null
    private var sharedRepository: ConversationRepository? = null

    fun source(context: Context): ConversationSessionEventSource = synchronized(lock) {
        source ?: createSource(context).also { created ->
            source = created
        }
    }

    fun repository(context: Context): ConversationRepository {
        source(context)
        return synchronized(lock) { checkNotNull(sharedRepository) }
    }

    fun manager(context: Context): ConversationSessionManager = source(context).manager

    fun observe(context: Context, observer: (ConversationSession) -> Unit) {
        source(context).addObserver(observer)
    }

    fun removeObserver(context: Context, observer: (ConversationSession) -> Unit) {
        source(context).removeObserver(observer)
    }

    private fun createSource(context: Context): ConversationSessionEventSource {
        val appContext = context.applicationContext
        val repository = ConversationRepository(appContext)
        sharedRepository = repository
        val current = repository.loadCurrent()
        return ConversationSessionEventSource(
            repository = repository,
            initialSession = current,
            assistantName = SettingsStore(appContext).assistantName,
        )
    }
}
