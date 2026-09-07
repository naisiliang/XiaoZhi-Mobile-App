package com.lchuang.xiaozhimobile.extensions.skills

import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonException
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonNumber
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonParser

data class SkillStep(
    val tool: String,
    val arguments: Map<String, String> = emptyMap(),
)

data class SkillDefinition(
    val id: String,
    val version: String,
    val name: String,
    val triggers: List<String>,
    val workflow: List<SkillStep>,
    val allowedTools: Set<String>,
    val toolBudget: Int,
    val prompt: String = "",
) {
    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9._-]{2,63}")
        private val VERSION_PATTERN = Regex(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?",
        )
        private val TOOL_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
        private const val MAX_TRIGGERS = 32
        private const val MAX_WORKFLOW_STEPS = 64
        private const val MAX_TOOL_ARGUMENTS = 32
        private const val MAX_PROMPT_LENGTH = 8 * 1024

        fun parse(json: String): SkillDefinition {
            val root = try {
                DeclarativeJsonParser(json).parseObject()
            } catch (error: DeclarativeJsonException) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_MANIFEST, "invalid skill JSON", error)
            }
            val id = requiredString(root, "id")
            if (!ID_PATTERN.matches(id)) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_ID, "invalid skill id")
            }
            val version = requiredString(root, "version")
            if (!VERSION_PATTERN.matches(version)) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_VERSION, "invalid skill version")
            }
            val name = requiredString(root, "name")
            if (name.length > 128) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_MANIFEST, "skill name is too long")
            }
            val triggers = parseTriggers(root["triggers"] ?: root["trigger"])
            val workflow = parseWorkflow(root["workflow"])
            val allowedTools = parseTools(root["allowedTools"] ?: root["allowed_tools"])
            val toolBudget = parseBudget(root["toolBudget"] ?: root["tool_budget"])
            val prompt = when (val value = root["prompt"]) {
                null -> ""
                is String -> value
                else -> throw SkillDefinitionException(SkillDefinitionCode.INVALID_MANIFEST, "prompt must be a string")
            }
            if (prompt.length > MAX_PROMPT_LENGTH) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_MANIFEST, "prompt is too long")
            }
            return SkillDefinition(id, version, name, triggers, workflow, allowedTools, toolBudget, prompt)
        }

        private fun requiredString(root: Map<String, Any?>, key: String): String {
            val value = root[key]
            if (value !is String || value.isBlank() || value != value.trim()) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_MANIFEST, "$key must be a non-blank string")
            }
            return value
        }

        private fun parseTriggers(value: Any?): List<String> {
            val values = when (value) {
                is String -> listOf(value)
                is List<*> -> value
                else -> throw SkillDefinitionException(SkillDefinitionCode.INVALID_TRIGGER, "trigger(s) required")
            }
            if (values.isEmpty() || values.size > MAX_TRIGGERS) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_TRIGGER, "invalid trigger count")
            }
            val result = mutableListOf<String>()
            for (raw in values) {
                if (raw !is String || raw.isBlank() || raw != raw.trim() || raw.length > 128) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_TRIGGER, "invalid trigger")
                }
                if (!result.add(raw)) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_TRIGGER, "duplicate trigger")
                }
            }
            return result
        }

        private fun parseWorkflow(value: Any?): List<SkillStep> {
            if (value !is List<*> || value.size > MAX_WORKFLOW_STEPS) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "workflow must be a bounded array")
            }
            return value.map { raw ->
                val step = raw as? Map<*, *>
                    ?: throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "workflow step must be an object")
                val tool = step["tool"] as? String
                    ?: throw SkillDefinitionException(SkillDefinitionCode.INVALID_TOOL, "workflow tool is required")
                if (!TOOL_PATTERN.matches(tool)) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_TOOL, "invalid workflow tool")
                }
                val argumentsValue = step["arguments"] ?: step["args"]
                val arguments = parseArguments(argumentsValue)
                SkillStep(tool, arguments)
            }
        }

        private fun parseArguments(value: Any?): Map<String, String> {
            if (value == null) return emptyMap()
            val objectValue = value as? Map<*, *>
                ?: throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "step arguments must be an object")
            if (objectValue.size > MAX_TOOL_ARGUMENTS || objectValue.keys.any { it !is String }) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "too many step arguments")
            }
            val result = linkedMapOf<String, String>()
            for (rawKey in objectValue.keys.filterIsInstance<String>()) {
                if (!TOOL_PATTERN.matches(rawKey)) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "invalid step argument name")
                }
                val valueString = objectValue[rawKey] as? String
                    ?: throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "step arguments must be strings")
                if (valueString.length > 4 * 1024) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_WORKFLOW, "step argument is too long")
                }
                result[rawKey] = valueString
            }
            return result
        }

        private fun parseTools(value: Any?): Set<String> {
            if (value !is List<*> || value.isEmpty() || value.size > MAX_WORKFLOW_STEPS) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_TOOL, "allowedTools must be a bounded array")
            }
            val result = linkedSetOf<String>()
            for (raw in value) {
                if (raw !is String || !TOOL_PATTERN.matches(raw)) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_TOOL, "invalid allowed tool")
                }
                if (!result.add(raw)) {
                    throw SkillDefinitionException(SkillDefinitionCode.INVALID_TOOL, "duplicate allowed tool")
                }
            }
            return result
        }

        private fun parseBudget(value: Any?): Int {
            val number = value as? DeclarativeJsonNumber
            val budget = number?.raw?.toIntOrNull()
            if (budget == null || budget !in 1..MAX_WORKFLOW_STEPS) {
                throw SkillDefinitionException(SkillDefinitionCode.INVALID_BUDGET, "invalid tool budget")
            }
            return budget
        }
    }
}

enum class SkillDefinitionCode {
    INVALID_MANIFEST,
    INVALID_ID,
    INVALID_VERSION,
    INVALID_TRIGGER,
    INVALID_WORKFLOW,
    INVALID_TOOL,
    INVALID_BUDGET,
    BUDGET_EXCEEDED,
}

class SkillDefinitionException(
    val code: SkillDefinitionCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
