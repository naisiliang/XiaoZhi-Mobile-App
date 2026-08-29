package com.lchuang.xiaozhimobile

import java.net.URI

object AiEndpointResolver {
    fun normalizeBaseUrl(raw: String): String {
        val clean = raw.trim()
        require(clean.isNotBlank()) { "Base URL 不能为空" }
        val uri = URI(clean)
        require(uri.scheme == "http" || uri.scheme == "https") { "Base URL 必须使用 http 或 https" }
        val authority = uri.rawAuthority ?: throw IllegalArgumentException("Base URL 缺少主机")
        var path = (uri.path ?: "").trimEnd('/')
        val suffixes = listOf("/v1/chat/completions", "/v1/responses", "/v1/models", "/v1")
        for (suffix in suffixes) {
            if (path.endsWith(suffix)) {
                path = path.removeSuffix(suffix).trimEnd('/')
                break
            }
        }
        val normalizedPath = if (path.isBlank()) "" else path
        return "${uri.scheme}://$authority$normalizedPath".trimEnd('/')
    }

    fun chatUrl(base: String): String = normalizeBaseUrl(base) + "/v1/chat/completions"
    fun responsesUrl(base: String): String = normalizeBaseUrl(base) + "/v1/responses"
    fun modelsUrl(base: String): String = normalizeBaseUrl(base) + "/v1/models"
}
