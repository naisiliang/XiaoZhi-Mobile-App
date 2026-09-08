package com.lchuang.xiaozhimobile.extensions.agents

import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.ToolExecutionResult
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

sealed interface AgentRunResult {
    data class Completed(
        val invocations: List<ToolInvocation>,
        val trace: List<String>,
        val toolCalls: Int,
    ) : AgentRunResult

    data class Denied(
        val code: AgentRunCode,
        val agentId: String,
        val trace: List<String> = emptyList(),
    ) : AgentRunResult
}

enum class AgentRunCode {
    AGENT_NOT_FOUND,
    AGENT_DISABLED,
    INPUT_TOO_LARGE,
    MAX_DELEGATION_DEPTH_EXCEEDED,
    TOOL_BUDGET_EXCEEDED,
    ARTIFACT_BUDGET_EXCEEDED,
    EXECUTION_TIMEOUT,
    DELEGATION_CYCLE,
    TOOL_NOT_ALLOWED,
    TOOL_BLOCKED,
    PERMISSION_NOT_DECLARED,
    TOOL_EXECUTION_FAILED,
}

/**
 * App-owned execution boundary for agent tools. Artifact-producing tools must
 * return a byte count measured from the generated private file; manifest
 * arguments such as `artifact_size_bytes` are never trusted as measurements.
 */
fun interface AgentToolExecutor {
    fun execute(invocation: ToolInvocation, maxArtifactBytes: Long): ToolExecutionResult
}

/**
 * Bounded orchestration. Ordinary tools can still be planned as typed
 * invocations; artifact-producing tools require the app-owned executor so the
 * real generated output is measured before it is charged to the budget.
 */
class AgentOrchestrator(
    private val registry: AgentRegistry,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val safetyPolicy = CentralSafetyPolicyEngine()

    fun run(
        agentId: String,
        input: String,
        budget: AgentBudget = AgentBudget(),
        toolExecutor: AgentToolExecutor? = null,
    ): AgentRunResult {
        if (input.toByteArray(StandardCharsets.UTF_8).size > MAX_INPUT_BYTES) {
            return AgentRunResult.Denied(AgentRunCode.INPUT_TOO_LARGE, agentId)
        }
        val started = nowNanos()
        val deadline = deadlineAfter(started, budget.maxExecutionTimeMs)
        return runAgent(
            agentId = agentId,
            input = input,
            budget = budget,
            state = ExecutionState(),
            activePath = emptyList(),
            depth = 0,
            parentDeadlineNanos = deadline,
            toolExecutor = toolExecutor,
        )
    }

    private fun runAgent(
        agentId: String,
        input: String,
        budget: AgentBudget,
        state: ExecutionState,
        activePath: List<String>,
        depth: Int,
        parentDeadlineNanos: Long,
        toolExecutor: AgentToolExecutor?,
    ): AgentRunResult {
        if (agentId in activePath) {
            return AgentRunResult.Denied(AgentRunCode.DELEGATION_CYCLE, agentId, activePath)
        }
        val definition = registry.find(agentId)
            ?: return AgentRunResult.Denied(AgentRunCode.AGENT_NOT_FOUND, agentId, activePath)
        if (!registry.isEnabled(agentId)) {
            return AgentRunResult.Denied(AgentRunCode.AGENT_DISABLED, agentId, activePath)
        }
        if (depth > budget.maxDelegationDepth) {
            return AgentRunResult.Denied(AgentRunCode.MAX_DELEGATION_DEPTH_EXCEEDED, agentId, activePath)
        }

        val currentDeadline = min(
            parentDeadlineNanos,
            deadlineAfter(nowNanos(), definition.maxExecutionTimeMs),
        )
        if (isExpired(currentDeadline)) {
            return AgentRunResult.Denied(AgentRunCode.EXECUTION_TIMEOUT, agentId, activePath)
        }

        val path = activePath + agentId
        val trace = path.toMutableList()
        val invocations = mutableListOf<ToolInvocation>()
        var localToolCalls = 0
        for (step in definition.workflow) {
            if (isExpired(currentDeadline)) {
                return AgentRunResult.Denied(AgentRunCode.EXECUTION_TIMEOUT, agentId, path)
            }
            if (state.toolCalls >= budget.maxToolCalls || localToolCalls >= definition.maxToolCalls) {
                return AgentRunResult.Denied(AgentRunCode.TOOL_BUDGET_EXCEEDED, agentId, path)
            }
            val normalized = step.tool.trim().lowercase(Locale.ROOT)
            if (normalized !in definition.allowedTools.map { it.lowercase(Locale.ROOT) }.toSet()) {
                return AgentRunResult.Denied(AgentRunCode.TOOL_NOT_ALLOWED, agentId, path)
            }
            val invocation = ToolInvocation(
                name = step.tool,
                arguments = step.arguments.mapValues { (_, value) -> value.replace("{input}", input) },
            )
            if (safetyPolicy.evaluate(invocation).decision == ToolDecision.BLOCK) {
                return AgentRunResult.Denied(AgentRunCode.TOOL_BLOCKED, agentId, path)
            }
            if (!definition.permissions.containsAll(AgentRegistry.requiredPermissionsFor(normalized))) {
                return AgentRunResult.Denied(AgentRunCode.PERMISSION_NOT_DECLARED, agentId, path)
            }
            val artifactBytes = if (AgentRegistry.isArtifactProducingTool(normalized)) {
                val remainingBudget = budget.maxArtifactSizeBytes - state.artifactBytes
                val maximum = min(definition.maxArtifactSizeBytes, remainingBudget)
                if (maximum <= 0L) {
                    return AgentRunResult.Denied(AgentRunCode.ARTIFACT_BUDGET_EXCEEDED, agentId, path)
                }
                val executor = toolExecutor
                    ?: return AgentRunResult.Denied(AgentRunCode.ARTIFACT_BUDGET_EXCEEDED, agentId, path)
                val executorInvocation = invocation.copy(
                    arguments = invocation.arguments.filterKeys { it !in DECLARED_ARTIFACT_SIZE_ARGUMENTS },
                )
                val execution = try {
                    executor.execute(executorInvocation, maximum)
                } catch (_: Throwable) {
                    return AgentRunResult.Denied(AgentRunCode.TOOL_EXECUTION_FAILED, agentId, path)
                }
                if (!execution.success) {
                    return AgentRunResult.Denied(AgentRunCode.TOOL_EXECUTION_FAILED, agentId, path)
                }
                val measuredBytes = execution.artifactBytes
                    ?: return AgentRunResult.Denied(AgentRunCode.ARTIFACT_BUDGET_EXCEEDED, agentId, path)
                if (measuredBytes < 0L || measuredBytes > maximum) {
                    return AgentRunResult.Denied(AgentRunCode.ARTIFACT_BUDGET_EXCEEDED, agentId, path)
                }
                measuredBytes
            } else {
                0L
            }
            invocations += invocation
            state.toolCalls++
            localToolCalls++
            state.artifactBytes += artifactBytes
        }

        if (isExpired(currentDeadline)) {
            return AgentRunResult.Denied(AgentRunCode.EXECUTION_TIMEOUT, agentId, path)
        }

        for (target in definition.delegationTargets) {
            if (isExpired(currentDeadline)) {
                return AgentRunResult.Denied(AgentRunCode.EXECUTION_TIMEOUT, agentId, path)
            }
            val maximumDepth = min(budget.maxDelegationDepth, definition.maxDelegationDepth)
            if (depth >= maximumDepth) {
                return AgentRunResult.Denied(AgentRunCode.MAX_DELEGATION_DEPTH_EXCEEDED, target, path)
            }
            if (target in path) {
                return AgentRunResult.Denied(AgentRunCode.DELEGATION_CYCLE, target, path)
            }
            val child = runAgent(
                agentId = target,
                input = input,
                budget = budget,
                state = state,
                activePath = path,
                depth = depth + 1,
                parentDeadlineNanos = currentDeadline,
                toolExecutor = toolExecutor,
            )
            when (child) {
                is AgentRunResult.Denied -> return child
                is AgentRunResult.Completed -> {
                    invocations += child.invocations
                    trace += child.trace.drop(path.size)
                }
            }
        }
        return AgentRunResult.Completed(
            invocations = invocations,
            trace = trace,
            toolCalls = state.toolCalls,
        )
    }

    private fun isExpired(deadlineNanos: Long): Boolean = nowNanos() >= deadlineNanos

    private fun deadlineAfter(start: Long, durationMs: Long): Long {
        val durationNanos = durationMs * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - start < durationNanos) Long.MAX_VALUE else start + durationNanos
    }

    // `artifact_size_bytes` / `artifactSizeBytes` in a declarative manifest
    // are advisory metadata only. The executor result above is the sole byte
    // accounting source, so under-reporting the manifest cannot bypass limits.

    private data class ExecutionState(
        var toolCalls: Int = 0,
        var artifactBytes: Long = 0L,
    )

    companion object {
        private const val MAX_INPUT_BYTES = 8 * 1024
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val DECLARED_ARTIFACT_SIZE_ARGUMENTS = setOf("artifact_size_bytes", "artifactSizeBytes")
    }
}
