package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.File
import java.nio.charset.StandardCharsets

data class XlsxDocument(
    val sheetName: String = "Sheet1",
    val rows: List<List<String>> = emptyList(),
)

class XlsxArtifactGenerator(
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
        sheetName: String = "Sheet1",
        rows: List<List<String>> = emptyList(),
    ): ArtifactGenerationResult = generate(
        sessionId = sessionId,
        sourceAgent = sourceAgent,
        displayName = displayName,
        document = XlsxDocument(sheetName, rows),
    )

    fun generate(
        sessionId: String,
        sourceAgent: String,
        displayName: String,
        document: XlsxDocument,
    ): ArtifactGenerationResult {
        val sheetXml = try {
            validateInput(document)
            buildSheetXml(document.rows)
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
        if (sheetXml.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_STRUCTURED_XML_BYTES) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.SIZE_LIMIT,
                "XLSX sheet XML exceeds the size limit",
            )
        }

        val entries = listOf(
            "[Content_Types].xml" to contentTypesXml(),
            "_rels/.rels" to packageRelationshipsXml(),
            "xl/workbook.xml" to workbookXml(document.sheetName),
            "xl/_rels/workbook.xml.rels" to workbookRelationshipsXml(),
            "xl/worksheets/sheet1.xml" to sheetXml,
        )
        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = XLSX_MIME,
            extension = "xlsx",
            maxBytes = MAX_STRUCTURED_ARTIFACT_BYTES,
            clock = clock,
            write = { output -> writeStructuredZip(output, entries) },
            validate = { file -> XlsxArtifactValidator.validate(file).isValid },
        )
    }

    private fun validateInput(document: XlsxDocument) {
        require(document.sheetName.isNotBlank() && document.sheetName == document.sheetName.trim()) {
            "XLSX sheet name must not be blank"
        }
        require(document.sheetName.length <= MAX_SHEET_NAME_LENGTH) { "XLSX sheet name is too long" }
        require(document.sheetName.none { it in INVALID_SHEET_NAME_CHARACTERS }) {
            "XLSX sheet name contains an invalid character"
        }
        requireXmlText(document.sheetName, MAX_SHEET_NAME_LENGTH)
        require(document.rows.size <= MAX_ROWS) { "XLSX has too many rows" }
        var estimatedBytes = 1_024L + xmlEscapedUtf8Size(document.sheetName)
        document.rows.forEach { row ->
            require(row.size <= MAX_COLUMNS) { "XLSX has too many columns" }
            estimatedBytes += 48L
            row.forEach { cell ->
                requireXmlText(cell, MAX_CELL_CHARS)
                estimatedBytes += 64L + xmlEscapedUtf8Size(cell)
                require(estimatedBytes <= MAX_STRUCTURED_XML_BYTES) {
                    "XLSX sheet XML exceeds the size limit"
                }
            }
        }
        require(estimatedBytes <= MAX_STRUCTURED_XML_BYTES) { "XLSX sheet XML exceeds the size limit" }
    }

    private fun buildSheetXml(rows: List<List<String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"").append(SPREADSHEET_NAMESPACE).append("\"><sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            append("<row r=\"").append(rowNumber).append("\">")
            row.forEachIndexed { columnIndex, cell ->
                val reference = columnName(columnIndex) + rowNumber
                append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(xmlEscape(cell))
                append("</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        return buildString {
            while (value > 0) {
                val remainder = (value - 1) % 26
                append(('A'.code + remainder).toChar())
                value = (value - 1) / 26
            }
        }.reversed()
    }

    private fun contentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="$CONTENT_TYPES_NAMESPACE">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="$XLSX_MIME.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="$XLSX_MIME.worksheet+xml"/>
        </Types>
    """.trimIndent()

    private fun packageRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$PACKAGE_RELATIONSHIP_NAMESPACE">
          <Relationship Id="rId1" Type="$OFFICE_DOCUMENT_RELATIONSHIP" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml(sheetName: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="$SPREADSHEET_NAMESPACE" xmlns:r="$OFFICE_RELATIONSHIP_NAMESPACE">
          <sheets><sheet name="${xmlEscape(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$PACKAGE_RELATIONSHIP_NAMESPACE">
          <Relationship Id="rId1" Type="$WORKSHEET_RELATIONSHIP" Target="worksheets/sheet1.xml"/>
        </Relationships>
    """.trimIndent()

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MAX_ROWS = 10_000
        private const val MAX_COLUMNS = 256
        private const val MAX_CELL_CHARS = 1_000_000
        private const val MAX_SHEET_NAME_LENGTH = 31
        private val INVALID_SHEET_NAME_CHARACTERS = setOf('[', ']', ':', '*', '?', '/', '\\')
    }
}

object XlsxArtifactValidator {
    fun validate(file: File): StructuredArtifactValidationResult = runCatching {
        val entries = readRequiredOpcEntries(
            file,
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
            ),
        )
        val contentTypes = parseSecureXml(entries.getValue("[Content_Types].xml"))
        requireXmlRoot(contentTypes, CONTENT_TYPES_NAMESPACE, "Types")
        require(
            hasXmlElementWithAttributes(contentTypes, CONTENT_TYPES_NAMESPACE, "Override") {
                it.getAttribute("PartName") == "/xl/workbook.xml" &&
                    it.getAttribute("ContentType") == "${XlsxArtifactGenerator.XLSX_MIME}.main+xml"
            },
        ) { "XLSX workbook content type is missing" }
        require(
            hasXmlElementWithAttributes(contentTypes, CONTENT_TYPES_NAMESPACE, "Override") {
                it.getAttribute("PartName") == "/xl/worksheets/sheet1.xml" &&
                    it.getAttribute("ContentType") == "${XlsxArtifactGenerator.XLSX_MIME}.worksheet+xml"
            },
        ) { "XLSX sheet content type is missing" }

        val packageRelationships = parseSecureXml(entries.getValue("_rels/.rels"))
        requireXmlRoot(packageRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationships")
        require(
            hasXmlElementWithAttributes(packageRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                it.getAttribute("Type") == OFFICE_DOCUMENT_RELATIONSHIP &&
                    it.getAttribute("Target") == "xl/workbook.xml"
            },
        ) { "XLSX package relationship is missing" }

        val workbook = parseSecureXml(entries.getValue("xl/workbook.xml"))
        requireXmlRoot(workbook, SPREADSHEET_NAMESPACE, "workbook")
        require(hasXmlElement(workbook, SPREADSHEET_NAMESPACE, "sheets")) { "XLSX sheets are missing" }
        require(
            hasXmlElementWithAttributes(workbook, SPREADSHEET_NAMESPACE, "sheet") {
                it.getAttributeNS(OFFICE_RELATIONSHIP_NAMESPACE, "id") == "rId1"
            },
        ) { "XLSX sheet relationship is missing" }

        val workbookRelationships = parseSecureXml(entries.getValue("xl/_rels/workbook.xml.rels"))
        requireXmlRoot(workbookRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationships")
        require(
            hasXmlElementWithAttributes(workbookRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                it.getAttribute("Type") == WORKSHEET_RELATIONSHIP &&
                    it.getAttribute("Target") == "worksheets/sheet1.xml"
            },
        ) { "XLSX worksheet relationship is missing" }

        val sheet = parseSecureXml(entries.getValue("xl/worksheets/sheet1.xml"))
        requireXmlRoot(sheet, SPREADSHEET_NAMESPACE, "worksheet")
        require(hasXmlElement(sheet, SPREADSHEET_NAMESPACE, "sheetData")) { "XLSX sheet data is missing" }
        StructuredArtifactValidationResult(true)
    }.getOrDefault(StructuredArtifactValidationResult(false, "invalid XLSX structure"))

    private const val SPREADSHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
}

private const val SPREADSHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
