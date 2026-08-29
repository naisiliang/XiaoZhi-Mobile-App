package com.lchuang.xiaozhimobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

class AiClient(private val settings: SettingsStore) {
    private val executor = Executors.newSingleThreadExecutor()

    private data class HttpResult(val code: Int, val raw: String, val latencyMs: Long)
    private class HttpFailure(val code: Int, val body: String, val latencyMs: Long) : RuntimeException("HTTP $code")
    private class ProtocolFailure(message: String) : RuntimeException(message)

    fun testEndpoint(callback: (AiEndpointTestResult) -> Unit) {
        executor.execute {
            val started = System.currentTimeMillis()
            try {
                val base = AiEndpointResolver.normalizeBaseUrl(settings.apiBaseUrl)
                val requestedMode = settings.apiMode
                val result = when (requestedMode) {
                    ApiMode.CHAT_COMPLETIONS -> testChat(base)
                    ApiMode.RESPONSES -> testResponses(base)
                    ApiMode.AUTO -> {
                        try {
                            testChat(base)
                        } catch (e: HttpFailure) {
                            if (e.code == 404 || e.code == 405) testResponses(base) else throw e
                        } catch (_: ProtocolFailure) {
                            testResponses(base)
                        }
                    }
                }
                callback(result)
            } catch (e: Throwable) {
                callback(
                    AiEndpointTestResult(
                        success = false,
                        httpStatus = (e as? HttpFailure)?.code,
                        mode = settings.apiMode.takeUnless { it == ApiMode.AUTO },
                        model = settings.model,
                        latencyMs = System.currentTimeMillis() - started,
                        reply = "",
                        error = sanitizeError(e)
                    )
                )
            }
        }
    }

    fun complete(
        messages: List<ConversationMessage>,
        tools: List<AiToolDefinition> = emptyList(),
        callback: (Result<RawAiResponse>) -> Unit
    ) {
        executor.execute {
            try {
                val base = AiEndpointResolver.normalizeBaseUrl(settings.apiBaseUrl)
                val result = when (settings.apiMode) {
                    ApiMode.CHAT_COMPLETIONS -> performChat(base, messages, tools)
                    ApiMode.RESPONSES -> performResponses(base, messages, tools)
                    ApiMode.AUTO -> {
                        try {
                            performChat(base, messages, tools)
                        } catch (e: HttpFailure) {
                            if (e.code == 404 || e.code == 405) performResponses(base, messages, tools) else throw e
                        } catch (_: ProtocolFailure) {
                            performResponses(base, messages, tools)
                        }
                    }
                }
                callback(Result.success(result))
            } catch (e: Throwable) {
                callback(Result.failure(IllegalStateException(sanitizeError(e))))
            }
        }
    }

    fun ask(text: String, callback: (Result<String>) -> Unit) {
        val messages = listOf(
            ConversationMessage("system", settings.systemPrompt),
            ConversationMessage("user", text)
        )
        complete(messages, emptyList()) { result ->
            callback(result.map { response -> response.text.ifBlank { throw IllegalStateException("AI返回内容为空") } })
        }
    }

    private fun testChat(base: String): AiEndpointTestResult {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "只回复：OK")))
            put("stream", false)
            put("max_tokens", 8)
            put("temperature", 0)
        }
        val http = post(AiEndpointResolver.chatUrl(base), body)
        if (http.code !in 200..299) throw HttpFailure(http.code, http.raw, http.latencyMs)
        val content = parseChatText(JSONObject(http.raw))
        if (content.isBlank()) throw ProtocolFailure("Chat Completions 返回格式异常")
        return AiEndpointTestResult(true, http.code, ApiMode.CHAT_COMPLETIONS, settings.model, http.latencyMs, content)
    }

    private fun testResponses(base: String): AiEndpointTestResult {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("input", "只回复：OK")
            put("max_output_tokens", 8)
        }
        val http = post(AiEndpointResolver.responsesUrl(base), body)
        if (http.code !in 200..299) throw HttpFailure(http.code, http.raw, http.latencyMs)
        val content = parseResponsesText(JSONObject(http.raw))
        if (content.isBlank()) throw ProtocolFailure("Responses 返回格式异常")
        return AiEndpointTestResult(true, http.code, ApiMode.RESPONSES, settings.model, http.latencyMs, content)
    }

    private fun performChat(base: String, messages: List<ConversationMessage>, tools: List<AiToolDefinition>): RawAiResponse {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().apply {
                messages.forEach { m -> put(JSONObject().put("role", m.role).put("content", m.content)) }
            })
            put("stream", false)
            put("temperature", 0.4)
            if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(toolDefinitionJson(it)) } })
        }
        val http = post(AiEndpointResolver.chatUrl(base), body)
        if (http.code !in 200..299) throw HttpFailure(http.code, http.raw, http.latencyMs)
        val json = JSONObject(http.raw)
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ProtocolFailure("Chat Completions 缺少 message")
        val toolCalls = parseChatToolCalls(message)
        val text = message.optString("content", "").trim()
        if (toolCalls.isEmpty() && text.isBlank()) throw ProtocolFailure("Chat Completions 没有可用输出")
        return RawAiResponse(ApiMode.CHAT_COMPLETIONS, http.code, text, toolCalls, http.raw)
    }

    private fun performResponses(base: String, messages: List<ConversationMessage>, tools: List<AiToolDefinition>): RawAiResponse {
        val input = JSONArray().apply {
            messages.forEach { m -> put(JSONObject().put("role", m.role).put("content", m.content)) }
        }
        val body = JSONObject().apply {
            put("model", settings.model)
            put("input", input)
            if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(responseToolDefinitionJson(it)) } })
        }
        val http = post(AiEndpointResolver.responsesUrl(base), body)
        if (http.code !in 200..299) throw HttpFailure(http.code, http.raw, http.latencyMs)
        val json = JSONObject(http.raw)
        val calls = parseResponsesToolCalls(json)
        val text = parseResponsesText(json)
        if (calls.isEmpty() && text.isBlank()) throw ProtocolFailure("Responses 没有可用输出")
        return RawAiResponse(ApiMode.RESPONSES, http.code, text, calls, http.raw)
    }

    private fun post(url: String, body: JSONObject): HttpResult {
        val started = System.currentTimeMillis()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = input?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return HttpResult(code, raw, System.currentTimeMillis() - started)
    }

    private fun parseChatText(json: JSONObject): String = json.optJSONArray("choices")
        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()

    private fun parseResponsesText(json: JSONObject): String {
        json.optString("output_text", "").trim().takeIf { it.isNotBlank() }?.let { return it }
        val output = json.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                val text = item.optString("text", "").trim()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun parseChatToolCalls(message: JSONObject): List<AiToolCall> {
        val array = message.optJSONArray("tool_calls") ?: return emptyList()
        val out = mutableListOf<AiToolCall>()
        for (i in 0 until array.length()) {
            val fn = array.optJSONObject(i)?.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isBlank()) continue
            val args = jsonObjectToMap(runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrElse { JSONObject() })
            out += AiToolCall(name, args)
        }
        return out
    }

    private fun parseResponsesToolCalls(json: JSONObject): List<AiToolCall> {
        val output = json.optJSONArray("output") ?: return emptyList()
        val out = mutableListOf<AiToolCall>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "function_call") continue
            val name = item.optString("name", "").trim()
            if (name.isBlank()) continue
            val args = jsonObjectToMap(runCatching { JSONObject(item.optString("arguments", "{}")) }.getOrElse { JSONObject() })
            out += AiToolCall(name, args)
        }
        return out
    }

    private fun toolDefinitionJson(def: AiToolDefinition): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", responseToolDefinitionJson(def))
    }

    private fun responseToolDefinitionJson(def: AiToolDefinition): JSONObject = JSONObject().apply {
        put("name", def.name)
        put("description", def.description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                def.properties.forEach { (name, type) ->
                    put(name, JSONObject().put("type", type))
                }
            })
            put("required", JSONArray(def.required))
            put("additionalProperties", false)
        })
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

    private fun sanitizeError(error: Throwable): String = when (error) {
        is HttpFailure -> when (error.code) {
            401 -> "API Key 无效或未授权"
            403 -> "接口拒绝访问"
            404, 405 -> "接口地址不支持"
            429 -> "请求过多或额度不足"
            in 500..599 -> "上游服务异常"
            else -> "AI 接口 HTTP ${error.code}"
        }
        is SocketTimeoutException -> "连接或响应超时"
        is UnknownHostException -> "域名解析失败"
        is IllegalArgumentException -> error.message ?: "AI 配置无效"
        else -> error.message?.take(180) ?: "AI 请求失败"
    }
}
