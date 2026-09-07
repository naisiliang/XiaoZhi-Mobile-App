package com.lchuang.xiaozhimobile.extensions.skills

import com.lchuang.xiaozhimobile.safety.ToolInvocation
import java.util.Locale

sealed interface SkillRunResult {
    data class Emitted(val invocations: List<ToolInvocation>) : SkillRunResult

    data object NotTriggered : SkillRunResult

    data class Denied(val code: SkillRunCode) : SkillRunResult
}

enum class SkillRunCode {
    SKILL_NOT_FOUND,
    SKILL_DISABLED,
    INPUT_TOO_LARGE,
    TOOL_NOT_ALLOWED,
    TOOL_BUDGET_EXCEEDED,
}

/** Interprets declarative steps into requests; execution remains in the app-owned tool chain. */
class SkillRunner(private val registry: SkillRegistry) {
    fun run(skillId: String, input: String): SkillRunResult {
        val definition = registry.find(skillId) ?: return SkillRunResult.Denied(SkillRunCode.SKILL_NOT_FOUND)
        if (!registry.isEnabled(skillId)) return SkillRunResult.Denied(SkillRunCode.SKILL_DISABLED)
        if (input.length > MAX_INPUT_LENGTH) return SkillRunResult.Denied(SkillRunCode.INPUT_TOO_LARGE)
        val normalized = input.trim()
        if (normalized.isBlank() || definition.triggers.none {
            normalized.lowercase(Locale.ROOT).contains(it.lowercase(Locale.ROOT))
        }) {
            return SkillRunResult.NotTriggered
        }
        if (definition.workflow.size > definition.toolBudget) {
            return SkillRunResult.Denied(SkillRunCode.TOOL_BUDGET_EXCEEDED)
        }
        val allowed = definition.allowedTools.map { it.lowercase(Locale.ROOT) }.toSet()
        val invocations = mutableListOf<ToolInvocation>()
        for (step in definition.workflow) {
            if (step.tool.lowercase(Locale.ROOT) !in allowed) {
                return SkillRunResult.Denied(SkillRunCode.TOOL_NOT_ALLOWED)
            }
            invocations += ToolInvocation(
                name = step.tool,
                arguments = step.arguments.mapValues { (_, value) -> value.replace("{input}", normalized) },
            )
        }
        return SkillRunResult.Emitted(invocations)
    }

    companion object {
        private const val MAX_INPUT_LENGTH = 4 * 1024
    }
}
