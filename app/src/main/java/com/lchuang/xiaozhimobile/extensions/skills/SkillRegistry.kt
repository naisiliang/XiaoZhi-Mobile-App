package com.lchuang.xiaozhimobile.extensions.skills

import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.tools.ToolRegistry
import java.util.Locale

enum class SkillRegistryCode {
    DUPLICATE_ID,
    UNKNOWN_TOOL,
    TOOL_NOT_ALLOWED,
    BUDGET_EXCEEDED,
    SKILL_NOT_FOUND,
}

sealed interface SkillRegistryResult {
    data class Registered(val definition: SkillDefinition) : SkillRegistryResult

    data class Enabled(val definition: SkillDefinition) : SkillRegistryResult

    data class Disabled(val definition: SkillDefinition) : SkillRegistryResult

    data class Rejected(
        val code: SkillRegistryCode,
        val detail: String = "",
    ) : SkillRegistryResult
}

class SkillRegistry(
    knownTools: Set<String> = DEFAULT_KNOWN_TOOLS,
) {
    private val knownToolNames = knownTools.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    private val definitionsById = linkedMapOf<String, SkillDefinition>()
    private val enabledIds = linkedSetOf<String>()
    private val safetyPolicy = CentralSafetyPolicyEngine()

    fun register(definition: SkillDefinition): SkillRegistryResult {
        if (definitionsById.containsKey(definition.id)) {
            return SkillRegistryResult.Rejected(SkillRegistryCode.DUPLICATE_ID)
        }
        if (definition.allowedTools.any {
                it.lowercase(Locale.ROOT) !in knownToolNames ||
                    safetyPolicy.evaluate(ToolInvocation(it)).decision == ToolDecision.BLOCK
            }) {
            return SkillRegistryResult.Rejected(SkillRegistryCode.UNKNOWN_TOOL)
        }
        val allowed = definition.allowedTools.map { it.lowercase(Locale.ROOT) }.toSet()
        if (definition.workflow.any { it.tool.lowercase(Locale.ROOT) !in allowed }) {
            return SkillRegistryResult.Rejected(SkillRegistryCode.TOOL_NOT_ALLOWED)
        }
        if (definition.workflow.size > definition.toolBudget) {
            return SkillRegistryResult.Rejected(SkillRegistryCode.BUDGET_EXCEEDED)
        }
        definitionsById[definition.id] = definition
        enabledIds += definition.id
        return SkillRegistryResult.Registered(definition)
    }

    fun find(skillId: String): SkillDefinition? = definitionsById[skillId]

    fun all(): List<SkillDefinition> = definitionsById.values.sortedBy { it.id }

    fun matching(input: String): List<SkillDefinition> {
        val normalized = input.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return emptyList()
        return definitionsById.values
            .filter { it.id in enabledIds }
            .filter { definition -> definition.triggers.any { trigger -> normalized.contains(trigger.lowercase(Locale.ROOT)) } }
            .sortedBy { it.id }
    }

    fun enable(skillId: String): SkillRegistryResult {
        val definition = definitionsById[skillId]
            ?: return SkillRegistryResult.Rejected(SkillRegistryCode.SKILL_NOT_FOUND)
        enabledIds += skillId
        return SkillRegistryResult.Enabled(definition)
    }

    fun disable(skillId: String): SkillRegistryResult {
        val definition = definitionsById[skillId]
            ?: return SkillRegistryResult.Rejected(SkillRegistryCode.SKILL_NOT_FOUND)
        enabledIds -= skillId
        return SkillRegistryResult.Disabled(definition)
    }

    fun remove(skillId: String): Boolean {
        enabledIds -= skillId
        return definitionsById.remove(skillId) != null
    }

    fun isEnabled(skillId: String): Boolean = skillId in enabledIds

    companion object {
        private val DEFAULT_KNOWN_TOOLS = setOf(
            "open_app", "navigate", "search_nearby", "open_web",
            "media_play", "media_pause", "media_next", "media_previous",
            "volume_up", "volume_down", "set_volume", "flashlight_on", "flashlight_off",
            "ui_click", "ui_select", "ui_back", "ui_next",
        ) + ToolRegistry.definitions().map { it.name }
    }
}
