package com.lchuang.xiaozhimobile.image

import com.lchuang.xiaozhimobile.artifacts.ArtifactExportCode
import com.lchuang.xiaozhimobile.artifacts.ArtifactExportCoordinator
import com.lchuang.xiaozhimobile.artifacts.ArtifactExportResult
import com.lchuang.xiaozhimobile.artifacts.ArtifactWorkspace
import java.io.File

/** Image-specific export facade; it never changes the generic private-first default. */
class ImageExportCoordinator(
    private val workspace: ArtifactWorkspace,
    private val artifacts: ArtifactExportCoordinator,
) {
    fun privateDefault(image: ImageArtifact): ArtifactExportResult =
        if (isValidImage(image)) artifacts.privateDefault(image.artifact) else invalidImage()

    fun createSafSaveIntent(image: ImageArtifact): android.content.Intent? =
        if (isValidImage(image)) artifacts.createSafSaveIntent(image.artifact) else null

    fun saveToSaf(image: ImageArtifact, destinationUri: String): ArtifactExportResult =
        if (isValidImage(image)) artifacts.saveToSaf(image.artifact, destinationUri) else invalidImage()

    fun saveToGallery(image: ImageArtifact): ArtifactExportResult =
        if (isValidImage(image)) artifacts.saveImageToMediaStore(image.artifact) else invalidImage()

    fun open(image: ImageArtifact): ArtifactExportResult =
        if (isValidImage(image)) artifacts.open(image.artifact) else invalidImage()

    fun share(image: ImageArtifact): ArtifactExportResult =
        if (isValidImage(image)) artifacts.share(image.artifact) else invalidImage()

    private fun isValidImage(image: ImageArtifact): Boolean {
        if (!artifacts.isValidPrivateArtifact(image.artifact)) return false
        val file = runCatching { File(image.artifact.privatePath).canonicalFile }.getOrNull() ?: return false
        return workspace.isPrivate(file) && ImageArtifactValidator.validate(file, image.artifact.mimeType)
    }

    private fun invalidImage() = ArtifactExportResult(false, ArtifactExportCode.INVALID_ARTIFACT)
}
