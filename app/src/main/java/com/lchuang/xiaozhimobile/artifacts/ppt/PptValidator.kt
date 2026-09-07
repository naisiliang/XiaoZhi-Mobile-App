package com.lchuang.xiaozhimobile.artifacts.ppt

import com.lchuang.xiaozhimobile.artifacts.generators.CONTENT_TYPES_NAMESPACE
import com.lchuang.xiaozhimobile.artifacts.generators.OFFICE_DOCUMENT_RELATIONSHIP
import com.lchuang.xiaozhimobile.artifacts.generators.OFFICE_RELATIONSHIP_NAMESPACE
import com.lchuang.xiaozhimobile.artifacts.generators.PACKAGE_RELATIONSHIP_NAMESPACE
import com.lchuang.xiaozhimobile.artifacts.generators.StructuredArtifactValidationResult
import com.lchuang.xiaozhimobile.artifacts.generators.ZipArtifactPathPolicy
import com.lchuang.xiaozhimobile.artifacts.generators.parseSecureXml
import com.lchuang.xiaozhimobile.artifacts.generators.hasXmlElement
import com.lchuang.xiaozhimobile.artifacts.generators.hasXmlElementWithAttributes
import com.lchuang.xiaozhimobile.artifacts.generators.readRequiredOpcEntries
import com.lchuang.xiaozhimobile.artifacts.generators.requireXmlRoot
import java.io.File
import java.util.zip.ZipFile

data class PptValidationResult(
    val isValid: Boolean,
    val slideCount: Int = 0,
    val mediaCount: Int = 0,
    val reason: String = "",
)

object PptValidator {
    fun validate(file: File): PptValidationResult {
        if (!file.isFile) return PptValidationResult(false, reason = "PPTX is not a file")
        if (file.length() <= 0L || file.length() > PptRenderer.MAX_PPTX_BYTES) {
            return PptValidationResult(false, reason = "PPTX size is outside the allowed range")
        }
        return try {
            val zipResult = com.lchuang.xiaozhimobile.artifacts.generators.ZipArtifactValidator.validate(file)
            require(zipResult.isValid) { "invalid PPTX ZIP" }
            val (slideIndexes, mediaPaths) = collectPackagePaths(file)
            require(slideIndexes.isNotEmpty() && slideIndexes.size <= PptPlanner.MAX_SLIDES) {
                "PPTX slide count is outside the allowed range"
            }
            require(slideIndexes == (1..slideIndexes.size).toSet()) { "PPTX slide numbering is not contiguous" }

            val required = mutableSetOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "ppt/presentation.xml",
                "ppt/_rels/presentation.xml.rels",
                "ppt/slideMasters/slideMaster1.xml",
                "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                "ppt/slideLayouts/slideLayout1.xml",
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                "ppt/theme/theme1.xml",
            )
            slideIndexes.forEach { index ->
                required += "ppt/slides/slide$index.xml"
                required += "ppt/slides/_rels/slide$index.xml.rels"
            }
            val entries = readRequiredOpcEntries(file, required)

            val contentTypes = parseSecureXml(entries.getValue("[Content_Types].xml"))
            requireXmlRoot(contentTypes, CONTENT_TYPES_NAMESPACE, "Types")
            require(
                hasXmlElementWithAttributes(contentTypes, CONTENT_TYPES_NAMESPACE, "Override") {
                    it.getAttribute("PartName") == "/ppt/presentation.xml" &&
                        it.getAttribute("ContentType") == PPTX_MAIN_CONTENT_TYPE
                },
            ) { "PPTX presentation content type is missing" }
            slideIndexes.forEach { index ->
                require(
                    hasXmlElementWithAttributes(contentTypes, CONTENT_TYPES_NAMESPACE, "Override") {
                        it.getAttribute("PartName") == "/ppt/slides/slide$index.xml" &&
                            it.getAttribute("ContentType") == SLIDE_CONTENT_TYPE
                    },
                ) { "PPTX slide content type is missing" }
            }
            mediaPaths.forEach { path ->
                val extension = path.substringAfterLast('.').lowercase()
                val expectedType = when (extension) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    else -> throw IllegalArgumentException("unsupported PPTX media extension")
                }
                require(
                    hasXmlElementWithAttributes(contentTypes, CONTENT_TYPES_NAMESPACE, "Default") {
                        it.getAttribute("Extension").lowercase() == extension &&
                            it.getAttribute("ContentType") == expectedType
                    },
                ) { "PPTX media content type is missing" }
            }

            val packageRelationships = parseSecureXml(entries.getValue("_rels/.rels"))
            requireXmlRoot(packageRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationships")
            require(
                hasXmlElementWithAttributes(packageRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                    it.getAttribute("Type") == OFFICE_DOCUMENT_RELATIONSHIP &&
                        it.getAttribute("Target") == "ppt/presentation.xml"
                },
            ) { "PPTX package relationship is missing" }

            val presentation = parseSecureXml(entries.getValue("ppt/presentation.xml"))
            requireXmlRoot(presentation, PRESENTATION_NAMESPACE, "presentation")
            require(hasXmlElement(presentation, PRESENTATION_NAMESPACE, "sldMasterIdLst")) { "PPTX master list is missing" }
            require(
                presentation.getElementsByTagNameNS(PRESENTATION_NAMESPACE, "sldId").length == slideIndexes.size,
            ) { "PPTX slide list does not match package slides" }

            val presentationRelationships = parseSecureXml(entries.getValue("ppt/_rels/presentation.xml.rels"))
            requireXmlRoot(presentationRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationships")
            require(
                hasXmlElementWithAttributes(presentationRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                    it.getAttribute("Type") == SLIDE_MASTER_RELATIONSHIP &&
                        it.getAttribute("Target") == "slideMasters/slideMaster1.xml"
                },
            ) { "PPTX master relationship is missing" }
            slideIndexes.forEach { index ->
                require(
                    hasXmlElementWithAttributes(presentationRelationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                        it.getAttribute("Type") == SLIDE_RELATIONSHIP &&
                            it.getAttribute("Target") == "slides/slide$index.xml"
                    },
                ) { "PPTX slide relationship is missing" }
            }

            requireXmlRoot(parseSecureXml(entries.getValue("ppt/slideMasters/slideMaster1.xml")), PRESENTATION_NAMESPACE, "sldMaster")
            requireXmlRoot(parseSecureXml(entries.getValue("ppt/slideLayouts/slideLayout1.xml")), PRESENTATION_NAMESPACE, "sldLayout")
            requireXmlRoot(parseSecureXml(entries.getValue("ppt/theme/theme1.xml")), DRAWING_NAMESPACE, "theme")
            requireXmlRoot(
                parseSecureXml(entries.getValue("ppt/slideMasters/_rels/slideMaster1.xml.rels")),
                PACKAGE_RELATIONSHIP_NAMESPACE,
                "Relationships",
            )
            requireXmlRoot(
                parseSecureXml(entries.getValue("ppt/slideLayouts/_rels/slideLayout1.xml.rels")),
                PACKAGE_RELATIONSHIP_NAMESPACE,
                "Relationships",
            )

            var referencedMedia = emptySet<String>()
            slideIndexes.forEach { index ->
                val slide = parseSecureXml(entries.getValue("ppt/slides/slide$index.xml"))
                requireXmlRoot(slide, PRESENTATION_NAMESPACE, "sld")
                require(hasXmlElement(slide, PRESENTATION_NAMESPACE, "cSld")) { "PPTX slide common data is missing" }
                require(hasXmlElement(slide, PRESENTATION_NAMESPACE, "spTree")) { "PPTX slide shape tree is missing" }
                val relationships = parseSecureXml(entries.getValue("ppt/slides/_rels/slide$index.xml.rels"))
                requireXmlRoot(relationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationships")
                require(
                    hasXmlElementWithAttributes(relationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                        it.getAttribute("Type") == SLIDE_LAYOUT_RELATIONSHIP &&
                            it.getAttribute("Target") == "../slideLayouts/slideLayout1.xml"
                    },
                ) { "PPTX slide layout relationship is missing" }
                val referenced = mutableSetOf<String>()
                hasXmlElementWithAttributes(relationships, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship") {
                    val type = it.getAttribute("Type")
                    if (type != IMAGE_RELATIONSHIP) return@hasXmlElementWithAttributes false
                    val target = it.getAttribute("Target")
                    require(target.startsWith("../media/")) { "PPTX media relationship is not relative" }
                    val name = target.removePrefix("../media/")
                    val packagePath = ZipArtifactPathPolicy.normalize("ppt/media/$name")
                    require(packagePath == "ppt/media/$name") { "PPTX media relationship is not canonical" }
                    require(mediaPaths.contains(packagePath)) { "PPTX media relationship target is missing" }
                    referenced += packagePath
                    true
                }
                referencedMedia = referencedMedia + referenced
            }
            require(referencedMedia == mediaPaths) { "PPTX media set does not match relationships" }
            PptValidationResult(true, slideIndexes.size, mediaPaths.size)
        } catch (_: Exception) {
            PptValidationResult(false, reason = "invalid PPTX structure")
        }
    }

    private fun collectPackagePaths(file: File): Pair<Set<Int>, Set<String>> = ZipFile(file).use { zip ->
        val slides = mutableSetOf<Int>()
        val media = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            SLIDE_PATH.matchEntire(entry.name)?.groupValues?.get(1)?.toIntOrNull()?.let(slides::add)
            if (entry.name.startsWith("ppt/media/")) {
                require(!entry.isDirectory && entry.name != "ppt/media/") { "invalid PPTX media entry" }
                require(zip.getEntry(entry.name)?.size ?: 0L > 0L) { "empty PPTX media entry" }
                media += entry.name
            }
        }
        slides to media
    }

    private const val PRESENTATION_NAMESPACE = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val DRAWING_NAMESPACE = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val SLIDE_MASTER_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
    private const val SLIDE_LAYOUT_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
    private const val SLIDE_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val IMAGE_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    private const val PPTX_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val SLIDE_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private val SLIDE_PATH = Regex("ppt/slides/slide(\\d+)\\.xml")
}
