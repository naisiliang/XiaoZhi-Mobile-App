package com.lchuang.xiaozhimobile.recovery

import com.lchuang.xiaozhimobile.artifacts.Artifact
import com.lchuang.xiaozhimobile.artifacts.ArtifactDigest
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactStatus
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import com.lchuang.xiaozhimobile.conversation.ConversationMessage
import com.lchuang.xiaozhimobile.conversation.ConversationSession
import com.lchuang.xiaozhimobile.conversation.ConversationSessionRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRecoveryCoordinatorTest {
    @Test
    fun `restart recovers completed data and invalidates every process-bound resource`() {
        val sessions = SessionStore()
        val active = session("active-session", endedAtMs = null)
        val completed = session("completed-session", endedAtMs = 1_100L)
        sessions.seed(active)
        sessions.seed(completed)

        val workspace = ArtifactWorkspace(Files.createTempDirectory("process-recovery-artifacts-").toFile())
        val artifacts = ArtifactRepository(workspace)
        val completedArtifact = artifact(workspace, "completed-artifact", ArtifactStatus.COMPLETED, "done")
        val stagedArtifact = artifact(workspace, "staged-artifact", ArtifactStatus.STAGED, "resume me")
        artifacts.save(completedArtifact)
        artifacts.save(stagedArtifact)

        val invalidated = mutableSetOf<ProcessBoundResource>()
        val coordinator = ProcessRecoveryCoordinator(
            sessionRepository = sessions,
            artifactRepository = artifacts,
            invalidators = ProcessRecoveryInvalidators(
                pendingMessageSend = { invalidated += ProcessBoundResource.PENDING_MESSAGE_SEND },
                confirmationToken = { invalidated += ProcessBoundResource.CONFIRMATION_TOKEN },
                pendingUiAction = { invalidated += ProcessBoundResource.PENDING_UI_ACTION },
                screenContext = { invalidated += ProcessBoundResource.SCREEN_CONTEXT },
                mediaProjection = { invalidated += ProcessBoundResource.MEDIA_PROJECTION },
            ),
            nowMs = { 2_000L },
        )

        val snapshot = coordinator.recover()

        assertEquals(
            setOf(
                ProcessBoundResource.PENDING_MESSAGE_SEND,
                ProcessBoundResource.CONFIRMATION_TOKEN,
                ProcessBoundResource.PENDING_UI_ACTION,
                ProcessBoundResource.SCREEN_CONTEXT,
                ProcessBoundResource.MEDIA_PROJECTION,
            ),
            snapshot.invalidatedResources,
        )
        assertEquals(snapshot.invalidatedResources, invalidated)
        assertTrue(snapshot.invalidationFailures.isEmpty())
        assertEquals(PROCESS_RESTART_INVALIDATION_REASON, snapshot.invalidationReason)
        assertEquals(setOf("completed-session", "active-session"), snapshot.recoveredSessions.map { it.id }.toSet())
        assertEquals(ConversationSession.Status.COMPLETED, sessions.find("active-session").status)
        assertEquals("INVALIDATED_ON_PROCESS_RESTART", sessions.find("active-session").endReason)
        assertEquals(2_000L, sessions.find("active-session").endedAtMs)
        assertEquals(setOf("completed-artifact"), snapshot.recoveredArtifacts.map { it.artifactId }.toSet())
        assertEquals(setOf("staged-artifact"), snapshot.interruptedArtifacts.map { it.artifactId }.toSet())
        assertEquals(ArtifactStatus.INTERRUPTED, artifacts.find("staged-artifact")?.status)
        artifacts.close()
    }

    @Test
    fun `corrupt completed artifacts are not presented as recovered`() {
        val sessions = SessionStore()
        val workspace = ArtifactWorkspace(Files.createTempDirectory("process-recovery-invalid-").toFile())
        val artifacts = ArtifactRepository(workspace)
        val corrupt = artifact(workspace, "corrupt-artifact", ArtifactStatus.COMPLETED, "original")
        artifacts.save(corrupt)
        File(corrupt.privatePath).writeText("changed")

        val snapshot = ProcessRecoveryCoordinator(
            sessionRepository = sessions,
            artifactRepository = artifacts,
            invalidators = ProcessRecoveryInvalidators(),
        ).recover()

        assertTrue(snapshot.recoveredArtifacts.isEmpty())
        assertTrue(snapshot.interruptedArtifacts.isEmpty())
        artifacts.close()
    }

    @Test
    fun `interrupted artifact requires explicit user continuation`() {
        val sessions = SessionStore()
        val workspace = ArtifactWorkspace(Files.createTempDirectory("process-recovery-continue-").toFile())
        val artifacts = ArtifactRepository(workspace)
        val interrupted = artifact(workspace, "interrupted-artifact", ArtifactStatus.INTERRUPTED, "draft")
        artifacts.save(interrupted)
        val coordinator = ProcessRecoveryCoordinator(
            sessionRepository = sessions,
            artifactRepository = artifacts,
            invalidators = ProcessRecoveryInvalidators(),
        )
        coordinator.recover()

        val waiting = coordinator.continueInterruptedArtifact(interrupted.artifactId)
        assertEquals(ArtifactContinuationCode.USER_CONFIRMATION_REQUIRED, waiting.code)
        assertFalse(waiting.continuationAuthorized)

        val authorized = coordinator.continueInterruptedArtifact(interrupted.artifactId, userConfirmed = true)
        assertEquals(ArtifactContinuationCode.CONTINUE_AUTHORIZED, authorized.code)
        assertTrue(authorized.continuationAuthorized)
        assertEquals(interrupted.artifactId, authorized.artifact?.artifactId)
        artifacts.close()
    }

    @Test
    fun `recovery is idempotent and invalidators run only once`() {
        val sessions = SessionStore()
        val workspace = ArtifactWorkspace(Files.createTempDirectory("process-recovery-idempotent-").toFile())
        val artifacts = ArtifactRepository(workspace)
        val staged = artifact(workspace, "staged-once", ArtifactStatus.STAGED, "checkpoint")
        artifacts.save(staged)
        var invalidationCalls = 0
        val coordinator = ProcessRecoveryCoordinator(
            sessionRepository = sessions,
            artifactRepository = artifacts,
            invalidators = ProcessRecoveryInvalidators(
                pendingMessageSend = { invalidationCalls++ },
            ),
        )

        val first = coordinator.recover()
        val second = coordinator.recover()

        assertTrue(first === second)
        assertEquals(1, invalidationCalls)
        assertEquals(ArtifactStatus.INTERRUPTED, artifacts.find(staged.artifactId)?.status)
        artifacts.close()
    }

    @Test
    fun `one invalidator failure does not skip the remaining invalidation boundaries`() {
        val invoked = mutableSetOf<ProcessBoundResource>()
        val result = ProcessRecoveryInvalidators(
            pendingMessageSend = {
                invoked += ProcessBoundResource.PENDING_MESSAGE_SEND
                error("owner unavailable")
            },
            confirmationToken = { invoked += ProcessBoundResource.CONFIRMATION_TOKEN },
            pendingUiAction = { invoked += ProcessBoundResource.PENDING_UI_ACTION },
            screenContext = { invoked += ProcessBoundResource.SCREEN_CONTEXT },
            mediaProjection = { invoked += ProcessBoundResource.MEDIA_PROJECTION },
        ).invalidate()

        assertEquals(ProcessBoundResource.entries.toSet(), invoked)
        assertEquals(setOf(ProcessBoundResource.PENDING_MESSAGE_SEND), result.failures)
        assertEquals(ProcessBoundResource.entries.toSet(), result.resources)
    }

    private fun session(id: String, endedAtMs: Long?): ConversationSession = ConversationSession(
        id = id,
        startedAtMs = 1_000L,
        messages = listOf(
            ConversationMessage(
                role = ConversationMessage.Role.USER,
                text = "请求",
                timestampMs = 1_010L,
                status = "complete",
            ),
        ),
        endedAtMs = endedAtMs,
        status = if (endedAtMs == null) ConversationSession.Status.ACTIVE else ConversationSession.Status.COMPLETED,
    )

    private fun artifact(
        workspace: ArtifactWorkspace,
        id: String,
        status: ArtifactStatus,
        content: String,
    ): Artifact {
        val file = workspace.allocateVersionFile(id, 1, "txt")
        file.writeText(content)
        return Artifact(
            artifactId = id,
            sessionId = "session-1",
            mimeType = "text/plain",
            displayName = "$id.txt",
            privatePath = file.canonicalPath,
            size = file.length(),
            sha256 = ArtifactDigest.sha256(file),
            createdAt = 1_000L,
            sourceAgent = "file-assistant",
            status = status,
        )
    }

    private class SessionStore : ConversationSessionRepository {
        private val values = linkedMapOf<String, ConversationSession>()

        override fun save(session: ConversationSession) {
            values[session.id] = session
        }

        override fun loadAll(): List<ConversationSession> = values.values.toList()

        fun seed(session: ConversationSession) {
            values[session.id] = session
        }

        fun find(id: String): ConversationSession = checkNotNull(values[id])
    }
}
