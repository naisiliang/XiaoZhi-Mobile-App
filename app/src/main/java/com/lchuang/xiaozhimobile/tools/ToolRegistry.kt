package com.lchuang.xiaozhimobile.tools

import com.lchuang.xiaozhimobile.AiToolDefinition
import com.lchuang.xiaozhimobile.messaging.MessagingToolAdapter
import java.util.Locale

/**
 * App-owned declarations exposed to extension-facing tool callers.
 *
 * Existing device tools stay in the legacy AI planner until their registry
 * migration task. New messaging capabilities enter here as one declarative
 * ordinary-text tool; this registry contains no executor references.
 */
object ToolRegistry {
    private val registeredDefinitions = listOf(MessagingToolAdapter().definition())

    fun definitions(): List<AiToolDefinition> = registeredDefinitions.toList()

    fun definitionFor(name: String): AiToolDefinition? {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return registeredDefinitions.firstOrNull { definition ->
            definition.name.lowercase(Locale.ROOT) == normalized
        }
    }
}
