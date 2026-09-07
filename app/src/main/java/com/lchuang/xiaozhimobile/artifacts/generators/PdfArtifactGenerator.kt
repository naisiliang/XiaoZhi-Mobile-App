package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

data class PdfDocument(val pages: List<String>)

class PdfArtifactGenerator(
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
        pages: List<String>,
    ): ArtifactGenerationResult = generate(
        sessionId = sessionId,
        sourceAgent = sourceAgent,
        displayName = displayName,
        document = PdfDocument(pages),
    )

    fun generate(
        sessionId: String,
        sourceAgent: String,
        displayName: String,
        document: PdfDocument,
    ): ArtifactGenerationResult {
        val bytes = try {
            require(document.pages.isNotEmpty()) { "PDF must contain at least one page" }
            require(document.pages.size <= MAX_PAGES) { "PDF has too many pages" }
            document.pages.forEach { page -> require(page.length <= MAX_PAGE_CHARS) { "PDF page is too large" } }
            var estimatedBytes = 2_048L
            document.pages.forEach { page ->
                estimatedBytes = Math.addExact(estimatedBytes, 256L + page.length.toLong() * 4L)
                require(estimatedBytes <= MAX_PDF_BYTES) { "PDF artifact exceeds the size limit" }
            }
            buildPdf(document.pages)
        } catch (error: IllegalArgumentException) {
            return ArtifactGenerationResult.Rejected(
                if (error.message?.contains("exceeds", ignoreCase = true) == true) {
                    ArtifactGenerationCode.SIZE_LIMIT
                } else {
                    ArtifactGenerationCode.INVALID_INPUT
                },
                error.message.orEmpty(),
            )
        }
        if (bytes.size.toLong() > MAX_PDF_BYTES) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.SIZE_LIMIT,
                "PDF artifact exceeds the size limit",
            )
        }
        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = PDF_MIME,
            extension = "pdf",
            maxBytes = MAX_PDF_BYTES,
            clock = clock,
            write = { output -> output.write(bytes) },
            validate = { file -> PdfArtifactValidator.validate(file).isValid },
        )
    }

    private fun buildPdf(pages: List<String>): ByteArray {
        val pageCount = pages.size
        val firstPageId = 3
        val firstContentId = firstPageId + pageCount
        val fontId = firstContentId + pageCount
        val objectCount = fontId
        val objects = arrayOfNulls<ByteArray>(objectCount + 1)
        objects[1] = ascii("<< /Type /Catalog /Pages 2 0 R >>")
        objects[2] = ascii(
            "<< /Type /Pages /Kids [${(0 until pageCount).joinToString(" ") { "${firstPageId + it} 0 R" }}] /Count $pageCount >>",
        )
        pages.forEachIndexed { index, _ ->
            val pageId = firstPageId + index
            val contentId = firstContentId + index
            objects[pageId] = ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontId 0 R >> >> /Contents $contentId 0 R >>",
            )
            val content = pageContent(pages[index])
            val contentBytes = ascii(content)
            objects[contentId] = ascii(
                "<< /Length ${contentBytes.size} >>\nstream\n$content\nendstream",
            )
        }
        objects[fontId] = ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A))
        output.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
        val offsets = IntArray(objectCount + 1)
        for (id in 1..objectCount) {
            offsets[id] = output.size()
            writeAscii(output, "$id 0 obj\n")
            output.write(objects[id] ?: error("missing PDF object $id"))
            writeAscii(output, "\nendobj\n")
        }
        val xrefOffset = output.size()
        writeAscii(output, "xref\n0 ${objectCount + 1}\n")
        writeAscii(output, "0000000000 65535 f \n")
        for (id in 1..objectCount) writeAscii(output, "%010d 00000 n \n".format(offsets[id]))
        writeAscii(output, "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\n")
        writeAscii(output, "startxref\n$xrefOffset\n%%EOF\n")
        return output.toByteArray()
    }

    private fun pageContent(page: String): String = buildString {
        append("BT\n/F1 12 Tf\n72 720 Td\n")
        page.split('\n').forEachIndexed { index, line ->
            if (index > 0) append("0 -16 Td\n")
            append('<').append(utf16Hex(line)).append("> Tj\n")
        }
        append("ET\n")
    }

    private fun utf16Hex(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_16BE)
        val digits = "0123456789ABCDEF"
        return buildString(bytes.size * 2 + 4) {
            append("FEFF")
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0f])
            }
        }
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(StandardCharsets.US_ASCII)

    companion object {
        const val PDF_MIME = "application/pdf"
        const val MAX_PDF_BYTES = 32L * 1024L * 1024L
        private const val MAX_PAGES = 256
        private const val MAX_PAGE_CHARS = 1_000_000
    }
}

object PdfArtifactValidator {
    fun validate(file: File): StructuredArtifactValidationResult = runCatching {
        val bytes = readBoundedFile(file, PdfArtifactGenerator.MAX_PDF_BYTES)
        require(bytes.size >= 5) { "PDF is too small" }
        require(bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(StandardCharsets.US_ASCII))) {
            "PDF header is missing"
        }
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        require(text.contains("/Type /Catalog")) { "PDF catalog is missing" }
        require(text.contains("/Type /Pages")) { "PDF pages tree is missing" }
        require(text.contains("/Kids [")) { "PDF page tree has no kids" }
        require(text.contains("/Contents ")) { "PDF page contents are missing" }
        require(text.contains("stream\n") && text.contains("\nendstream")) { "PDF page stream is missing" }
        require(text.contains("BT\n") && text.contains("\nET\n")) { "PDF page content is empty" }
        require(PAGE_PATTERN.findAll(text).count() > 0) { "PDF has no page objects" }
        require(Regex("/Count\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true) {
            "PDF page count is missing"
        }
        val xrefMarker = "startxref\n"
        val xrefNumberStart = text.lastIndexOf(xrefMarker)
        require(xrefNumberStart >= 0) { "PDF cross-reference offset is missing" }
        val xrefLineStart = xrefNumberStart + xrefMarker.length
        val xrefLineEnd = text.indexOf('\n', xrefLineStart)
        require(xrefLineEnd > xrefLineStart) { "PDF cross-reference offset is malformed" }
        val xrefOffset = text.substring(xrefLineStart, xrefLineEnd).trim().toIntOrNull()
        require(xrefOffset != null && xrefOffset >= 0 && xrefOffset < bytes.size) {
            "PDF cross-reference offset is out of bounds"
        }
        require(text.startsWith("xref\n", xrefOffset)) { "PDF cross-reference table is missing" }
        require(text.contains("trailer\n") && text.endsWith("%%EOF\n")) { "PDF trailer is missing" }
        StructuredArtifactValidationResult(true)
    }.getOrDefault(StructuredArtifactValidationResult(false, "invalid PDF structure"))

    private val PAGE_PATTERN = Regex("/Type\\s*/Page\\b")
}
