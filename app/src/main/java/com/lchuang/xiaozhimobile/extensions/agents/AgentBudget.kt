package com.lchuang.xiaozhimobile.extensions.agents

/** Hard upper bounds applied to one agent orchestration request. */
data class AgentBudget(
    val maxDelegationDepth: Int = DEFAULT_MAX_DELEGATION_DEPTH,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val maxExecutionTimeMs: Long = DEFAULT_MAX_EXECUTION_TIME_MS,
    val maxArtifactSizeBytes: Long = DEFAULT_MAX_ARTIFACT_SIZE_BYTES,
) {
    init {
        require(maxDelegationDepth in 0..MAX_DELEGATION_DEPTH) {
            "maxDelegationDepth must be between 0 and $MAX_DELEGATION_DEPTH"
        }
        require(maxToolCalls in 1..MAX_TOOL_CALLS) {
            "maxToolCalls must be between 1 and $MAX_TOOL_CALLS"
        }
        require(maxExecutionTimeMs in 1..MAX_EXECUTION_TIME_MS) {
            "maxExecutionTimeMs must be between 1 and $MAX_EXECUTION_TIME_MS"
        }
        require(maxArtifactSizeBytes in 1..MAX_ARTIFACT_SIZE_BYTES) {
            "maxArtifactSizeBytes must be between 1 and $MAX_ARTIFACT_SIZE_BYTES"
        }
    }

    /** Alias used by the design spec's prose name. */
    val maxExecutionTime: Long
        get() = maxExecutionTimeMs

    /** Alias used by artifact-facing callers. */
    val maxArtifactSize: Long
        get() = maxArtifactSizeBytes

    companion object {
        const val DEFAULT_MAX_DELEGATION_DEPTH = 3
        const val DEFAULT_MAX_TOOL_CALLS = 16
        const val DEFAULT_MAX_EXECUTION_TIME_MS = 30_000L
        const val DEFAULT_MAX_ARTIFACT_SIZE_BYTES = 16L * 1024L * 1024L

        const val MAX_DELEGATION_DEPTH = 8
        const val MAX_TOOL_CALLS = 64
        const val MAX_EXECUTION_TIME_MS = 120_000L
        const val MAX_ARTIFACT_SIZE_BYTES = 64L * 1024L * 1024L

        fun isValid(
            maxDelegationDepth: Int,
            maxToolCalls: Int,
            maxExecutionTimeMs: Long,
            maxArtifactSizeBytes: Long,
        ): Boolean = maxDelegationDepth in 0..MAX_DELEGATION_DEPTH &&
            maxToolCalls in 1..MAX_TOOL_CALLS &&
            maxExecutionTimeMs in 1..MAX_EXECUTION_TIME_MS &&
            maxArtifactSizeBytes in 1..MAX_ARTIFACT_SIZE_BYTES
    }
}
