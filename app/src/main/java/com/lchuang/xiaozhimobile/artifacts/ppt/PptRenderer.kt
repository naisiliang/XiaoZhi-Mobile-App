package com.lchuang.xiaozhimobile.artifacts.ppt

import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationCode
import com.lchuang.xiaozhimobile.artifacts.generators.ArtifactGenerationResult
import com.lchuang.xiaozhimobile.artifacts.generators.OFFICE_RELATIONSHIP_NAMESPACE
import com.lchuang.xiaozhimobile.artifacts.generators.PACKAGE_RELATIONSHIP_NAMESPACE
import com.lchuang.xiaozhimobile.artifacts.generators.generateValidatedArtifact
import com.lchuang.xiaozhimobile.artifacts.generators.xmlEscape
import com.lchuang.xiaozhimobile.artifacts.ArtifactRepository
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptRenderer(
    private val workspace: ArtifactWorkspace,
    private val repository: ArtifactRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        repository: ArtifactRepository,
        workspace: ArtifactWorkspace,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(workspace, repository, clock)

    fun render(
        sessionId: String,
        sourceAgent: String,
        displayName: String,
        plan: PresentationPlan,
    ): ArtifactGenerationResult {
        val validatedPlan = when (val planned = PptPlanner.plan(
            PresentationRequest(
                title = plan.title,
                slides = plan.slides.map { slide ->
                    SlideRequest(slide.title, slide.body, slide.media)
                },
            ),
        )) {
            is PptPlanResult.Planned -> planned.plan
            is PptPlanResult.Rejected -> return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.INVALID_INPUT,
                planned.reason,
            )
        }
        val payloads = try {
            buildPayloads(validatedPlan)
        } catch (error: IllegalArgumentException) {
            return ArtifactGenerationResult.Rejected(
                ArtifactGenerationCode.INVALID_INPUT,
                error.message.orEmpty(),
            )
        }
        return generateValidatedArtifact(
            workspace = workspace,
            repository = repository,
            sessionId = sessionId,
            sourceAgent = sourceAgent,
            displayName = displayName,
            mimeType = PPTX_MIME,
            extension = "pptx",
            maxBytes = MAX_PPTX_BYTES,
            clock = clock,
            write = { output -> writePptxZip(output, payloads) },
            validate = { file -> PptValidator.validate(file).isValid },
        )
    }

    private fun buildPayloads(plan: PresentationPlan): List<PptPayload> {
        val payloads = mutableListOf<PptPayload>()
        payloads += textPayload("[Content_Types].xml", contentTypesXml(plan))
        payloads += textPayload("_rels/.rels", packageRelationshipsXml())
        payloads += textPayload("ppt/presentation.xml", presentationXml(plan))
        payloads += textPayload("ppt/_rels/presentation.xml.rels", presentationRelationshipsXml(plan))
        payloads += textPayload("ppt/slideMasters/slideMaster1.xml", slideMasterXml())
        payloads += textPayload("ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRelationshipsXml())
        payloads += textPayload("ppt/slideLayouts/slideLayout1.xml", slideLayoutXml())
        payloads += textPayload("ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRelationshipsXml())
        payloads += textPayload("ppt/theme/theme1.xml", themeXml())
        plan.slides.forEach { slide ->
            payloads += textPayload("ppt/slides/slide${slide.index}.xml", slideXml(slide))
            payloads += textPayload(
                "ppt/slides/_rels/slide${slide.index}.xml.rels",
                slideRelationshipsXml(slide),
            )
            slide.media.forEach { media ->
                payloads += PptPayload("ppt/media/${media.fileName}", media.bytes)
            }
        }
        return payloads
    }

    private fun contentTypesXml(plan: PresentationPlan): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"$CONTENT_TYPES_NAMESPACE\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        val extensions = plan.slides.flatMap { slide -> slide.media }.map { it.fileName.substringAfterLast('.').lowercase() }.toSet()
        extensions.forEach { extension ->
            val contentType = when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                else -> error("unsupported media extension")
            }
            append("<Default Extension=\"").append(extension).append("\" ContentType=\"").append(contentType).append("\"/>")
        }
        append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"").append(PPTX_MAIN_CONTENT_TYPE).append("\"/>")
        append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"").append(SLIDE_MASTER_CONTENT_TYPE).append("\"/>")
        append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"").append(SLIDE_LAYOUT_CONTENT_TYPE).append("\"/>")
        append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"").append(THEME_CONTENT_TYPE).append("\"/>")
        plan.slides.forEach { slide ->
            append("<Override PartName=\"/ppt/slides/slide").append(slide.index)
                .append(".xml\" ContentType=\"").append(SLIDE_CONTENT_TYPE).append("\"/>")
        }
        append("</Types>")
    }

    private fun packageRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$PACKAGE_RELATIONSHIP_NAMESPACE">
          <Relationship Id="rId1" Type="$OFFICE_DOCUMENT_RELATIONSHIP" Target="ppt/presentation.xml"/>
        </Relationships>
    """.trimIndent()

    private fun presentationXml(plan: PresentationPlan): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<p:presentation xmlns:a=\"$DRAWING_NAMESPACE\" xmlns:r=\"$OFFICE_RELATIONSHIP_NAMESPACE\" xmlns:p=\"$PRESENTATION_NAMESPACE\">")
        append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
        append("<p:sldIdLst>")
        plan.slides.forEach { slide ->
            append("<p:sldId id=\"").append(255 + slide.index).append("\" r:id=\"rId").append(slide.index + 1).append("\"/>")
        }
        append("</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>")
        append("<p:defaultTextStyle/><p:embeddedFontLst/></p:presentation>")
    }

    private fun presentationRelationshipsXml(plan: PresentationPlan): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"$PACKAGE_RELATIONSHIP_NAMESPACE\">")
        append("<Relationship Id=\"rId1\" Type=\"$SLIDE_MASTER_RELATIONSHIP\" Target=\"slideMasters/slideMaster1.xml\"/>")
        plan.slides.forEach { slide ->
            append("<Relationship Id=\"rId").append(slide.index + 1).append("\" Type=\"$SLIDE_RELATIONSHIP\" Target=\"slides/slide").append(slide.index).append(".xml\"/>")
        }
        append("</Relationships>")
    }

    private fun slideMasterXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sldMaster xmlns:a="$DRAWING_NAMESPACE" xmlns:r="$OFFICE_RELATIONSHIP_NAMESPACE" xmlns:p="$PRESENTATION_NAMESPACE">
          <p:cSld name="Master"><p:spTree>${emptyShapeTree()}</p:spTree></p:cSld>
          <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
          <p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst>
          <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
        </p:sldMaster>
    """.trimIndent()

    private fun slideMasterRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$PACKAGE_RELATIONSHIP_NAMESPACE">
          <Relationship Id="rId1" Type="$SLIDE_LAYOUT_RELATIONSHIP" Target="../slideLayouts/slideLayout1.xml"/>
          <Relationship Id="rId2" Type="$THEME_RELATIONSHIP" Target="../theme/theme1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun slideLayoutXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sldLayout xmlns:a="$DRAWING_NAMESPACE" xmlns:r="$OFFICE_RELATIONSHIP_NAMESPACE" xmlns:p="$PRESENTATION_NAMESPACE" type="title" preserve="1">
          <p:cSld name="Title Slide"><p:spTree>${emptyShapeTree()}</p:spTree></p:cSld>
          <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
        </p:sldLayout>
    """.trimIndent()

    private fun slideLayoutRelationshipsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$PACKAGE_RELATIONSHIP_NAMESPACE">
          <Relationship Id="rId1" Type="$SLIDE_MASTER_RELATIONSHIP" Target="../slideMasters/slideMaster1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun themeXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <a:theme xmlns:a="$DRAWING_NAMESPACE" name="Office Theme">
          <a:themeElements>
            <a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F1F1F"/></a:dk2><a:lt2><a:srgbClr val="FFFFFF"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
            <a:fontScheme name="Office"><a:majorFont><a:latin typeface="Aptos Display"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/></a:minorFont></a:fontScheme>
            <a:fmtScheme name="Office"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/><a:bgFillStyleLst/></a:fmtScheme>
          </a:themeElements>
        </a:theme>
    """.trimIndent()

    private fun slideXml(slide: SlidePlan): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<p:sld xmlns:a=\"$DRAWING_NAMESPACE\" xmlns:r=\"$OFFICE_RELATIONSHIP_NAMESPACE\" xmlns:p=\"$PRESENTATION_NAMESPACE\">")
        append("<p:cSld name=\"Slide ").append(slide.index).append("\"><p:spTree>")
        append(emptyShapeTree())
        append(textShape(2, "Title", slide.title, bold = true, y = 400_000))
        if (slide.body.isNotEmpty()) append(bodyShape(3, slide.body))
        slide.media.forEachIndexed { index, media -> append(pictureShape(4 + index, media, index)) }
        append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
    }

    private fun slideRelationshipsXml(slide: SlidePlan): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"$PACKAGE_RELATIONSHIP_NAMESPACE\">")
        append("<Relationship Id=\"rId1\" Type=\"$SLIDE_LAYOUT_RELATIONSHIP\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
        slide.media.forEachIndexed { index, media ->
            append("<Relationship Id=\"rId").append(index + 2).append("\" Type=\"$IMAGE_RELATIONSHIP\" Target=\"../media/")
                .append(xmlEscape(media.fileName)).append("\"/>")
        }
        append("</Relationships>")
    }

    private fun emptyShapeTree(): String = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"

    private fun textShape(id: Int, name: String, text: String, bold: Boolean, y: Int): String = """
        <p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
          <p:spPr><a:xfrm><a:off x="600000" y="$y"/><a:ext cx="10800000" cy="700000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
          <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="2800"${if (bold) " b=\"1\"" else ""}/><a:t>${xmlEscape(text)}</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody>
        </p:sp>
    """.trimIndent()

    private fun bodyShape(id: Int, lines: List<String>): String = buildString {
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>")
        append("<p:spPr><a:xfrm><a:off x=\"800000\" y=\"1400000\"/><a:ext cx=\"10000000\" cy=\"4500000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>")
        append("<p:txBody><a:bodyPr/><a:lstStyle/>")
        lines.forEach { line -> append("<a:p><a:pPr marL=\"360000\" indent=\"-180000\"><a:buChar char=\"•\"/></a:pPr><a:r><a:rPr lang=\"zh-CN\" sz=\"1800\"/><a:t>").append(xmlEscape(line)).append("</a:t></a:r><a:endParaRPr lang=\"zh-CN\"/></a:p>") }
        append("</p:txBody></p:sp>")
    }

    private fun pictureShape(id: Int, media: SlideMedia, mediaIndex: Int): String = """
        <p:pic><p:nvPicPr><p:cNvPr id="$id" name="${xmlEscape(media.fileName)}"/><p:cNvPicPr preferRelativeResize="0"/><p:nvPr/></p:nvPicPr>
          <p:blipFill><a:blip r:embed="rId${mediaIndex + 2}"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
          <p:spPr><a:xfrm><a:off x="800000" y="5200000"/><a:ext cx="2400000" cy="1200000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
        </p:pic>
    """.trimIndent()

    private fun textPayload(path: String, text: String): PptPayload =
        PptPayload(path, text.toByteArray(StandardCharsets.UTF_8))

    private fun writePptxZip(output: OutputStream, payloads: List<PptPayload>) {
        ZipOutputStream(output).use { zip ->
            payloads.forEach { payload ->
                zip.putNextEntry(ZipEntry(payload.path))
                zip.write(payload.bytes)
                zip.closeEntry()
            }
        }
    }

    private data class PptPayload(val path: String, val bytes: ByteArray)

    companion object {
        const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val MAX_PPTX_BYTES = 64L * 1024L * 1024L
        private const val PRESENTATION_NAMESPACE = "http://schemas.openxmlformats.org/presentationml/2006/main"
        private const val DRAWING_NAMESPACE = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val CONTENT_TYPES_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/content-types"
        private const val OFFICE_DOCUMENT_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
        private const val SLIDE_MASTER_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
        private const val SLIDE_LAYOUT_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
        private const val SLIDE_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
        private const val THEME_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme"
        private const val IMAGE_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
        private const val PPTX_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        private const val SLIDE_MASTER_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
        private const val SLIDE_LAYOUT_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
        private const val SLIDE_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        private const val THEME_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.theme+xml"
    }
}
