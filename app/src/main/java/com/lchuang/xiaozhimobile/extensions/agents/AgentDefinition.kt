package com.lchuang.xiaozhimobile.extensions.agents

import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonException
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonNumber
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonParser
import com.lchuang.xiaozhimobile.extensions.ExtensionPermission
import java.nio.charset.StandardCharsets

data class AgentStep(
    val tool: String,
    val arguments: Map<String, String> = emptyMap(),
)

/** Declarative agent metadata; it contains no executable code or Android handle. */
data class AgentDefinition(
    val id: String,
    val version: String,
    val name: String,
    val systemPrompt: String = "",
    val skills: Set<String> = emptySet(),
    val allowedTools: Set<String> = emptySet(),
    val permissions: Set<ExtensionPermission> = emptySet(),
    val maxDelegationDepth: Int = AgentBudget.DEFAULT_MAX_DELEGATION_DEPTH,
    val maxToolCalls: Int = AgentBudget.DEFAULT_MAX_TOOL_CALLS,
    val maxExecutionTimeMs: Long = AgentBudget.DEFAULT_MAX_EXECUTION_TIME_MS,
    val maxArtifactSizeBytes: Long = AgentBudget.DEFAULT_MAX_ARTIFACT_SIZE_BYTES,
    val workflow: List<AgentStep> = emptyList(),
    val delegationTargets: List<String> = emptyList(),
    val model: String = "default",
    val description: String = "",
) {
    /** Compatibility alias for callers that refer to the system prompt as prompt. */
    val prompt: String
        get() = systemPrompt

    /** Compatibility alias for the allowlist terminology used by the security contract. */
    val tools: Set<String>
        get() = allowedTools

    val maxExecutionTime: Long
        get() = maxExecutionTimeMs

    val maxArtifactSize: Long
        get() = maxArtifactSizeBytes

    fun hasValidBudget(): Boolean = AgentBudget.isValid(
        maxDelegationDepth = maxDelegationDepth,
        maxToolCalls = maxToolCalls,
        maxExecutionTimeMs = maxExecutionTimeMs,
        maxArtifactSizeBytes = maxArtifactSizeBytes,
    )

    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9._-]{2,63}")
        private val VERSION_PATTERN = Regex(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?",
        )
        private val TOOL_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
        private const val MAX_NAME_LENGTH = 128
        private const val MAX_DESCRIPTION_LENGTH = 4 * 1024
        private const val MAX_PROMPT_LENGTH = 8 * 1024
        private const val MAX_LIST_ITEMS = 64
        private const val MAX_ARGUMENTS = 32
        private const val MAX_ARGUMENT_LENGTH = 4 * 1024
        private const val MAX_JSON_BYTES = 64 * 1024

        fun parse(json: String): AgentDefinition {
            if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "agent JSON is too large")
            }
            val root = try {
                DeclarativeJsonParser(json).parseObject()
            } catch (error: DeclarativeJsonException) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "invalid agent JSON", error)
            }

            val id = requiredString(root, "id")
            if (!ID_PATTERN.matches(id)) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_ID, "invalid agent id")
            }
            val version = requiredString(root, "version")
            if (!VERSION_PATTERN.matches(version)) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_VERSION, "invalid agent version")
            }
            val name = requiredString(root, "name")
            if (name.length > MAX_NAME_LENGTH) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "agent name is too long")
            }

            val model = optionalString(root, "model", "default")
            if (model.isBlank() || model.length > MAX_NAME_LENGTH) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "invalid agent model")
            }
            val systemPrompt = optionalString(root, "systemPrompt", optionalString(root, "prompt", ""))
            if (systemPrompt.length > MAX_PROMPT_LENGTH) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "agent prompt is too long")
            }
            val description = optionalString(root, "description", "")
            if (description.length > MAX_DESCRIPTION_LENGTH) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "agent description is too long")
            }

            val skills = parseIdentifiers(root, listOf("skills", "allowedSkills"), AgentDefinitionCode.INVALID_SKILL).toSet()
            val allowedTools = parseTools(root["allowedTools"] ?: root["tools"])
            val permissions = parsePermissions(root["permissions"])
            val workflow = parseWorkflow(root["workflow"])
            val delegationTargets = parseIdentifiers(
                root,
                listOf("delegationTargets", "delegatesTo", "delegation_targets", "delegates_to"),
                AgentDefinitionCode.INVALID_DELEGATION,
            )

            val maxDelegationDepth = requiredInt(
                root,
                listOf("maxDelegationDepth", "max_delegation_depth"),
                AgentDefinitionCode.INVALID_BUDGET,
            )
            val maxToolCalls = requiredInt(
                root,
                listOf("maxToolCalls", "max_tool_calls"),
                AgentDefinitionCode.INVALID_BUDGET,
            )
            val maxExecutionTimeMs = requiredLong(
                root,
                listOf("maxExecutionTimeMs", "maxExecutionTime", "max_execution_time_ms", "max_execution_time"),
                AgentDefinitionCode.INVALID_BUDGET,
            )
            val maxArtifactSizeBytes = optionalLong(
                root,
                listOf("maxArtifactSizeBytes", "maxArtifactSize", "max_artifact_size_bytes", "max_artifact_size"),
                AgentBudget.DEFAULT_MAX_ARTIFACT_SIZE_BYTES,
                AgentDefinitionCode.INVALID_BUDGET,
            )
            if (!AgentBudget.isValid(
                    maxDelegationDepth,
                    maxToolCalls,
                    maxExecutionTimeMs,
                    maxArtifactSizeBytes,
                )
            ) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_BUDGET, "agent budget is outside bounds")
            }
            if (workflow.size > maxToolCalls) {
                throw AgentDefinitionException(AgentDefinitionCode.BUDGET_EXCEEDED, "workflow exceeds agent tool budget")
            }

            return AgentDefinition(
                id = id,
                version = version,
                name = name,
                systemPrompt = systemPrompt,
                skills = skills,
                allowedTools = allowedTools,
                permissions = permissions,
                maxDelegationDepth = maxDelegationDepth,
                maxToolCalls = maxToolCalls,
                maxExecutionTimeMs = maxExecutionTimeMs,
                maxArtifactSizeBytes = maxArtifactSizeBytes,
                workflow = workflow,
                delegationTargets = delegationTargets,
                model = model,
                description = description,
            )
        }

        private fun requiredString(root: Map<String, Any?>, key: String): String {
            val value = root[key]
            if (value !is String || value.isBlank() || value != value.trim()) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "$key must be a non-blank string")
            }
            return value
        }

        private fun optionalString(root: Map<String, Any?>, key: String, default: String): String {
            if (!root.containsKey(key)) return default
            val value = root[key]
            if (value !is String || value != value.trim()) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_MANIFEST, "$key must be a string")
            }
            return value
        }

        private fun parseIdentifiers(
            root: Map<String, Any?>,
            keys: List<String>,
            code: AgentDefinitionCode,
        ): List<String> {
            val value = firstPresent(root, keys) ?: return emptyList()
            if (value !is List<*> || value.size > MAX_LIST_ITEMS) {
                throw AgentDefinitionException(code, "identifier list is not bounded")
            }
            val result = mutableListOf<String>()
            for (raw in value) {
                if (raw !is String || !ID_PATTERN.matches(raw) || result.contains(raw)) {
                    throw AgentDefinitionException(code, "invalid or duplicate identifier")
                }
                result += raw
            }
            return result
        }

        private fun parseTools(value: Any?): Set<String> {
            if (value == null) return emptySet()
            if (value !is List<*> || value.size > MAX_LIST_ITEMS) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_TOOL, "allowedTools must be a bounded array")
            }
            val result = linkedSetOf<String>()
            for (raw in value) {
                if (raw !is String || !TOOL_PATTERN.matches(raw) || !result.add(raw)) {
                    throw AgentDefinitionException(AgentDefinitionCode.INVALID_TOOL, "invalid or duplicate allowed tool")
                }
            }
            return result
        }

        private fun parsePermissions(value: Any?): Set<ExtensionPermission> {
            if (value == null) return emptySet()
            if (value !is List<*> || value.size > MAX_LIST_ITEMS) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_PERMISSION, "permissions must be bounded")
            }
            val result = linkedSetOf<ExtensionPermission>()
            for (raw in value) {
                val permission = (raw as? String)?.let(ExtensionPermission::fromWire)
                    ?: throw AgentDefinitionException(AgentDefinitionCode.INVALID_PERMISSION, "unknown agent permission")
                if (!result.add(permission)) {
                    throw AgentDefinitionException(AgentDefinitionCode.INVALID_PERMISSION, "duplicate agent permission")
                }
            }
            return result
        }

        private fun parseWorkflow(value: Any?): List<AgentStep> {
            if (value == null) return emptyList()
            if (value !is List<*> || value.size > MAX_LIST_ITEMS) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "workflow must be bounded")
            }
            return value.map { raw ->
                val step = raw as? Map<*, *>
                    ?: throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "workflow step must be an object")
                val tool = step["tool"] as? String
                    ?: throw AgentDefinitionException(AgentDefinitionCode.INVALID_TOOL, "workflow tool is required")
                if (!TOOL_PATTERN.matches(tool)) {
                    throw AgentDefinitionException(AgentDefinitionCode.INVALID_TOOL, "invalid workflow tool")
                }
                AgentStep(tool, parseArguments(step["arguments"] ?: step["args"]))
            }
        }

        private fun parseArguments(value: Any?): Map<String, String> {
            if (value == null) return emptyMap()
            val objectValue = value as? Map<*, *>
                ?: throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "step arguments must be an object")
            if (objectValue.size > MAX_ARGUMENTS || objectValue.keys.any { it !is String }) {
                throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "step arguments are not bounded")
            }
            val result = linkedMapOf<String, String>()
            for (rawKey in objectValue.keys.filterIsInstance<String>()) {
                if (!TOOL_PATTERN.matches(rawKey)) {
                    throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "invalid step argument name")
                }
                val valueString = objectValue[rawKey] as? String
                    ?: throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "step arguments must be strings")
                if (valueString.length > MAX_ARGUMENT_LENGTH) {
                    throw AgentDefinitionException(AgentDefinitionCode.INVALID_WORKFLOW, "step argument is too long")
                }
                result[rawKey] = valueString
            }
            return result
        }

        private fun requiredInt(root: Map<String, Any?>, keys: List<String>, code: AgentDefinitionCode): Int {
            val number = firstPresent(root, keys) as? DeclarativeJsonNumber
            return number?.raw?.toIntOrNull()
                ?: throw AgentDefinitionException(code, "budget value must be an integer")
        }

        private fun requiredLong(root: Map<String, Any?>, keys: List<String>, code: AgentDefinitionCode): Long {
            val number = firstPresent(root, keys) as? DeclarativeJsonNumber
            return number?.raw?.toLongOrNull()
                ?: throw AgentDefinitionException(code, "budget value must be an integer")
        }

        private fun optionalLong(
            root: Map<String, Any?>,
            keys: List<String>,
            default: Long,
            code: AgentDefinitionCode,
        ): Long {
            if (keys.none(root::containsKey)) return default
            return requiredLong(root, keys, code)
        }

        private fun firstPresent(root: Map<String, Any?>, keys: List<String>): Any? {
            for (key in keys) {
                if (root.containsKey(key)) return root[key]
            }
            return null
        }
    }
}

enum class AgentDefinitionCode {
    INVALID_MANIFEST,
    INVALID_ID,
    INVALID_VERSION,
    INVALID_SKILL,
    INVALID_TOOL,
    INVALID_PERMISSION,
    INVALID_WORKFLOW,
    INVALID_DELEGATION,
    INVALID_BUDGET,
    BUDGET_EXCEEDED,
}

class AgentDefinitionException(
    val code: AgentDefinitionCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
