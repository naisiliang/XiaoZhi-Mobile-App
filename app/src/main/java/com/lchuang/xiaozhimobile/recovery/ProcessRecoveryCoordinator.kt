package com.lchuang.xiaozhimobile.recovery

import com.lchuang.xiaozhimobile.artifacts.Artifact
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.conversation.ConversationSession
import com.lchuang.xiaozhimobile.conversation.ConversationSessionRepository
import com.lchuang.xiaozhimobile.messaging.MessagingCoordinator
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.vision.VisionSessionAuthorization

const val PROCESS_RESTART_INVALIDATION_REASON = "INVALIDATED_ON_PROCESS_RESTART"

enum class ProcessBoundResource {
    PENDING_MESSAGE_SEND,
    CONFIRMATION_TOKEN,
    PENDING_UI_ACTION,
    SCREEN_CONTEXT,
    MEDIA_PROJECTION,
}

/**
 * Process-owned state is invalidated through narrow callbacks so this coordinator does not retain
 * message bodies, screen trees, UI nodes, or platform projection handles.
 */
data class ProcessRecoveryInvalidators(
    val pendingMessageSend: () -> Unit = {},
    val confirmationToken: () -> Unit = {},
    val pendingUiAction: () -> Unit = {},
    val screenContext: () -> Unit = {},
    val mediaProjection: () -> Unit = {},
) {
    internal fun invalidate(): InvalidationResult {
        val failures = linkedSetOf<ProcessBoundResource>()
        val actions = listOf(
            ProcessBoundResource.PENDING_MESSAGE_SEND to pendingMessageSend,
            ProcessBoundResource.CONFIRMATION_TOKEN to confirmationToken,
            ProcessBoundResource.PENDING_UI_ACTION to pendingUiAction,
            ProcessBoundResource.SCREEN_CONTEXT to screenContext,
            ProcessBoundResource.MEDIA_PROJECTION to mediaProjection,
        )
        actions.forEach { (resource, action) ->
            try {
                action()
            } catch (_: Exception) {
                // Continue invalidating the remaining resources and expose the failure in the
                // safe recovery snapshot. No stale resource is treated as valid after a failure.
                failures += resource
            }
        }
        return InvalidationResult(
            resources = actions.mapTo(linkedSetOf()) { it.first },
            failures = failures,
        )
    }

    internal data class InvalidationResult(
        val resources: Set<ProcessBoundResource>,
        val failures: Set<ProcessBoundResource>,
    )

    companion object {
        /**
         * Bind the process-death boundary to the concrete in-memory owners.
         * Pending UI actions are context-bound, so clearing the screen store
         * invalidates both the proposal and its old snapshot.
         */
        fun forRuntime(
            messagingCoordinator: MessagingCoordinator,
            screenContextStore: ScreenContextStore,
            visionSessionAuthorization: VisionSessionAuthorization,
            mediaProjection: () -> Unit = visionSessionAuthorization::invalidate,
        ): ProcessRecoveryInvalidators = ProcessRecoveryInvalidators(
            pendingMessageSend = messagingCoordinator::invalidateOnProcessRestart,
            confirmationToken = messagingCoordinator::invalidateOnProcessRestart,
            pendingUiAction = screenContextStore::invalidate,
            screenContext = screenContextStore::invalidate,
            mediaProjection = mediaProjection,
        )
    }
}

data class ProcessRecoverySnapshot(
    val recoveredSessions: List<ConversationSession>,
    val invalidatedActiveSessions: List<ConversationSession>,
    val recoveredArtifacts: List<Artifact>,
    val interruptedArtifacts: List<Artifact>,
    val invalidatedResources: Set<ProcessBoundResource>,
    val invalidationFailures: Set<ProcessBoundResource>,
    val invalidationReason: String = PROCESS_RESTART_INVALIDATION_REASON,
)

enum class ArtifactContinuationCode {
    USER_CONFIRMATION_REQUIRED,
    CONTINUE_AUTHORIZED,
    NOT_FOUND,
}

data class ArtifactContinuationResult(
    val code: ArtifactContinuationCode,
    val artifact: Artifact? = null,
) {
    val continuationAuthorized: Boolean
        get() = code == ArtifactContinuationCode.CONTINUE_AUTHORIZED

    init {
        if (code == ArtifactContinuationCode.NOT_FOUND) {
            require(artifact == null) { "a missing interrupted artifact cannot carry metadata" }
        }
        if (code == ArtifactContinuationCode.CONTINUE_AUTHORIZED) {
            require(artifact != null) { "only an existing interrupted artifact can be continuation-authorized" }
        }
    }
}

/**
 * Performs the one-time process-death boundary. Durable completed data is read back, active
 * sessions are closed as history, staged artifacts become user-resumable interruptions, and all
 * process-bound actions are invalidated without attempting to resume them automatically.
 */
class ProcessRecoveryCoordinator(
    private val sessionRepository: ConversationSessionRepository,
    private val artifactRepository: ArtifactRepository,
    private val invalidators: ProcessRecoveryInvalidators = ProcessRecoveryInvalidators(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var cachedSnapshot: ProcessRecoverySnapshot? = null

    fun recover(): ProcessRecoverySnapshot = synchronized(lock) {
        cachedSnapshot ?: recoverLocked().also { snapshot -> cachedSnapshot = snapshot }
    }

    /** Explicit UI intent is required; this method never starts the artifact generator itself. */
    fun continueInterruptedArtifact(
        artifactId: String,
        userConfirmed: Boolean = false,
    ): ArtifactContinuationResult = synchronized(lock) {
        require(artifactId.isNotBlank()) { "artifactId must not be blank" }
        val artifact = artifactRepository.listInterruptedArtifacts()
            .singleOrNull { candidate -> candidate.artifactId == artifactId }
            ?: return@synchronized ArtifactContinuationResult(ArtifactContinuationCode.NOT_FOUND)
        if (!userConfirmed) {
            return@synchronized ArtifactContinuationResult(
                code = ArtifactContinuationCode.USER_CONFIRMATION_REQUIRED,
                artifact = artifact,
            )
        }
        return@synchronized ArtifactContinuationResult(
            code = ArtifactContinuationCode.CONTINUE_AUTHORIZED,
            artifact = artifact,
        )
    }

    private fun recoverLocked(): ProcessRecoverySnapshot {
        val invalidation = invalidators.invalidate()
        val invalidatedActiveSessions = sessionRepository.loadAll()
            .filter { session -> session.status == ConversationSession.Status.ACTIVE && session.endedAtMs == null }
            .map { session ->
                session.copy(
                    endedAtMs = nowMs(),
                    endReason = PROCESS_RESTART_INVALIDATION_REASON,
                    status = ConversationSession.Status.COMPLETED,
                )
            }
        invalidatedActiveSessions.forEach(sessionRepository::save)

        val recoveredSessions = sessionRepository.loadAll()
            .filter { session -> session.status == ConversationSession.Status.COMPLETED }
        val interruptedArtifacts = artifactRepository.markStagedArtifactsInterrupted()
        val recoveredArtifacts = artifactRepository.recoverCompletedArtifacts()
        return ProcessRecoverySnapshot(
            recoveredSessions = recoveredSessions,
            invalidatedActiveSessions = invalidatedActiveSessions,
            recoveredArtifacts = recoveredArtifacts,
            interruptedArtifacts = interruptedArtifacts,
            invalidatedResources = invalidation.resources,
            invalidationFailures = invalidation.failures,
            invalidationReason = PROCESS_RESTART_INVALIDATION_REASON,
        )
    }
}
