package com.lchuang.xiaozhimobile

enum class ApiMode { AUTO, CHAT_COMPLETIONS, RESPONSES }
enum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }

data class ConversationMessage(val role: String, val content: String)

data class AiToolCall(val tool: String, val args: Map<String, Any?>)

data class AiToolDefinition(
    val name: String,
    val description: String,
    val properties: Map<String, String> = emptyMap(),
    val required: List<String> = emptyList()
)

sealed class AiOutcome {
    data class Reply(val text: String) : AiOutcome()
    data class Tool(val call: AiToolCall) : AiOutcome()
}

data class AiEndpointTestResult(
    val success: Boolean,
    val httpStatus: Int?,
    val mode: ApiMode?,
    val model: String,
    val latencyMs: Long,
    val reply: String,
    val error: String = ""
)

data class RawAiResponse(
    val mode: ApiMode,
    val httpStatus: Int,
    val text: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    val raw: String = ""
)

data class ToolExecutionResult(
    val success: Boolean,
    val spokenText: String,
    val debugCode: String
)
