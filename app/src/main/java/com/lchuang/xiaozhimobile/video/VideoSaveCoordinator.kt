package com.lchuang.xiaozhimobile.video

import java.net.URI
import java.util.Locale

enum class VideoSaveRoute {
    APP_NATIVE_SAVE,
    ANDROID_SHARE,
    PUBLIC_URL_TO_MEDIA_STORE,
    BLOCKED,
}

enum class VideoSaveCode {
    SAVED_NATIVE,
    SHARED,
    SAVED_PUBLIC_URL,
    BLOCKED_POLICY,
    INVALID_PACKAGE,
    INVALID_SHARE_URI,
    INVALID_PUBLIC_URL,
    NATIVE_SAVE_FAILED,
    SHARE_FAILED,
    PUBLIC_SAVE_FAILED,
}

data class VideoSaveRequest(
    val route: VideoSaveRoute,
    val packageName: String? = null,
    val contentUri: String? = null,
    val publicUrl: String? = null,
    val reason: String? = null,
) {
    override fun toString(): String =
        "VideoSaveRequest(route=$route, packagePresent=${!packageName.isNullOrBlank()}, " +
            "contentUriPresent=${!contentUri.isNullOrBlank()}, publicUrlPresent=${!publicUrl.isNullOrBlank()})"
}

data class VideoSaveResult(
    val success: Boolean,
    val code: VideoSaveCode,
)

/** Platform operations are injected; this coordinator never downloads or bypasses app controls. */
interface VideoSaveOperations {
    fun appNativeSave(packageName: String): Boolean

    fun androidShare(contentUri: String): Boolean

    fun savePublicUrlToMediaStore(publicUrl: String): Boolean
}

class VideoSaveCoordinator(
    private val operations: VideoSaveOperations,
) {
    fun execute(request: VideoSaveRequest): VideoSaveResult = when (request.route) {
        VideoSaveRoute.BLOCKED -> VideoSaveResult(false, VideoSaveCode.BLOCKED_POLICY)
        VideoSaveRoute.APP_NATIVE_SAVE -> executeNative(request)
        VideoSaveRoute.ANDROID_SHARE -> executeShare(request)
        VideoSaveRoute.PUBLIC_URL_TO_MEDIA_STORE -> executePublicUrl(request)
    }

    private fun executeNative(request: VideoSaveRequest): VideoSaveResult {
        val packageName = request.packageName?.trim()?.takeIf(String::isNotBlank)
            ?: return VideoSaveResult(false, VideoSaveCode.INVALID_PACKAGE)
        val success = runCatching { operations.appNativeSave(packageName) }.getOrDefault(false)
        return VideoSaveResult(
            success = success,
            code = if (success) VideoSaveCode.SAVED_NATIVE else VideoSaveCode.NATIVE_SAVE_FAILED,
        )
    }

    private fun executeShare(request: VideoSaveRequest): VideoSaveResult {
        val contentUri = request.contentUri?.trim()?.takeIf(String::isNotBlank)
            ?: return VideoSaveResult(false, VideoSaveCode.INVALID_SHARE_URI)
        if (!isShareableUri(contentUri)) {
            return VideoSaveResult(false, VideoSaveCode.INVALID_SHARE_URI)
        }
        val success = runCatching { operations.androidShare(contentUri) }.getOrDefault(false)
        return VideoSaveResult(
            success = success,
            code = if (success) VideoSaveCode.SHARED else VideoSaveCode.SHARE_FAILED,
        )
    }

    private fun executePublicUrl(request: VideoSaveRequest): VideoSaveResult {
        val publicUrl = request.publicUrl?.trim()?.takeIf(String::isNotBlank)
            ?: return VideoSaveResult(false, VideoSaveCode.INVALID_PUBLIC_URL)
        if (!isPublicHttpsUrl(publicUrl)) {
            return VideoSaveResult(false, VideoSaveCode.INVALID_PUBLIC_URL)
        }
        val success = runCatching { operations.savePublicUrlToMediaStore(publicUrl) }.getOrDefault(false)
        return VideoSaveResult(
            success = success,
            code = if (success) VideoSaveCode.SAVED_PUBLIC_URL else VideoSaveCode.PUBLIC_SAVE_FAILED,
        )
    }

    private fun isShareableUri(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return when (scheme) {
            "content" -> uri.authority?.isNotBlank() == true
            "https" -> isPublicHttpsUrl(value)
            else -> false
        }
    }

    private fun isPublicHttpsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")
            ?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (uri.scheme.lowercase(Locale.ROOT) != "https" || uri.userInfo != null) return false
        if (host.contains(':')) return false
        if (host in setOf("localhost", "localhost.localdomain", "ip6-localhost")) return false
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() != null }) {
            val values = octets.map(String::toInt)
            val first = values[0]
            if (first == 0 || first == 10 || first == 127 ||
                (first == 169 && values[1] == 254) ||
                (first == 172 && values[1] in 16..31) ||
                (first == 192 && values[1] == 168)
            ) return false
        }
        return host != "::1" && !host.startsWith("fe80:")
    }
}
