package com.lchuang.xiaozhimobile.artifacts.generators

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

data class StructuredArtifactValidationResult(
    val isValid: Boolean,
    val reason: String = "",
)

internal const val MAX_STRUCTURED_ARTIFACT_BYTES = 32L * 1024L * 1024L
internal const val MAX_STRUCTURED_XML_BYTES = 8L * 1024L * 1024L
internal const val PACKAGE_RELATIONSHIP_NAMESPACE =
    "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val OFFICE_RELATIONSHIP_NAMESPACE =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val OFFICE_DOCUMENT_RELATIONSHIP =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
internal const val WORKSHEET_RELATIONSHIP =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
internal const val CONTENT_TYPES_NAMESPACE =
    "http://schemas.openxmlformats.org/package/2006/content-types"

internal fun writeStructuredZip(
    output: OutputStream,
    entries: List<Pair<String, String>>,
) {
    ZipOutputStream(output).use { zip ->
        entries.forEach { (rawPath, content) ->
            val path = ZipArtifactPathPolicy.normalize(rawPath)
            zip.putNextEntry(ZipEntry(path))
            zip.write(content.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }
}

internal fun readBoundedFile(file: File, maxBytes: Long): ByteArray {
    require(maxBytes > 0L && maxBytes <= com.lchuang.xiaozhimobile.artifacts.ArtifactDigest.MAX_ARTIFACT_BYTES) {
        "invalid structured artifact size limit"
    }
    require(file.isFile) { "structured artifact must be a file" }
    if (file.length() > maxBytes) throw IOException("structured artifact exceeds the size limit")
    val bytes = ByteArrayOutputStream(minOf(file.length(), 8_192L).toInt())
    FileInputStream(file).use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            if (total > maxBytes) throw IOException("structured artifact exceeds the size limit")
            bytes.write(buffer, 0, count)
        }
    }
    return bytes.toByteArray()
}

internal fun readRequiredOpcEntries(
    file: File,
    requiredPaths: Set<String>,
): Map<String, ByteArray> {
    require(file.isFile) { "OPC artifact must be a file" }
    require(file.length() <= MAX_STRUCTURED_ARTIFACT_BYTES) {
        "OPC artifact exceeds the size limit"
    }
    val zipResult = ZipArtifactValidator.validate(file)
    require(zipResult.isValid) { "invalid OPC ZIP: ${zipResult.reason}" }
    return ZipFile(file).use { zip ->
        requiredPaths.associateWith { path ->
            readRequiredZipEntry(zip, path, MAX_STRUCTURED_XML_BYTES)
        }
    }
}

private fun readRequiredZipEntry(zip: ZipFile, rawPath: String, maxBytes: Long): ByteArray {
    val path = ZipArtifactPathPolicy.normalize(rawPath)
    val entry = zip.getEntry(path) ?: throw IllegalArgumentException("missing OPC entry: $path")
    require(!entry.isDirectory) { "OPC entry is a directory: $path" }
    require(entry.size <= maxBytes || entry.size < 0L) { "OPC entry exceeds the size limit: $path" }
    val bytes = ByteArrayOutputStream(minOf(entry.size.coerceAtLeast(0L), 8_192L).toInt())
    zip.getInputStream(entry).use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            require(total <= maxBytes) { "OPC entry exceeds the size limit: $path" }
            bytes.write(buffer, 0, count)
        }
    }
    return bytes.toByteArray()
}

internal fun parseSecureXml(bytes: ByteArray): Document {
    require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_STRUCTURED_XML_BYTES) {
        "invalid XML size"
    }
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

internal fun requireXmlRoot(document: Document, namespace: String, localName: String): Element {
    val root = document.documentElement
    require(root != null && root.namespaceURI == namespace && root.localName == localName) {
        "unexpected XML root"
    }
    return root
}

internal fun hasXmlElement(document: Document, namespace: String, localName: String): Boolean =
    document.getElementsByTagNameNS(namespace, localName).length > 0

internal fun hasXmlElementWithAttributes(
    document: Document,
    namespace: String,
    localName: String,
    matches: (Element) -> Boolean,
): Boolean {
    val elements = document.getElementsByTagNameNS(namespace, localName)
    for (index in 0 until elements.length) {
        val element = elements.item(index) as? Element ?: continue
        if (matches(element)) return true
    }
    return false
}

internal fun requireXmlText(value: String, maxChars: Int) {
    require(value.length <= maxChars) { "structured artifact text is too large" }
    require(value.none { character ->
        character.code in 0x00..0x08 ||
            character.code in 0x0b..0x0c ||
            character.code in 0x0e..0x1f ||
            character == '\u007f'
    }) { "structured artifact text contains an XML control character" }
    xmlEscapedUtf8Size(value)
}

internal fun xmlEscapedUtf8Size(value: String): Long {
    var total = 0L
    var index = 0
    while (index < value.length) {
        val character = value[index]
        val bytes = when (character) {
            '&' -> 5L
            '<', '>' -> 4L
            '"', '\'' -> 6L
            else -> when {
                character.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "structured artifact text contains an invalid Unicode surrogate"
                    }
                    index++
                    4L
                }
                character.isLowSurrogate() -> {
                    throw IllegalArgumentException("structured artifact text contains an invalid Unicode surrogate")
                }
                character.code < 0x80 -> 1L
                character.code < 0x800 -> 2L
                else -> 3L
            }
        }
        total = Math.addExact(total, bytes)
        index++
    }
    return total
}

internal fun xmlEscape(value: String): String = buildString(value.length + 16) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}

internal fun writeAscii(output: ByteArrayOutputStream, value: String) {
    output.write(value.toByteArray(StandardCharsets.US_ASCII))
}
