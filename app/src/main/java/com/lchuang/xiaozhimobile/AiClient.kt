package com.lchuang.xiaozhimobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AiClient(private val settings: SettingsStore) {
    private val executor = Executors.newSingleThreadExecutor()

    fun ask(text: String, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                val url = settings.apiUrl
                if (url.isBlank()) {
                    callback(Result.failure(IllegalStateException("还没有配置 AI 接口")))
                    return@execute
                }

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (settings.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    }
                }

                val body = JSONObject().apply {
                    put("model", settings.model.ifBlank { "gpt-5.6" })
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", settings.systemPrompt))
                        put(JSONObject().put("role", "user").put("content", text))
                    })
                    put("stream", false)
                    put("temperature", 0.6)
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val input = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = input.bufferedReader().use(BufferedReader::readText)
                if (code !in 200..299) throw IllegalStateException("AI接口 HTTP $code: $raw")

                val json = JSONObject(raw)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    .orEmpty()

                if (content.isBlank()) throw IllegalStateException("AI返回内容为空")
                callback(Result.success(content))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }
}
