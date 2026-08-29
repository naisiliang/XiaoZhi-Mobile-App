package com.lchuang.xiaozhimobile

import org.json.JSONObject

class AiOrchestrator(
    private val settings: SettingsStore,
    private val client: AiClient
) {
    private val allowedTools = setOf(
        "open_app", "navigate", "search_nearby", "open_web",
        "media_play", "media_pause", "media_next", "media_previous",
        "volume_up", "volume_down", "set_volume", "flashlight_on", "flashlight_off"
    )

    private val toolDefinitions = listOf(
        AiToolDefinition("open_app", "打开手机上已安装的应用", mapOf("name" to "string"), listOf("name")),
        AiToolDefinition("navigate", "使用地图导航到目的地", mapOf("destination" to "string", "mapApp" to "string"), listOf("destination")),
        AiToolDefinition("search_nearby", "在附近搜索地点类别或商家", mapOf("keyword" to "string", "mapApp" to "string"), listOf("keyword")),
        AiToolDefinition("open_web", "打开网页或搜索关键词", mapOf("query_or_url" to "string"), listOf("query_or_url")),
        AiToolDefinition("media_play", "播放或继续音乐"),
        AiToolDefinition("media_pause", "暂停音乐"),
        AiToolDefinition("media_next", "播放下一首"),
        AiToolDefinition("media_previous", "播放上一首"),
        AiToolDefinition("volume_up", "调大媒体音量"),
        AiToolDefinition("volume_down", "调小媒体音量"),
        AiToolDefinition("set_volume", "把媒体音量设为百分比", mapOf("percent" to "integer"), listOf("percent")),
        AiToolDefinition("flashlight_on", "打开手电筒"),
        AiToolDefinition("flashlight_off", "关闭手电筒")
    )

    fun respond(userText: String, memory: AiConversationMemory, callback: (Result<AiOutcome>) -> Unit) {
        val clean = userText.trim()
        if (clean.isBlank()) {
            callback(Result.failure(IllegalArgumentException("用户输入为空")))
            return
        }

        val messages = mutableListOf<ConversationMessage>()
        messages += ConversationMessage("system", plannerInstruction(nativeTools = true))
        messages += memory.messages()
        messages += ConversationMessage("user", clean)
        client.complete(messages, toolDefinitions) { first ->
            if (first.isSuccess) {
                callback(normalizeNativeResult(first.getOrThrow()))
                return@complete
            }
            val message = first.exceptionOrNull()?.message.orEmpty()
            if (!shouldTryJsonFallback(message)) {
                callback(Result.failure(first.exceptionOrNull() ?: IllegalStateException("AI 请求失败")))
                return@complete
            }
            val fallbackMessages = mutableListOf<ConversationMessage>()
            fallbackMessages += ConversationMessage("system", plannerInstruction(nativeTools = false))
            fallbackMessages += memory.messages()
            fallbackMessages += ConversationMessage("user", clean)
            client.complete(fallbackMessages, emptyList()) { second ->
                if (second.isFailure) {
                    callback(Result.failure(second.exceptionOrNull() ?: IllegalStateException("AI 请求失败")))
                    return@complete
                }
                val raw = second.getOrThrow()
                callback(parseStrictFallback(raw.text))
            }
        }
    }

    fun classifyExitIntent(userText: String, callback: (Result<ExitDecision>) -> Unit) {
        val clean = userText.trim()
        if (clean.isBlank()) {
            callback(Result.success(ExitDecision.CONTINUE))
            return
        }
        val identity = settings.assistantName.ifBlank { "小智" }
        val instruction = """
            你只判断用户是否明确想结束与${identity}当前这一次对话。
            如果是，只回复 EXIT。
            如果不是或不确定，只回复 CONTINUE。
            “退出微信/退出登录/关闭某个应用”不是退出助手，必须回复 CONTINUE。
            禁止输出解释、JSON、Markdown或工具调用。
        """.trimIndent()
        val messages = listOf(
            ConversationMessage("system", instruction),
            ConversationMessage("user", clean)
        )
        client.complete(messages, emptyList()) { result ->
            if (result.isFailure) {
                callback(Result.failure(result.exceptionOrNull() ?: IllegalStateException("AI退出判断失败")))
                return@complete
            }
            when (result.getOrThrow().text.trim().uppercase()) {
                "EXIT" -> callback(Result.success(ExitDecision.EXIT))
                "CONTINUE" -> callback(Result.success(ExitDecision.CONTINUE))
                else -> callback(Result.failure(IllegalStateException("AI退出判断格式错误")))
            }
        }
    }

    private fun normalizeNativeResult(raw: RawAiResponse): Result<AiOutcome> {
        // Native OpenAI Chat Completions exposes tool_calls; Responses is normalized by AiClient.
        raw.toolCalls.firstOrNull()?.let { call ->
            if (call.tool !in allowedTools) return Result.failure(IllegalStateException("AI 返回了未授权工具"))
            return Result.success(AiOutcome.Tool(call))
        }
        val text = raw.text.trim()
        if (text.isBlank()) return Result.failure(IllegalStateException("AI 返回内容为空"))
        val maybeJson = parseStrictFallbackOrNull(text)
        return if (maybeJson != null) Result.success(maybeJson) else Result.success(AiOutcome.Reply(text))
    }

    private fun parseStrictFallback(text: String): Result<AiOutcome> {
        val outcome = parseStrictFallbackOrNull(text)
            ?: return Result.failure(IllegalStateException("AI JSON 规划格式不正确"))
        return Result.success(outcome)
    }

    private fun parseStrictFallbackOrNull(text: String): AiOutcome? {
        val clean = stripWholeCodeFence(text.trim())
        if (!clean.startsWith("{") || !clean.endsWith("}")) return null
        val json = runCatching { JSONObject(clean) }.getOrNull() ?: return null
        return when (json.optString("type")) {
            "reply" -> {
                val reply = json.optString("text", "").trim()
                if (reply.isBlank()) null else AiOutcome.Reply(reply)
            }
            "tool_call" -> {
                val tool = json.optString("tool", "").trim()
                if (tool !in allowedTools) return null
                val argsJson = json.optJSONObject("args") ?: return null
                AiOutcome.Tool(AiToolCall(tool, jsonObjectToMap(argsJson)))
            }
            else -> null
        }
    }

    private fun stripWholeCodeFence(text: String): String {
        if (!text.startsWith("```")) return text
        val lines = text.lines()
        if (lines.size < 3 || !lines.last().trim().startsWith("```")) return text
        return lines.drop(1).dropLast(1).joinToString("\n").trim()
    }

    private fun shouldTryJsonFallback(error: String): Boolean {
        val lower = error.lowercase()
        return lower.contains("http 400") || lower.contains("http 404") ||
            lower.contains("http 405") || lower.contains("格式") || lower.contains("protocol")
    }

    private fun plannerInstruction(nativeTools: Boolean): String {
        val identity = settings.assistantName.ifBlank { "小智" }
        val base = """
            你是安卓手机上的$identity。回答简洁自然，适合语音播报。
            当用户要求执行手机动作时，只能选择已经提供的安全工具；不能声称动作已经成功，必须等待本地执行结果。
            禁止建议或调用删除数据、发送消息、付款转账、安装卸载应用、修改密码、读取隐私数据、运行 shell 或任意 Intent/URI。
            普通问答使用中文直接回答。
        """.trimIndent()
        if (nativeTools) return base + "\n如果接口支持 tool_calls，请优先使用一个最合适的工具；每次最多一个工具。"
        return base + """

            当前接口不使用原生工具调用。你必须只输出一个严格 JSON 对象，不要输出解释或 Markdown：
            普通回答：{"type":"reply","text":"回答内容"}
            手机动作：{"type":"tool_call","tool":"navigate","args":{"destination":"广州南站","mapApp":"高德地图"}}
            tool 只能是：${allowedTools.joinToString(",")}
        """.trimIndent()
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.opt(key).takeUnless { it == JSONObject.NULL }
        }
        return out
    }
}
