package com.lchuang.xiaozhimobile.artifacts.ppt

data class PresentationRequest(
    val title: String,
    val slides: List<SlideRequest>,
)

data class SlideRequest(
    val title: String,
    val body: List<String> = emptyList(),
    val media: List<SlideMedia> = emptyList(),
)

data class SlideMedia(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String = mimeTypeFor(fileName),
)

private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}
