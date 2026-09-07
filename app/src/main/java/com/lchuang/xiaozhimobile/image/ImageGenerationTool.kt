package com.lchuang.xiaozhimobile.image

import android.util.Base64
import com.lchuang.xiaozhimobile.artifacts.Artifact
import com.lchuang.xiaozhimobile.artifacts.ArtifactDigest
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationCode
import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationResult
import com.lchuang.xiaozhimobile.artifacts.generators.generateValidatedArtifact
import com.lchuang.xiaozhimobile.providers.ProviderCapability
import com.lchuang.xiaozhimobile.providers.ProviderCapabilityProfile
import com.lchuang.xiaozhimobile.providers.ProviderConnectionConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import org.json.JSONObject

enum class ImageGenerationCode {
    INVALID_INTENT,
    INVALID_PROVIDER,
    CAPABILITY_UNAVAILABLE,
    INVALID_CONTEXT,
    PROVIDER_FAILED,
    INVALID_IMAGE,
    SIZE_LIMIT,
    STORAGE_FAILED,
}

sealed interface ImageGenerationResult {
    data class Completed(val image: ImageArtifact) : ImageGenerationResult

    data class Rejected(
        val code: ImageGenerationCode,
        val detail: String = "",
    ) : ImageGenerationResult
}

data class ImageProviderRequest(
    val endpoint: String,
    val model: String,
    val mode: ImageIntentMode,
    val prompt: String,
    val size: String,
    val sourceImageBytes: ByteArray? = null,
    val apiKey: String = "",
) {
    override fun toString(): String =
        "ImageProviderRequest(endpoint=$endpoint, model=$model, mode=$mode, promptLength=${prompt.length}, " +
            "size=$size, sourceImageBytes=${sourceImageBytes?.size ?: 0}, apiKey=${if (apiKey.isBlank()) "" else "REDACTED"})"
}

sealed interface ImageProviderResult {
    data class Success(
        val bytes: ByteArray,
        val mimeType: String? = null,
    ) : ImageProviderResult

    data class Failure(val reason: String = "") : ImageProviderResult
}

fun interface ImageGenerationProvider {
    fun generate(request: ImageProviderRequest): ImageProviderResult
}

class ImageGenerationTool(
    private val workspace: ArtifactWorkspace,
    private val repository: ArtifactRepository,
    private val provider: ImageGenerationProvider = HttpImageGenerationProvider(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun generate(
        intent: ImageIntent,
        provider: ProviderConnectionConfig,
        capabilities: ProviderCapabilityProfile,
        sessionId: String,
        sourceAgent: String,
        size: String = DEFAULT_SIZE,
        displayName: String? = null,
    ): ImageGenerationResult {
        if (intent.prompt.length > MAX_PROMPT_LENGTH || intent.prompt.any { it.code < 0x20 || it == '\u007f' }) {
            return ImageGenerationResult.Rejected(ImageGenerationCode.INVALID_INTENT, "image prompt is invalid")
        }
        val configError = provider.validationError()
        if (configError != null || size !in SUPPORTED_SIZES) {
            return ImageGenerationResult.Rejected(ImageGenerationCode.INVALID_PROVIDER, "image provider configuration is invalid")
        }
        val baseUrl = runCatching { provider.normalizedBaseUrl() }.getOrNull()
            ?: return ImageGenerationResult.Rejected(ImageGenerationCode.INVALID_PROVIDER, "image provider configuration is invalid")
        if (capabilities.baseUrl != baseUrl || capabilities.model != provider.model || capabilities.apiMode != provider.apiMode) {
            return ImageGenerationResult.Rejected(ImageGenerationCode.INVALID_PROVIDER, "image capability profile is stale")
        }
        if (!capabilities.supports(ProviderCapability.IMAGE_GENERATION)) {
            return ImageGenerationResult.Rejected(
                ImageGenerationCode.CAPABILITY_UNAVAILABLE,
                "provider does not support image generation",
            )
        }
        if (intent.mode == ImageIntentMode.CREATE && intent.imageContext != null) {
            return ImageGenerationResult.Rejected(ImageGenerationCode.INVALID_INTENT, "create intent cannot carry image context")
        }

        val source = when (intent.mode) {
            ImageIntentMode.CREATE -> null
            ImageIntentMode.EDIT,
            ImageIntentMode.REGENERATE,
            -> loadImageContext(intent.imageContext)
                ?: return ImageGenerationResult.Rejected(
                    ImageGenerationCode.INVALID_CONTEXT,
                    "image edit context is unavailable",
                )
        }
        val request = ImageProviderRequest(
            endpoint = baseUrl + if (source == null) "/v1/images/generations" else "/v1/images/edits",
            model = provider.model,
            mode = intent.mode,
            prompt = intent.prompt,
            size = size,
            sourceImageBytes = source?.bytes,
            apiKey = provider.apiKey,
        )
        val response = try {
                this.provider.generate(request)
        } catch (error: Exception) {
            ImageProviderResult.Failure(error.message.orEmpty())
        }
        if (response is ImageProviderResult.Failure) {
            return ImageGenerationResult.Rejected(
                ImageGenerationCode.PROVIDER_FAILED,
                sanitizeProviderDetail(response.reason, provider.apiKey),
            )
        }
        val success = response as ImageProviderResult.Success
        if (success.bytes.size.toLong() > ImageArtifactValidator.MAX_IMAGE_BYTES) {
            return ImageGenerationResult.Rejected(ImageGenerationCode.SIZE_LIMIT, "generated image exceeds the size limit")
        }
        val format = ImageArtifactValidator.formatFor(success.bytes, success.mimeType)
            ?: return ImageGenerationResult.Rejected(ImageGenerationCode.INVALID_IMAGE, "provider returned invalid image data")
        val result = generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName ?: "小白生成图片.${format.extension}",
            mimeType = format.mimeType,
            extension = format.extension,
            maxBytes = ImageArtifactValidator.MAX_IMAGE_BYTES,
            clock = clock,
            write = { output -> output.write(success.bytes) },
            validate = { file -> ImageArtifactValidator.validate(file, format.mimeType) },
        )
        return when (result) {
            is ArtifactGenerationResult.Completed -> ImageGenerationResult.Completed(
                ImageArtifact(
                    artifact = result.artifact,
                    prompt = intent.prompt,
                    mode = intent.mode,
                    sourceArtifactId = intent.imageContext?.artifactId,
                    sourceVersion = intent.imageContext?.version,
                ),
            )
            is ArtifactGenerationResult.Rejected -> ImageGenerationResult.Rejected(
                result.toImageCode(),
                result.detail,
            )
        }
    }

    private fun loadImageContext(context: ImageGenerationContext?): LoadedImage? {
        val artifact = context?.artifact ?: return null
        if (artifact.artifactId != context.artifactId || artifact.version != context.version ||
            artifact.status != com.lchuang.xiaozhimobile.artifacts.ArtifactStatus.COMPLETED ||
            !artifact.mimeType.startsWith("image/")
        ) return null
        val file = File(artifact.privatePath).canonicalFile
        if (!workspace.isPrivate(file) || !file.isFile || file.length() != artifact.size ||
            file.length() > ImageArtifactValidator.MAX_IMAGE_BYTES
        ) return null
        if (!runCatching { ArtifactDigest.sha256(file) == artifact.sha256 }.getOrDefault(false)) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (ImageArtifactValidator.formatFor(bytes, artifact.mimeType) == null) return null
        return LoadedImage(bytes)
    }

    private fun ArtifactGenerationResult.Rejected.toImageCode(): ImageGenerationCode = when (code) {
        ArtifactGenerationCode.SIZE_LIMIT -> ImageGenerationCode.SIZE_LIMIT
        ArtifactGenerationCode.VALIDATION_FAILED -> ImageGenerationCode.INVALID_IMAGE
        ArtifactGenerationCode.STORAGE_FAILED -> ImageGenerationCode.STORAGE_FAILED
        ArtifactGenerationCode.INVALID_INPUT -> ImageGenerationCode.INVALID_INTENT
    }

    private data class LoadedImage(val bytes: ByteArray)

    companion object {
        private const val DEFAULT_SIZE = "1024x1024"
        private const val MAX_PROMPT_LENGTH = 4 * 1024
        private val SUPPORTED_SIZES = setOf("256x256", "512x512", "1024x1024")
        private val BEARER_PATTERN = Regex("(?i)bearer\\s+\\S+")
        private val SECRET_PATTERN = Regex("(?i)\\bsk-[a-z0-9_-]+\\b")
        private val DATA_PATTERN = Regex("(?i)data:[^;\\s]+;base64,[a-z0-9+/=]+")

        private fun sanitizeProviderDetail(raw: String, apiKey: String): String {
            val sanitized = (if (apiKey.isBlank()) raw else raw.replace(apiKey, "REDACTED"))
                .replace(BEARER_PATTERN, "Bearer REDACTED")
                .replace(SECRET_PATTERN, "REDACTED")
                .replace(DATA_PATTERN, "image data REDACTED")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(180)
            return sanitized.ifBlank { "image provider failed" }
        }
    }
}

/** HTTP provider for standard JSON image generation and multipart image-edit endpoints. */
class HttpImageGenerationProvider(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : ImageGenerationProvider {
    init {
        require(connectTimeoutMs in 1..120_000)
        require(readTimeoutMs in 1..120_000)
    }

    override fun generate(request: ImageProviderRequest): ImageProviderResult {
        return try {
            val endpoint = URI(request.endpoint)
            if ((endpoint.scheme != "http" && endpoint.scheme != "https") || endpoint.host.isNullOrBlank()) {
                ImageProviderResult.Failure("invalid image provider endpoint")
            } else {
                val connection = (URL(request.endpoint).openConnection() as? HttpURLConnection)
                    ?: return ImageProviderResult.Failure("image provider is unavailable")
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                if (request.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${request.apiKey}")
                if (request.sourceImageBytes == null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { output -> output.write(jsonBody(request).toByteArray(Charsets.UTF_8)) }
                } else {
                    val boundary = "----XiaoZhiImage${UUID.randomUUID().toString().replace("-", "")}"
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    connection.outputStream.use { output -> writeMultipart(output, boundary, request) }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    ImageProviderResult.Failure("image provider HTTP $status")
                } else {
                    val body = readBounded(connection.inputStream, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
                    parseResponse(body, endpoint)
                }
            }
        } catch (_: IOException) {
            ImageProviderResult.Failure("image provider network failure")
        } catch (_: RuntimeException) {
            ImageProviderResult.Failure("image provider response was invalid")
        }
    }

    private fun jsonBody(request: ImageProviderRequest): String = JSONObject()
        .put("model", request.model)
        .put("prompt", request.prompt)
        .put("n", 1)
        .put("size", request.size)
        .put("response_format", "b64_json")
        .toString()

    private fun writeMultipart(output: OutputStream, boundary: String, request: ImageProviderRequest) {
        writeField(output, boundary, "model", request.model)
        writeField(output, boundary, "prompt", request.prompt)
        writeField(output, boundary, "n", "1")
        writeField(output, boundary, "size", request.size)
        writeField(output, boundary, "response_format", "b64_json")
        val bytes = request.sourceImageBytes ?: throw IOException("missing image edit source")
        if (bytes.size.toLong() > ImageArtifactValidator.MAX_IMAGE_BYTES) throw IOException("image source exceeds the size limit")
        val format = ImageArtifactValidator.formatFor(bytes)
            ?: throw IOException("invalid image edit source")
        output.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Disposition: form-data; name=\"image\"; filename=\"source.${format.extension}\"\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Type: ${format.mimeType}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun writeField(output: OutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun parseResponse(body: String, origin: URI): ImageProviderResult {
        val data = JSONObject(body).optJSONArray("data")
            ?: return ImageProviderResult.Failure("image provider response missing data")
        val item = data.optJSONObject(0) ?: return ImageProviderResult.Failure("image provider response missing image")
        val declaredMime = item.optString("mime_type", "").ifBlank { null }
        val encoded = item.optString("b64_json", "").trim()
        if (encoded.isNotBlank()) {
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                ?: return ImageProviderResult.Failure("image provider returned invalid image data")
            return ImageProviderResult.Success(bytes, declaredMime)
        }
        val url = item.optString("url", "").trim()
        if (url.startsWith("data:", ignoreCase = true)) {
            val separator = url.indexOf(',')
            if (separator <= 5 || !url.substring(0, separator).contains(";base64", ignoreCase = true)) {
                return ImageProviderResult.Failure("image provider returned an invalid data URL")
            }
            val mime = url.substring(5, separator).substringBefore(';').trim().ifBlank { null }
            val bytes = runCatching { Base64.decode(url.substring(separator + 1), Base64.DEFAULT) }.getOrNull()
                ?: return ImageProviderResult.Failure("image provider returned invalid image data")
            return ImageProviderResult.Success(bytes, mime)
        }
        if (url.isBlank()) return ImageProviderResult.Failure("image provider response missing image data")
        return downloadSameOrigin(url, origin)
    }

    private fun downloadSameOrigin(rawUrl: String, origin: URI): ImageProviderResult {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return ImageProviderResult.Failure("image provider returned an invalid URL")
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank() ||
            uri.userInfo != null || !uri.host.equals(origin.host, ignoreCase = true) ||
            !uri.scheme.equals(origin.scheme, ignoreCase = true) ||
            (uri.port != -1 && uri.port != origin.port && !(uri.port == 443 && origin.port == -1 && uri.scheme == "https"))
        ) return ImageProviderResult.Failure("image provider returned an untrusted URL")
        val connection = (URL(rawUrl).openConnection() as? HttpURLConnection)
            ?: return ImageProviderResult.Failure("image provider image URL is unavailable")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        val status = connection.responseCode
        if (status !in 200..299) return ImageProviderResult.Failure("image provider image URL HTTP $status")
        val bytes = readBounded(connection.inputStream, ImageArtifactValidator.MAX_IMAGE_BYTES)
        val mime = connection.contentType?.substringBefore(';')?.trim()?.ifBlank { null }
        return ImageProviderResult.Success(bytes, mime)
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > maxBytes) throw IOException("image response exceeds the size limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        // A base64 JSON response is approximately 4/3 the decoded image size.
        private val MAX_RESPONSE_BYTES =
            ((ImageArtifactValidator.MAX_IMAGE_BYTES + 2L) / 3L) * 4L + 64L * 1024L
    }
}
