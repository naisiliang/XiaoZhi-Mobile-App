package com.lchuang.xiaozhimobile.adapters

import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import java.util.Locale

/** Semantic enrichment for the two map packages; navigation remains MapController's job. */
class MapAppAdapter : AppAdapter {
    override val id: String = "maps"

    override fun canHandle(packageName: String): Boolean =
        packageName.trim().lowercase(Locale.ROOT) in SUPPORTED_PACKAGES

    override fun classifyPage(context: ScreenContext): AppPageKind {
        val labels = semanticLabels(context.root)
        return when {
            labels.any { it.contains("导航") || it.contains("路线") || it.contains("navigation") } ->
                AppPageKind.MAP_NAVIGATION
            labels.any { it.contains("搜索") || it.contains("附近") || it.contains("search") || it.contains("poi") } ->
                AppPageKind.MAP_SEARCH
            labels.any { it.contains("地图") || it.contains("map") } -> AppPageKind.MAP_HOME
            else -> AppPageKind.OTHER
        }
    }

    override fun extractSemanticObjects(context: ScreenContext): List<ContextCandidate> {
        val objects = mutableListOf<ContextCandidate>()
        var position = 0

        fun visit(node: ScreenNode) {
            val label = semanticLabel(node)
            if (node.clickable && !label.isNullOrBlank()) {
                position += 1
                objects += ContextCandidate(
                    id = node.id,
                    label = label,
                    kind = ContextTargetKind.GENERIC,
                    position = position,
                    generationId = context.generationId,
                    packageName = context.packageName,
                    windowFingerprint = context.windowFingerprint,
                )
            }
            node.children.forEach(::visit)
        }

        visit(context.root)
        return objects
    }

    private fun semanticLabels(root: ScreenNode): List<String> {
        val labels = mutableListOf<String>()

        fun visit(node: ScreenNode) {
            listOf(node.text, node.contentDescription, node.role, node.className)
                .filterNotNull()
                .mapTo(labels) { it.lowercase(Locale.ROOT) }
            node.children.forEach(::visit)
        }

        visit(root)
        return labels
    }

    private fun semanticLabel(node: ScreenNode): String? = listOf(
        node.contentDescription,
        node.text,
        node.role,
    ).firstOrNull { !it.isNullOrBlank() }?.trim()

    private companion object {
        val SUPPORTED_PACKAGES = setOf(
            "com.autonavi.minimap",
            "com.baidu.baidumap",
        )
    }
}
