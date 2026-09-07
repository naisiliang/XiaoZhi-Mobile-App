package com.lchuang.xiaozhimobile.providers

import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonException
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonParser
import com.lchuang.xiaozhimobile.AiEndpointResolver
import com.lchuang.xiaozhimobile.ApiMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ProviderProbeOperation(val capability: ProviderCapability) {
    TEXT(ProviderCapability.TEXT),
    RESPONSES_API(ProviderCapability.RESPONSES_API),
    CHAT_COMPLETIONS(ProviderCapability.CHAT_COMPLETIONS),
    FUNCTION_CALLING(ProviderCapability.FUNCTION_CALLING),
    STRUCTURED_OUTPUT(ProviderCapability.STRUCTURED_OUTPUT),
    VISION(ProviderCapability.VISION),
    IMAGE_GENERATION(ProviderCapability.IMAGE_GENERATION),
    FILE_INPUT(ProviderCapability.FILE_INPUT),
    CODE_INTERPRETER(ProviderCapability.CODE_INTERPRETER),
    MCP(ProviderCapability.MCP),
    NATIVE_SKILLS(ProviderCapability.NATIVE_SKILLS),
}

enum class ProbeFailureKind {
    TIMEOUT,
    UNAVAILABLE,
    HTTP,
    PROTOCOL,
}

sealed interface ProviderProbeResponse {
    data class Supported(
        val httpStatus: Int = 200,
        val nativeSkills: List<String> = emptyList(),
    ) : ProviderProbeResponse

    data class Unsupported(
        val httpStatus: Int? = null,
        val reason: String = "",
    ) : ProviderProbeResponse

    data class Failed(
        val kind: ProbeFailureKind,
        val httpStatus: Int? = null,
        val reason: String = "",
    ) : ProviderProbeResponse
}

data class ProviderProbeRequest(
    val operation: ProviderProbeOperation,
    val method: String,
    val endpoint: String,
    val model: String,
    val apiMode: ApiMode,
    val bodyJson: String,
    val apiKey: String,
) {
    override fun toString(): String =
        "ProviderProbeRequest(operation=$operation, method=$method, endpoint=$endpoint, " +
            "model=$model, apiMode=$apiMode, bodyBytes=${bodyJson.toByteArray(StandardCharsets.UTF_8).size}, " +
            "apiKey=${if (apiKey.isBlank()) "" else REDACTED})"

    companion object {
        private const val REDACTED = "REDACTED"
    }
}

fun interface ProviderCapabilityTransport {
    fun probe(request: ProviderProbeRequest): ProviderProbeResponse
}

/** Probes every provider capability independently and caches only by provider configuration. */
class ProviderCapabilityProbe(
    private val transport: ProviderCapabilityTransport = HttpProviderCapabilityTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(
        val baseUrl: String,
        val model: String,
        val apiMode: ApiMode,
        val apiKeyDigest: String,
    )

    private val cache = linkedMapOf<CacheKey, ProviderCapabilityProfile>()

    @Synchronized
    fun detect(
        config: ProviderConnectionConfig,
        localSkills: Collection<String> = emptyList(),
    ): ProviderCapabilityProfile {
        require(config.validationError() == null) { "provider configuration is invalid" }
        val baseUrl = config.normalizedBaseUrl()
        val normalizedLocalSkills = normalizeLocalSkills(localSkills)
        val key = CacheKey(baseUrl, config.model, config.apiMode, digest(config.apiKey))
        val cached = cache[key]
        if (cached != null) return cached.copy(localSkills = normalizedLocalSkills)

        val capabilities = linkedMapOf<ProviderCapability, CapabilitySupport>()
        var nativeSkills = emptyList<String>()
        for (operation in ProviderProbeOperation.entries) {
            val response = try {
                transport.probe(buildRequest(operation, config, baseUrl))
            } catch (_: Exception) {
                ProviderProbeResponse.Failed(ProbeFailureKind.UNAVAILABLE)
            }
            capabilities[operation.capability] = when (response) {
                is ProviderProbeResponse.Supported -> {
                    if (operation == ProviderProbeOperation.NATIVE_SKILLS) {
                        nativeSkills = normalizeNativeSkills(response.nativeSkills)
                    }
                    CapabilitySupport.SUPPORTED
                }
                is ProviderProbeResponse.Unsupported -> CapabilitySupport.UNSUPPORTED
                is ProviderProbeResponse.Failed -> CapabilitySupport.UNKNOWN
            }
        }

        val profile = ProviderCapabilityProfile(
            baseUrl = baseUrl,
            model = config.model,
            apiMode = config.apiMode,
            capabilities = capabilities.toMap(),
            nativeSkills = nativeSkills,
            checkedAtMs = clock(),
        )
        if (cache.size >= MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
        cache[key] = profile
        return profile.copy(localSkills = normalizedLocalSkills)
    }

    @Synchronized
    fun invalidate() {
        cache.clear()
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 8
        private const val MAX_SKILLS = 64
        private const val MAX_SKILL_LENGTH = 128

        /** Exposed only for deterministic contract tests; production callers use detect(). */
        fun buildRequestForTest(
            operation: ProviderProbeOperation,
            config: ProviderConnectionConfig,
        ): ProviderProbeRequest {
            require(config.validationError() == null) { "provider configuration is invalid" }
            return buildRequest(operation, config, config.normalizedBaseUrl())
        }

        private fun buildRequest(
            operation: ProviderProbeOperation,
            config: ProviderConnectionConfig,
            baseUrl: String,
        ): ProviderProbeRequest {
            val selectedMode = when (config.apiMode) {
                ApiMode.RESPONSES -> ApiMode.RESPONSES
                ApiMode.CHAT_COMPLETIONS, ApiMode.AUTO -> ApiMode.CHAT_COMPLETIONS
            }
            val wireMode = when (operation) {
                ProviderProbeOperation.RESPONSES_API -> ApiMode.RESPONSES
                ProviderProbeOperation.CHAT_COMPLETIONS -> ApiMode.CHAT_COMPLETIONS
                else -> selectedMode
            }
            val method = if (operation == ProviderProbeOperation.NATIVE_SKILLS) "GET" else "POST"
            val endpoint = when (operation) {
                ProviderProbeOperation.RESPONSES_API -> AiEndpointResolver.responsesUrl(baseUrl)
                ProviderProbeOperation.CHAT_COMPLETIONS -> AiEndpointResolver.chatUrl(baseUrl)
                ProviderProbeOperation.IMAGE_GENERATION -> "$baseUrl/v1/images/generations"
                ProviderProbeOperation.NATIVE_SKILLS -> "$baseUrl/v1/skills"
                else -> if (selectedMode == ApiMode.RESPONSES) {
                    AiEndpointResolver.responsesUrl(baseUrl)
                } else {
                    AiEndpointResolver.chatUrl(baseUrl)
                }
            }
            val body = when (operation) {
                ProviderProbeOperation.NATIVE_SKILLS -> objectJson(emptyMap())
                ProviderProbeOperation.TEXT,
                ProviderProbeOperation.RESPONSES_API,
                ProviderProbeOperation.CHAT_COMPLETIONS -> completionBody(wireMode, config.model, "只回复 OK")
                ProviderProbeOperation.FUNCTION_CALLING -> functionCallingBody(wireMode, config.model)
                ProviderProbeOperation.STRUCTURED_OUTPUT -> structuredOutputBody(wireMode, config.model)
                ProviderProbeOperation.VISION -> visionBody(wireMode, config.model)
                ProviderProbeOperation.IMAGE_GENERATION -> objectJson(linkedMapOf(
                    "model" to jsonString(config.model),
                    "prompt" to jsonString("生成一张纯色测试图"),
                    "n" to "1",
                    "size" to jsonString("256x256"),
                ))
                ProviderProbeOperation.FILE_INPUT -> fileBody(wireMode, config.model)
                ProviderProbeOperation.CODE_INTERPRETER -> hostedToolBody(wireMode, config.model, "计算 1+1", "code_interpreter")
                ProviderProbeOperation.MCP -> hostedToolBody(wireMode, config.model, "列出 MCP 工具", "mcp")
            }
            return ProviderProbeRequest(
                operation = operation,
                method = method,
                endpoint = endpoint,
                model = config.model,
                apiMode = wireMode,
                bodyJson = body.toString(),
                apiKey = config.apiKey,
            )
        }

        private fun completionBody(mode: ApiMode, model: String, text: String): String {
            val fields = linkedMapOf("model" to jsonString(model))
            if (mode == ApiMode.RESPONSES) {
                fields["input"] = jsonString(text)
                fields["max_output_tokens"] = "8"
            } else {
                val message = objectJson(linkedMapOf("role" to jsonString("user"), "content" to jsonString(text)))
                fields["messages"] = arrayJson(listOf(message))
                fields["stream"] = "false"
                fields["max_tokens"] = "8"
                fields["temperature"] = "0"
            }
            return objectJson(fields)
        }

        private fun functionCallingBody(mode: ApiMode, model: String): String {
            val parameters = objectJson(linkedMapOf("type" to jsonString("object"), "properties" to objectJson(emptyMap())))
            val function = objectJson(linkedMapOf(
                "name" to jsonString("probe"),
                "description" to jsonString("A harmless capability probe"),
                "parameters" to parameters,
            ))
            val tool = if (mode == ApiMode.RESPONSES) {
                objectJson(linkedMapOf(
                    "type" to jsonString("function"),
                    "name" to jsonString("probe"),
                    "description" to jsonString("A harmless capability probe"),
                    "parameters" to parameters,
                ))
            } else {
                objectJson(linkedMapOf("type" to jsonString("function"), "function" to function))
            }
            return addField(completionBody(mode, model, "调用探测函数"), "tools", arrayJson(listOf(tool)))
        }

        private fun structuredOutputBody(mode: ApiMode, model: String): String {
            val schema = objectJson(linkedMapOf("type" to jsonString("object"), "properties" to objectJson(emptyMap())))
            val format = objectJson(linkedMapOf(
                "type" to jsonString("json_schema"),
                "name" to jsonString("probe"),
                "schema" to schema,
            ))
            val field = if (mode == ApiMode.RESPONSES) {
                "text" to objectJson(linkedMapOf("format" to format))
            } else {
                "response_format" to objectJson(linkedMapOf(
                    "type" to jsonString("json_schema"),
                    "json_schema" to format,
                ))
            }
            return addField(completionBody(mode, model, "返回 JSON"), field.first, field.second)
        }

        private fun hostedToolBody(mode: ApiMode, model: String, prompt: String, type: String): String =
            addField(
                completionBody(mode, model, prompt),
                "tools",
                arrayJson(listOf(objectJson(linkedMapOf("type" to jsonString(type))))),
            )

        private fun visionBody(mode: ApiMode, model: String): String {
            val dataUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            val fields = linkedMapOf<String, String>("model" to jsonString(model))
            if (mode == ApiMode.RESPONSES) {
                val content = arrayJson(listOf(
                    objectJson(linkedMapOf("type" to jsonString("input_text"), "text" to jsonString("描述这张测试图"))),
                    objectJson(linkedMapOf("type" to jsonString("input_image"), "image_url" to jsonString(dataUrl))),
                ))
                fields["input"] = arrayJson(listOf(objectJson(linkedMapOf("role" to jsonString("user"), "content" to content))))
                fields["max_output_tokens"] = "8"
            } else {
                val content = arrayJson(listOf(
                    objectJson(linkedMapOf("type" to jsonString("text"), "text" to jsonString("描述这张测试图"))),
                    objectJson(linkedMapOf(
                        "type" to jsonString("image_url"),
                        "image_url" to objectJson(linkedMapOf("url" to jsonString(dataUrl))),
                    )),
                ))
                fields["messages"] = arrayJson(listOf(objectJson(linkedMapOf("role" to jsonString("user"), "content" to content))))
                fields["max_tokens"] = "8"
            }
            return objectJson(fields)
        }

        private fun fileBody(mode: ApiMode, model: String): String {
            val fileData = "data:text/plain;base64,T0s="
            val fields = linkedMapOf<String, String>("model" to jsonString(model))
            if (mode == ApiMode.RESPONSES) {
                val item = objectJson(linkedMapOf(
                    "type" to jsonString("input_file"),
                    "filename" to jsonString("probe.txt"),
                    "file_data" to jsonString(fileData),
                ))
                val content = arrayJson(listOf(item))
                fields["input"] = arrayJson(listOf(objectJson(linkedMapOf("role" to jsonString("user"), "content" to content))))
                fields["max_output_tokens"] = "8"
            } else {
                val file = objectJson(linkedMapOf("filename" to jsonString("probe.txt"), "file_data" to jsonString(fileData)))
                val content = arrayJson(listOf(objectJson(linkedMapOf("type" to jsonString("file"), "file" to file))))
                fields["messages"] = arrayJson(listOf(objectJson(linkedMapOf("role" to jsonString("user"), "content" to content))))
                fields["max_tokens"] = "8"
            }
            return objectJson(fields)
        }

        private fun addField(json: String, name: String, value: String): String {
            require(json.endsWith('}'))
            return json.dropLast(1) + "," + jsonString(name) + ":" + value + "}"
        }

        private fun objectJson(fields: Map<String, String>): String =
            fields.entries.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:$value" }

        private fun arrayJson(values: Iterable<String>): String = values.joinToString(",", "[", "]")

        private fun jsonString(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

        private fun normalizeLocalSkills(skills: Collection<String>): List<String> {
            require(skills.size <= MAX_SKILLS) { "too many local skills" }
            return skills.map { skill ->
                require(skill.isNotBlank() && skill == skill.trim() && skill.length <= MAX_SKILL_LENGTH) {
                    "invalid local skill id"
                }
                skill
            }.distinct()
        }

        private fun normalizeNativeSkills(skills: List<String>): List<String> =
            skills.asSequence()
                .filter { it.isNotBlank() && it == it.trim() && it.length <= MAX_SKILL_LENGTH }
                .distinct()
                .take(MAX_SKILLS)
                .toList()

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Default transport performs bounded, low-cost HTTP probes and never uploads user files. */
class HttpProviderCapabilityTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : ProviderCapabilityTransport {
    init {
        require(connectTimeoutMs in 1..120_000)
        require(readTimeoutMs in 1..120_000)
    }

    override fun probe(request: ProviderProbeRequest): ProviderProbeResponse {
        val connection = try {
            val uri = URI(request.endpoint)
            if ((!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) ||
                uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                request.method !in setOf("GET", "POST") ||
                request.bodyJson.toByteArray(StandardCharsets.UTF_8).size > MAX_REQUEST_BYTES ||
                request.apiKey.length > MAX_API_KEY_LENGTH
            ) {
                return ProviderProbeResponse.Failed(ProbeFailureKind.UNAVAILABLE)
            }
            (URL(request.endpoint).openConnection() as? HttpURLConnection ?: return ProviderProbeResponse.Failed(ProbeFailureKind.UNAVAILABLE)).apply {
                requestMethod = request.method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                doInput = true
                if (request.method != "GET") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                if (request.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${request.apiKey}")
                }
            }
        } catch (_: Exception) {
            return ProviderProbeResponse.Failed(ProbeFailureKind.UNAVAILABLE)
        }

        return try {
            if (request.method != "GET") {
                connection.outputStream.use { output ->
                    output.write(request.bodyJson.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val body = readBounded(if (code in 200..299) connection.inputStream else connection.errorStream)
            when {
                code in 200..299 && body.isNotBlank() -> ProviderProbeResponse.Supported(
                    httpStatus = code,
                    nativeSkills = if (request.operation == ProviderProbeOperation.NATIVE_SKILLS) parseNativeSkills(body) else emptyList(),
                )
                code in setOf(404, 405, 415, 501) -> ProviderProbeResponse.Unsupported(code)
                code in 200..299 -> ProviderProbeResponse.Failed(ProbeFailureKind.PROTOCOL, code)
                else -> ProviderProbeResponse.Failed(ProbeFailureKind.HTTP, code)
            }
        } catch (_: SocketTimeoutException) {
            ProviderProbeResponse.Failed(ProbeFailureKind.TIMEOUT)
        } catch (_: UnknownHostException) {
            ProviderProbeResponse.Failed(ProbeFailureKind.UNAVAILABLE)
        } catch (_: IOException) {
            ProviderProbeResponse.Failed(ProbeFailureKind.UNAVAILABLE)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream?): String {
        if (input == null) return ""
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) throw IOException("provider response is too large")
                output.write(buffer, 0, read)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun parseNativeSkills(raw: String): List<String> {
        val root = try {
            DeclarativeJsonParser(raw).parseObject()
        } catch (_: DeclarativeJsonException) {
            return emptyList()
        }
        val skills = (root["skills"] as? List<*>)
            ?: ((root["result"] as? Map<*, *>)?.get("skills") as? List<*>)
            ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(skills.size, MAX_SKILLS)) {
                val value = skills[index]
                val name = if (value is Map<*, *>) value["name"] as? String ?: "" else value as? String ?: ""
                if (name.isNotBlank() && name == name.trim() && name.length <= MAX_SKILL_LENGTH && !contains(name)) {
                    add(name)
                }
            }
        }
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_API_KEY_LENGTH = 8 * 1024
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_SKILLS = 64
        private const val MAX_SKILL_LENGTH = 128
    }
}
