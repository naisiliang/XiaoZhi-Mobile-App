package com.lchuang.xiaozhimobile.artifacts.generators

import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.File
import java.nio.charset.StandardCharsets

data class CsvDocument(val rows: List<List<String>>)

object CsvArtifactValidator {
    const val MAX_CSV_BYTES = 16L * 1024L * 1024L
    const val MAX_ROWS = 10_000
    const val MAX_COLUMNS = 256
    const val MAX_CELL_CHARS = 1_000_000

    fun parse(file: File, delimiter: Char = ','): CsvDocument {
        validateDelimiter(delimiter)
        val text = TextArtifactValidator.readUtf8(file, MAX_CSV_BYTES)
        require('\u0000' !in text) { "CSV contains NUL" }
        val rows = parseText(text, delimiter)
        validateRows(rows)
        return CsvDocument(rows)
    }

    fun validate(file: File, delimiter: Char = ','): Boolean = runCatching {
        parse(file, delimiter)
    }.isSuccess

    private fun parseText(text: String, delimiter: Char): List<List<String>> {
        if (text.isEmpty()) return emptyList()

        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var afterClosingQuote = false
        var fieldStarted = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field.setLength(0)
            fieldStarted = false
            afterClosingQuote = false
        }

        fun finishRow() {
            finishField()
            rows += row.toList()
            row.clear()
        }

        while (index < text.length) {
            val character = text[index]
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index += 2
                    } else {
                        inQuotes = false
                        afterClosingQuote = true
                        index++
                    }
                } else {
                    field.append(character)
                    index++
                }
                continue
            }

            if (afterClosingQuote) {
                when (character) {
                    delimiter -> {
                        finishField()
                        index++
                    }
                    '\r', '\n' -> {
                        finishRow()
                        index++
                        if (character == '\r' && index < text.length && text[index] == '\n') index++
                    }
                    else -> throw IllegalArgumentException("characters after a closing CSV quote")
                }
                continue
            }

            when (character) {
                '"' -> {
                    if (fieldStarted) throw IllegalArgumentException("quote must start a CSV field")
                    inQuotes = true
                    fieldStarted = true
                    index++
                }
                delimiter -> {
                    finishField()
                    index++
                }
                '\r', '\n' -> {
                    finishRow()
                    index++
                    if (character == '\r' && index < text.length && text[index] == '\n') index++
                }
                else -> {
                    field.append(character)
                    fieldStarted = true
                    index++
                }
            }
        }

        if (inQuotes) throw IllegalArgumentException("unterminated CSV quote")
        if (afterClosingQuote || fieldStarted || row.isNotEmpty()) {
            finishField()
            rows += row.toList()
        }
        return rows
    }

    private fun validateRows(rows: List<List<String>>) {
        require(rows.size <= MAX_ROWS) { "CSV has too many rows" }
        rows.forEach { row ->
            require(row.size <= MAX_COLUMNS) { "CSV has too many columns" }
            row.forEach { cell ->
                require(cell.length <= MAX_CELL_CHARS) { "CSV cell is too large" }
                require('\u0000' !in cell) { "CSV cell contains NUL" }
            }
        }
    }

    internal fun validateDelimiter(delimiter: Char) {
        require(delimiter.code >= 0x20 && delimiter != '"' && delimiter != '\u007f') {
            "invalid CSV delimiter"
        }
    }
}

class CsvArtifactGenerator(
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
        rows: List<List<String>>,
        delimiter: Char = ',',
    ): ArtifactGenerationResult {
        val serialized = try {
            CsvArtifactValidator.validateDelimiter(delimiter)
            serialize(rows, delimiter)
        } catch (error: IllegalArgumentException) {
            val code = if (error.message?.contains("size", ignoreCase = true) == true) {
                ArtifactGenerationCode.SIZE_LIMIT
            } else {
                ArtifactGenerationCode.INVALID_INPUT
            }
            return ArtifactGenerationResult.Rejected(code, error.message.orEmpty())
        }

        val bytes = serialized.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() > CsvArtifactValidator.MAX_CSV_BYTES) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.SIZE_LIMIT,
                "CSV artifact exceeds the size limit",
            )
        }
        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = "text/csv",
            extension = "csv",
            maxBytes = CsvArtifactValidator.MAX_CSV_BYTES,
            clock = clock,
            write = { output -> output.write(bytes) },
            validate = { file -> CsvArtifactValidator.validate(file, delimiter) },
        )
    }

    private fun serialize(rows: List<List<String>>, delimiter: Char): String {
        require(rows.size <= CsvArtifactValidator.MAX_ROWS) { "CSV has too many rows" }
        var estimatedBytes = 0L
        val delimiterBytes = delimiter.toString().toByteArray(StandardCharsets.UTF_8).size.toLong()
        rows.forEachIndexed { rowIndex, row ->
            require(row.size <= CsvArtifactValidator.MAX_COLUMNS) { "CSV has too many columns" }
            if (rowIndex > 0) estimatedBytes++
            row.forEachIndexed { columnIndex, cell ->
                require(cell.length <= CsvArtifactValidator.MAX_CELL_CHARS) { "CSV cell is too large" }
                require('\u0000' !in cell) { "CSV cell contains NUL" }
                if (columnIndex > 0) estimatedBytes += delimiterBytes
                val cellBytes = cell.toByteArray(StandardCharsets.UTF_8).size.toLong()
                val quoteCount = cell.count { it == '"' }.toLong()
                val needsQuotes = cell.any { it == delimiter || it == '"' || it == '\r' || it == '\n' }
                estimatedBytes += cellBytes + if (needsQuotes) 2L + quoteCount else 0L
                require(estimatedBytes <= CsvArtifactValidator.MAX_CSV_BYTES) {
                    "CSV artifact exceeds the size limit"
                }
            }
        }

        return buildString {
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) append('\n')
                row.forEachIndexed { columnIndex, cell ->
                    if (columnIndex > 0) append(delimiter)
                    val needsQuotes = cell.any { it == delimiter || it == '"' || it == '\r' || it == '\n' }
                    if (needsQuotes) {
                        append('"')
                        append(cell.replace("\"", "\"\""))
                        append('"')
                    } else {
                        append(cell)
                    }
                }
            }
        }
    }
}
