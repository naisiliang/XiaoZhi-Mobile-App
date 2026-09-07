package com.lchuang.xiaozhimobile.providers.health

import com.lchuang.xiaozhimobile.diagnostics.DiagnosticRecorder
import com.lchuang.xiaozhimobile.providers.ProviderCapability

enum class CapabilityHealthState {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    SUSPENDED,
}

enum class CapabilityFailureKind {
    TRANSIENT,
    SECURITY_VIOLATION,
}

data class CapabilityHealthStatus(
    val capability: ProviderCapability,
    val state: CapabilityHealthState,
    val consecutiveFailures: Int,
    val lastFailureReason: String = "",
)

/** Tracks provider capability failures without allowing a suspended capability to self-revive. */
class CapabilityHealthMonitor(
    private val degradedAfter: Int = DEFAULT_DEGRADED_AFTER,
    private val unhealthyAfter: Int = DEFAULT_UNHEALTHY_AFTER,
    private val suspendedAfter: Int = DEFAULT_SUSPENDED_AFTER,
    private val diagnosticRecorder: DiagnosticRecorder? = null,
) {
    init {
        require(degradedAfter >= 1)
        require(unhealthyAfter > degradedAfter)
        require(suspendedAfter > unhealthyAfter)
    }

    private data class MutableStatus(
        val state: CapabilityHealthState,
        val consecutiveFailures: Int,
        val lastFailureReason: String,
    )

    private val statuses = linkedMapOf<ProviderCapability, MutableStatus>()

    @Synchronized
    fun status(capability: ProviderCapability): CapabilityHealthStatus = snapshotOf(capability)

    @Synchronized
    fun stateOf(capability: ProviderCapability): CapabilityHealthState = status(capability).state

    @Synchronized
    fun recordFailure(
        capability: ProviderCapability,
        reason: String = "",
        kind: CapabilityFailureKind = CapabilityFailureKind.TRANSIENT,
    ): CapabilityHealthStatus {
        val current = statuses[capability]
        if (current?.state == CapabilityHealthState.SUSPENDED) return snapshotOf(capability)
        val failures = (current?.consecutiveFailures ?: 0) + 1
        val state = when {
            kind == CapabilityFailureKind.SECURITY_VIOLATION -> CapabilityHealthState.SUSPENDED
            failures >= suspendedAfter -> CapabilityHealthState.SUSPENDED
            failures >= unhealthyAfter -> CapabilityHealthState.UNHEALTHY
            failures >= degradedAfter -> CapabilityHealthState.DEGRADED
            else -> CapabilityHealthState.HEALTHY
        }
        statuses[capability] = MutableStatus(state, failures, reason.take(MAX_REASON_LENGTH))
        return snapshotOf(capability).also { status ->
            recordDiagnostic("capability_failure", status, reason.isNotBlank())
        }
    }

    @Synchronized
    fun recordSuccess(capability: ProviderCapability): CapabilityHealthStatus {
        val current = statuses[capability]
        if (current?.state == CapabilityHealthState.SUSPENDED) return snapshotOf(capability)
        statuses[capability] = MutableStatus(CapabilityHealthState.HEALTHY, 0, "")
        return snapshotOf(capability).also { status ->
            recordDiagnostic("capability_success", status, reasonPresent = false)
        }
    }

    /** Explicit user recheck is required to clear SUSPENDED, including security suspensions. */
    @Synchronized
    fun recheck(capability: ProviderCapability): CapabilityHealthStatus {
        statuses[capability] = MutableStatus(CapabilityHealthState.HEALTHY, 0, "")
        return snapshotOf(capability).also { status ->
            recordDiagnostic("capability_recheck", status, reasonPresent = false)
        }
    }

    @Synchronized
    fun snapshot(): List<CapabilityHealthStatus> = ProviderCapability.entries.map(::snapshotOf)

    private fun snapshotOf(capability: ProviderCapability): CapabilityHealthStatus {
        val current = statuses[capability] ?: MutableStatus(CapabilityHealthState.HEALTHY, 0, "")
        return CapabilityHealthStatus(
            capability = capability,
            state = current.state,
            consecutiveFailures = current.consecutiveFailures,
            lastFailureReason = current.lastFailureReason,
        )
    }

    private fun recordDiagnostic(
        action: String,
        status: CapabilityHealthStatus,
        reasonPresent: Boolean,
    ) {
        diagnosticRecorder?.record(
            sessionId = "",
            module = "provider_health",
            action = action,
            resultCode = status.state.name,
            safeMetadata = mapOf(
                "capability" to status.capability.name,
                "consecutiveFailures" to status.consecutiveFailures.toString(),
                "reasonPresent" to reasonPresent.toString(),
            ),
        )
    }

    companion object {
        const val DEFAULT_DEGRADED_AFTER = 1
        const val DEFAULT_UNHEALTHY_AFTER = 3
        const val DEFAULT_SUSPENDED_AFTER = 5
        private const val MAX_REASON_LENGTH = 256
    }
}
