package com.lchuang.xiaozhimobile.video

import java.net.URI
import java.util.Locale

enum class VideoIntentType {
    SAVE_CURRENT,
    SHARE_CURRENT,
    SAVE_PUBLIC_URL,
}

data class VideoIntent(
    val type: VideoIntentType,
    val publicUrl: String? = null,
) {
    init {
        if (type == VideoIntentType.SAVE_PUBLIC_URL) {
            require(!publicUrl.isNullOrBlank()) { "A public-url intent requires a URL" }
        } else {
            require(publicUrl == null) { "Only public-url intents may contain a URL" }
        }
    }
}

sealed interface VideoIntentResolution {
    data class Resolved(val intent: VideoIntent) : VideoIntentResolution

    data class Blocked(val reason: VideoBlockedReason) : VideoIntentResolution

    data object Empty : VideoIntentResolution

    data object Unsupported : VideoIntentResolution
}

enum class VideoBlockedReason {
    UNSAFE_ACQUISITION,
    INVALID_PUBLIC_LINK,
}

/** Recognizes only native save/share and legal public-link video requests. */
class VideoIntentResolver {
    fun resolve(command: String): VideoIntentResolution {
        val normalized = normalize(command)
        if (normalized.isBlank()) return VideoIntentResolution.Empty
        if (BLOCKED_MARKERS.any(normalized::contains)) {
            return VideoIntentResolution.Blocked(VideoBlockedReason.UNSAFE_ACQUISITION)
        }

        if (SHARE_MARKERS.any(normalized::contains)) {
            return VideoIntentResolution.Resolved(VideoIntent(VideoIntentType.SHARE_CURRENT))
        }
        if (SAVE_MARKERS.any(normalized::contains)) {
            val url = HTTPS_URL.find(command)?.value
            if (url != null) {
                if (!isPublicHttpsUrl(url)) {
                    return VideoIntentResolution.Blocked(VideoBlockedReason.INVALID_PUBLIC_LINK)
                }
                return VideoIntentResolution.Resolved(
                    VideoIntent(VideoIntentType.SAVE_PUBLIC_URL, publicUrl = url),
                )
            }
            if (normalized.contains("视频") || normalized.contains("video")) {
                return VideoIntentResolution.Resolved(VideoIntent(VideoIntentType.SAVE_CURRENT))
            }
        }
        return VideoIntentResolution.Unsupported
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val HTTPS_URL = Regex("https://[^\\s，。！？!?]+", RegexOption.IGNORE_CASE)
        val SAVE_MARKERS = setOf("保存", "存到相册", "save this video", "save video")
        val SHARE_MARKERS = setOf("分享这个视频", "分享视频", "share this video", "share video")
        val BLOCKED_MARKERS = setOf(
            "drm",
            "破解",
            "绕过",
            "抓包",
            "中间人",
            "mitm",
            "会员",
            "付费墙",
            "paywall",
            "隐藏文件",
            "hidden file",
            "scrape",
        )

        fun isPublicHttpsUrl(value: String): Boolean {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
            val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")
                ?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (uri.scheme.lowercase(Locale.ROOT) != "https" || uri.userInfo != null) return false
            if (host.contains(':')) return false
            return host !in setOf("localhost", "localhost.localdomain", "ip6-localhost") &&
                !isPrivateHost(host)
        }

        fun isPrivateHost(host: String): Boolean {
            if (host == "::1" || host == "0:0:0:0:0:0:0:1" || host.startsWith("fe80:")) return true
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull() == null }) return false
            val values = octets.map(String::toInt)
            val first = values[0]
            return first == 0 || first == 10 || first == 127 ||
                (first == 169 && values[1] == 254) ||
                (first == 172 && values[1] in 16..31) ||
                (first == 192 && values[1] == 168)
        }
    }
}
