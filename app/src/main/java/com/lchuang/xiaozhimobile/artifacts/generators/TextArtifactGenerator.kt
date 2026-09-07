package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.ArtifactDigest
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

enum class TextArtifactFormat(
    val extension: String,
    val mimeType: String,
) {
    TEXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
}

object TextArtifactValidator {
    const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
    private const val BUFFER_SIZE = 8 * 1024

    /** Reads a bounded UTF-8 file and rejects malformed or unmappable input. */
    fun readUtf8(file: File, maxBytes: Long = MAX_TEXT_BYTES): String {
        require(maxBytes > 0L && maxBytes <= ArtifactDigest.MAX_ARTIFACT_BYTES) {
            "invalid text size limit"
        }
        require(file.isFile) { "text artifact must be a file" }
        if (file.length() > maxBytes) throw IOException("text artifact exceeds the size limit")

        val bytes = ByteArrayOutputStream(minOf(file.length(), BUFFER_SIZE.toLong()).toInt())
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > maxBytes) throw IOException("text artifact exceeds the size limit")
                bytes.write(buffer, 0, count)
            }
        }
        return strictUtf8Decoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun validate(file: File, maxBytes: Long = MAX_TEXT_BYTES): Boolean = runCatching {
        '\u0000' !in readUtf8(file, maxBytes)
    }.getOrDefault(false)

    private fun strictUtf8Decoder(): CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
}

class TextArtifactGenerator(
    private val workspace: ArtifactWorkspace,
    private val repository: ArtifactRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        repository: ArtifactRepository,
        workspace: ArtifactWorkspace,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(workspace, repository, clock)

    fun generate(
        sessionId: String,
        sourceAgent: String,
        displayName: String,
        text: String,
        format: TextArtifactFormat = TextArtifactFormat.TEXT,
    ): ArtifactGenerationResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > TextArtifactValidator.MAX_TEXT_BYTES) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.SIZE_LIMIT,
                "text artifact exceeds the size limit",
            )
        }
        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = format.mimeType,
            extension = format.extension,
            maxBytes = TextArtifactValidator.MAX_TEXT_BYTES,
            clock = clock,
            write = { output -> output.write(bytes) },
            validate = { file -> TextArtifactValidator.validate(file) },
        )
    }
}
