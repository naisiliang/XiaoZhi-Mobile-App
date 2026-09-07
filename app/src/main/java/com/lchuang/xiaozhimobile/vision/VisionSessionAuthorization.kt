package com.lchuang.xiaozhimobile.vision

import java.util.UUID

/** Opaque result supplied by a user-approved Android MediaProjection flow. */
data class UserMediaProjectionConsent(
    val opaqueHandle: String,
) {
    init {
        require(opaqueHandle.isNotBlank()) { "Projection consent handle must not be blank" }
    }
}

/**
 * An in-memory, process-bound authorization. The opaque platform handle is
 * never retained after the verifier returns.
 */
class VisionAuthorizationGrant internal constructor(
    internal val sessionId: String,
    internal val processInstanceId: String,
    internal val nonce: String,
    val grantedAtMs: Long,
) {
    override fun toString(): String =
        "VisionAuthorizationGrant(sessionId=$sessionId, grantedAtMs=$grantedAtMs)"
}

class VisionSessionAuthorization(
    private val processInstanceId: String = UUID.randomUUID().toString(),
    private val consentVerifier: (UserMediaProjectionConsent) -> Boolean = { false },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var activeSessionId: String? = null
    private var activeGrant: VisionAuthorizationGrant? = null

    init {
        require(processInstanceId.isNotBlank()) { "Process instance id must not be blank" }
    }

    @Synchronized
    fun beginSession(sessionId: String) {
        require(sessionId.isNotBlank()) { "Vision session id must not be blank" }
        activeSessionId = sessionId
        activeGrant = null
    }

    /** Grant only after the caller's user-mediated platform consent is verified. */
    @Synchronized
    fun grantUserConsent(
        sessionId: String,
        consent: UserMediaProjectionConsent,
    ): VisionAuthorizationGrant? {
        if (activeSessionId != sessionId) return null
        val verified = runCatching { consentVerifier(consent) }.getOrDefault(false)
        if (!verified) return null

        return VisionAuthorizationGrant(
            sessionId = sessionId,
            processInstanceId = processInstanceId,
            nonce = UUID.randomUUID().toString(),
            grantedAtMs = clockMs(),
        ).also { activeGrant = it }
    }

    @Synchronized
    fun isAuthorized(sessionId: String, grant: VisionAuthorizationGrant?): Boolean =
        grant != null &&
            activeSessionId == sessionId &&
            activeGrant === grant &&
            grant.sessionId == sessionId &&
            grant.processInstanceId == processInstanceId

    @Synchronized
    fun endSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            activeGrant = null
        }
    }

    @Synchronized
    fun invalidate() {
        activeSessionId = null
        activeGrant = null
    }
}
