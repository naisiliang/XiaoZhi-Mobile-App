package com.lchuang.xiaozhimobile.image

import java.util.Locale

enum class ImageIntentMode {
    CREATE,
    EDIT,
    REGENERATE,
}

/** Optional explicit context for continuing work on a previously generated image. */
data class ImageGenerationContext(
    val artifactId: String,
    val version: Int,
    val prompt: String,
    val artifact: com.lchuang.xiaozhimobile.artifacts.Artifact? = null,
) {
    init {
        require(artifactId.isNotBlank()) { "image context artifact id must not be blank" }
        require(version >= 1) { "image context version must be positive" }
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_LENGTH) {
            "image context prompt is invalid"
        }
    }

    companion object {
        private const val MAX_PROMPT_LENGTH = 4 * 1024
    }
}

data class ImageIntent(
    val mode: ImageIntentMode,
    val prompt: String,
    val imageContext: ImageGenerationContext? = null,
) {
    init {
        require(prompt.isNotBlank() && prompt == prompt.trim() && prompt.length <= MAX_PROMPT_LENGTH) {
            "image prompt is invalid"
        }
        require(prompt.none { it.code < 0x20 || it == '\u007f' }) {
            "image prompt contains a control character"
        }
    }

    companion object {
        private const val MAX_PROMPT_LENGTH = 4 * 1024
    }
}

/** Resolves only explicit image creation/edit language into an image intent. */
object ImageIntentResolver {
    private const val MAX_PROMPT_LENGTH = 4 * 1024
    private val createMarkers = listOf(
        "生成一张",
        "生成图片",
        "生成图像",
        "画一张",
        "画图",
        "做一张图",
        "generate an image",
        "create an image",
        "draw an image",
    )
    private val regenerateMarkers = listOf(
        "再生成",
        "重新生成",
        "换一张",
        "regenerate",
        "generate again",
    )
    private val editMarkers = listOf(
        "修改",
        "改成",
        "改为",
        "换成",
        "变成",
        "把",
        "edit",
        "change",
        "make it",
    )

    fun resolve(
        rawText: String,
        imageContext: ImageGenerationContext? = null,
    ): ImageIntent? {
        val text = rawText.trim()
        if (text.isBlank() || text.length > MAX_PROMPT_LENGTH ||
            text.any { it.code < 0x20 || it == '\u007f' }
        ) return null
        val lower = text.lowercase(Locale.ROOT)
        val regenerate = regenerateMarkers.any(lower::contains)
        if (regenerate) {
            val context = imageContext ?: return null
            return ImageIntent(ImageIntentMode.REGENERATE, context.prompt, context)
        }
        val edit = imageContext != null && editMarkers.any(lower::contains)
        if (edit) return ImageIntent(ImageIntentMode.EDIT, text, imageContext)
        if (createMarkers.any(lower::contains)) return ImageIntent(ImageIntentMode.CREATE, text)
        return null
    }
}
