package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.File
import java.nio.charset.StandardCharsets

private const val DOCX_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

data class DocxDocument(
    val title: String = "",
    val paragraphs: List<String> = emptyList(),
)

class DocxArtifactGenerator(
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
        title: String = "",
        paragraphs: List<String> = emptyList(),
    ): ArtifactGenerationResult = generate(
        sessionId = sessionId,
        sourceAgent = sourceAgent,
        displayName = displayName,
        document = DocxDocument(title, paragraphs),
    )

    fun generate(
        sessionId: String,
        sourceAgent: String,
        displayName: String,
        document: DocxDocument,
    ): ArtifactGenerationResult {
        val documentXml = try {
            require(document.paragraphs.size <= MAX_PARAGRAPHS) { "DOCX has too many paragraphs" }
            requireXmlText(document.title, MAX_TEXT_CHARS)
            document.paragraphs.forEach { paragraph -> requireXmlText(paragraph, MAX_TEXT_CHARS) }
            var estimatedBytes = 1_024L
            if (document.title.isNotEmpty()) estimatedBytes += 128L + xmlEscapedUtf8Size(document.title)
            document.paragraphs.forEach { paragraph ->
                estimatedBytes += 128L + xmlEscapedUtf8Size(paragraph)
            }
            require(estimatedBytes <= MAX_STRUCTURED_XML_BYTES) { "DOCX XML exceeds the size limit" }
            buildDocumentXml(document)
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
        if (documentXml.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_STRUCTURED_XML_BYTES) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.SIZE_LIMIT,
                "DOCX XML exceeds the size limit",
            )
        }

        val entries = listOf(
            "[Content_Types].xml" to contentTypesXml(),
            "_rels/.rels" to packageRelationshipsXml(),
            "word/document.xml" to documentXml,
        )
        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = DOCX_MIME,
            extension = "docx",
            maxBytes = MAX_STRUCTURED_ARTIFACT_BYTES,
            clock = clock,
            write = { output -> writeStructuredZip(output, entries) },
            validate = { file -> DocxArtifactValidator.validate(file).isValid },
        )
    }

    private fun buildDocumentXml(document: DocxDocument): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:document xmlns:w=\"").append(DOCX_NAMESPACE).append("\"><w:body>")
        if (document.title.isNotEmpty()) appendParagraph(document.title, bold = true)
        document.paragraphs.forEach { paragraph -> appendParagraph(paragraph, bold = false) }
        append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/>")
        append("</w:sectPr></w:body></w:document>")
    }

    private fun StringBuilder.appendParagraph(text: String, bold: Boolean) {
        append("<w:p><w:r>")
        if (bold) append("<w:rPr><w:b/></w:rPr>")
        append("<w:t xml:space=\"preserve\">")
        append(xmlEscape(text))
        append("</w:t></w:r></w:p>")
    }

    private fun contentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="$CONTENT_TYPES_NAMESPACE">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="$DOCX_MIME"/>
        </Types>
    """.trimIndent()

    private fun packageRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$PACKAGE_RELATIONSHIP_NAMESPACE">
          <Relationship Id="rId1" Type="$OFFICE_DOCUMENT_RELATIONSHIP" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MAX_PARAGRAPHS = 10_000
        private const val MAX_TEXT_CHARS = 1_000_000
    }
}

object DocxArtifactValidator {
    fun validate(file: File): StructuredArtifactValidationResult = runCatching {
        val entries = readRequiredOpcEntries(
            file,
            setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"),
        )
        val contentTypes = parseSecureXml(entries.getValue("[Content_Types].xml"))
        requireXmlRoot(contentTypes, CONTENT_TYPES_NAMESPACE, "Types")
        require(
            hasXmlElementWithAttributes(contentTypes, CONTENT_TYPES_NAMESPACE, "Override") {
                it.getAttribute("PartName") == "/word/document.xml" &&
                    it.getAttribute("ContentType") == DocxArtifactGenerator.DOCX_MIME
            },
        ) { "DOCX content type is missing" }

        val packageRelationships = parseSecureXml(entries.getValue("_rels/.rels"))
        requireXmlRoot(packageRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationships")
        require(
            hasXmlElementWithAttributes(packageRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                it.getAttribute("Type") == OFFICE_DOCUMENT_RELATIONSHIP &&
                    it.getAttribute("Target") == "word/document.xml"
            },
        ) { "DOCX package relationship is missing" }

        val document = parseSecureXml(entries.getValue("word/document.xml"))
        requireXmlRoot(document, DOCX_NAMESPACE, "document")
        require(hasXmlElement(document, DOCX_NAMESPACE, "body")) { "DOCX body is missing" }
        StructuredArtifactValidationResult(true)
    }.getOrDefault(StructuredArtifactValidationResult(false, "invalid DOCX structure"))

}
