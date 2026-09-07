package com.lchuang.xiaozhimobile.artifacts.ppt

import com.lchuang.xiaozhimobile.artifacts.generators.ZipArtifactPathPolicy
import com.lchuang.xiaozhimobile.artifacts.generators.requireXmlText

object PptPlanner {
    const val MAX_SLIDES = 50
    const val MAX_BODY_LINES = 64
    const val MAX_MEDIA_PER_SLIDE = 8
    const val MAX_TEXT_CHARS = 1_000_000
    const val MAX_TOTAL_TEXT_CHARS = 4_000_000
    const val MAX_MEDIA_BYTES = 8L * 1024L * 1024L
    const val MAX_TOTAL_MEDIA_BYTES = 16L * 1024L * 1024L
    private const val MAX_TITLE_CHARS = 256

    fun plan(request: PresentationRequest): PptPlanResult {
        return try {
            require(request.title.isNotBlank() && request.title == request.title.trim()) {
                "presentation title must not be blank"
            }
            require(request.title.length <= MAX_TITLE_CHARS) { "presentation title is too long" }
            requireXmlText(request.title, MAX_TITLE_CHARS)
            require(request.slides.isNotEmpty() && request.slides.size <= MAX_SLIDES) {
                "presentation slide count is outside the allowed range"
            }

            var totalTextChars = request.title.length.toLong()
            var totalMediaBytes = 0L
            val mediaNames = HashSet<String>()
            val slides = request.slides.mapIndexed { index, requestSlide ->
                require(requestSlide.title.isNotBlank() && requestSlide.title == requestSlide.title.trim()) {
                    "slide title must not be blank"
                }
                require(requestSlide.title.length <= MAX_TITLE_CHARS) { "slide title is too long" }
                requireXmlText(requestSlide.title, MAX_TITLE_CHARS)
                require(requestSlide.body.size <= MAX_BODY_LINES) { "slide has too many body lines" }
                requestSlide.body.forEach { line ->
                    requireXmlText(line, MAX_TEXT_CHARS)
                    totalTextChars += line.length.toLong()
                    require(totalTextChars <= MAX_TOTAL_TEXT_CHARS) { "presentation text exceeds the size limit" }
                }
                totalTextChars += requestSlide.title.length.toLong()
                require(totalTextChars <= MAX_TOTAL_TEXT_CHARS) { "presentation text exceeds the size limit" }
                require(requestSlide.media.size <= MAX_MEDIA_PER_SLIDE) { "slide has too many media assets" }
                val media = requestSlide.media.map { asset ->
                    validateMedia(asset, mediaNames)
                    totalMediaBytes += asset.bytes.size.toLong()
                    require(totalMediaBytes <= MAX_TOTAL_MEDIA_BYTES) {
                        "presentation media exceeds the size limit"
                    }
                    asset.copy(bytes = asset.bytes.copyOf())
                }
                SlidePlan(index = index + 1, title = requestSlide.title, body = requestSlide.body.toList(), media = media)
            }
            PptPlanResult.Planned(PresentationPlan(request.title, slides))
        } catch (error: IllegalArgumentException) {
            PptPlanResult.Rejected(error.message.orEmpty())
        }
    }

    private fun validateMedia(asset: SlideMedia, names: MutableSet<String>) {
        require(asset.fileName.isNotBlank() && asset.fileName == asset.fileName.trim()) {
            "media filename must not be blank"
        }
        require(asset.fileName.length <= 128) { "media filename is too long" }
        val safePath = ZipArtifactPathPolicy.normalize("ppt/media/${asset.fileName}")
        require(safePath == "ppt/media/${asset.fileName}") { "media filename is not canonical" }
        val normalizedName = asset.fileName.lowercase()
        require(names.add(normalizedName)) { "duplicate media filename" }
        require(asset.bytes.isNotEmpty() && asset.bytes.size.toLong() <= MAX_MEDIA_BYTES) {
            "media asset size is outside the allowed range"
        }
        val expectedMime = mimeTypeFor(asset.fileName)
        require(asset.mimeType == expectedMime && expectedMime != "application/octet-stream") {
            "media MIME type does not match its filename"
        }
        require(hasExpectedSignature(asset.bytes, expectedMime)) { "media signature is invalid" }
    }

    private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun hasExpectedSignature(bytes: ByteArray, mimeType: String): Boolean = when (mimeType) {
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        )
        "image/jpeg" -> bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
        )
        "image/gif" -> bytes.size >= 6 && (
            bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
            )
        else -> false
    }
}
