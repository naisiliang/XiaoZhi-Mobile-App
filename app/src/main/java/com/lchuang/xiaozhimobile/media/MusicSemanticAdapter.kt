package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import java.util.Locale

/** Shared semantic implementation used by the app-specific music adapters. */
abstract class MusicSemanticAdapter(
    override val id: String,
    private val supportedPackage: String,
) : MusicAppAdapter {
    override fun canHandle(app: MusicApp): Boolean =
        app.packageName.equals(supportedPackage, ignoreCase = true)

    override fun propose(
        intent: MusicIntent,
        screen: ScreenContext,
    ): MusicAdapterResolution {
        if (!screen.packageName.equals(supportedPackage, ignoreCase = true)) {
            return MusicAdapterResolution.Unsupported
        }

        val nodes = flatten(screen.root)
        return when (intent.type) {
            MusicIntentType.OPEN -> MusicAdapterResolution.Unsupported
            MusicIntentType.SEARCH -> proposeControl(
                intent = intent,
                screen = screen,
                nodes = nodes,
                markers = SEARCH_MARKERS,
            )
            MusicIntentType.PLAY -> proposeControl(
                intent = intent,
                screen = screen,
                nodes = nodes,
                markers = PLAY_MARKERS,
                excludedMarkers = PAUSE_MARKERS,
            )
            MusicIntentType.PAUSE -> proposeControl(
                intent = intent,
                screen = screen,
                nodes = nodes,
                markers = PAUSE_MARKERS,
                excludedMarkers = PLAY_MARKERS,
            )
            MusicIntentType.PREVIOUS -> proposeControl(
                intent = intent,
                screen = screen,
                nodes = nodes,
                markers = PREVIOUS_MARKERS,
            )
            MusicIntentType.NEXT -> proposeControl(
                intent = intent,
                screen = screen,
                nodes = nodes,
                markers = NEXT_MARKERS,
            )
            MusicIntentType.SELECT_FIRST -> proposeResultSelection(intent, screen, nodes, 0)
            MusicIntentType.SELECT_SECOND -> proposeResultSelection(intent, screen, nodes, 1)
        }
    }

    private fun proposeControl(
        intent: MusicIntent,
        screen: ScreenContext,
        nodes: List<ScreenNode>,
        markers: Set<String>,
        excludedMarkers: Set<String> = emptySet(),
    ): MusicAdapterResolution {
        val candidates = nodes
            .filter { node ->
                isInteractableControl(node) &&
                    matches(node, markers) &&
                    !matches(node, excludedMarkers)
            }
            .map { node -> candidate(node, screen) }
            .distinctBy(ContextCandidate::id)
        return when (candidates.size) {
            0 -> MusicAdapterResolution.Unsupported
            1 -> proposed(intent, screen, UiActionType.CLICK, candidates.single())
            else -> MusicAdapterResolution.NeedsClarification(
                candidates = candidates,
                reason = MusicClarificationReason.AMBIGUOUS_TARGET,
            )
        }
    }

    private fun proposeResultSelection(
        intent: MusicIntent,
        screen: ScreenContext,
        nodes: List<ScreenNode>,
        index: Int,
    ): MusicAdapterResolution {
        val results = nodes
            .filter(::isResultNode)
            .mapIndexed { position, node -> candidate(node, screen, position + 1) }
            .distinctBy(ContextCandidate::id)
        val selected = results.getOrNull(index) ?: return MusicAdapterResolution.Unsupported
        return proposed(intent, screen, UiActionType.SELECT, selected)
    }

    private fun proposed(
        intent: MusicIntent,
        screen: ScreenContext,
        action: UiActionType,
        target: ContextCandidate,
    ) = MusicAdapterResolution.Proposed(
        intent = intent,
        proposal = UiActionProposal(action = action, context = screen, target = target),
    )

    private fun isInteractableControl(node: ScreenNode): Boolean {
        if (node.clickable) return true
        val roles = listOfNotNull(node.role, node.className).map(::normalize)
        return roles.any { role ->
            role.contains("button") || role.contains("search") || role.contains("input") ||
                role.contains("editor")
        }
    }

    private fun isResultNode(node: ScreenNode): Boolean {
        if (!node.clickable) return false
        val role = normalize(listOfNotNull(node.role, node.className).joinToString(" "))
        if (role.contains("button") || role.contains("search") || role.contains("input")) {
            return false
        }
        return RESULT_ROLE_MARKERS.any(role::contains) && semanticLabel(node).isNotBlank()
    }

    private fun matches(node: ScreenNode, markers: Set<String>): Boolean {
        if (markers.isEmpty()) return false
        val values = listOfNotNull(node.role, node.className, node.text, node.contentDescription)
            .map(::normalize)
        return values.any { value ->
            markers.any { marker -> value == marker || value.contains(marker) }
        }
    }

    private fun candidate(
        node: ScreenNode,
        screen: ScreenContext,
        position: Int? = null,
    ) = ContextCandidate(
        id = node.id,
        label = semanticLabel(node),
        position = position,
        generationId = screen.generationId,
        packageName = screen.packageName,
        windowFingerprint = screen.windowFingerprint,
    )

    private fun semanticLabel(node: ScreenNode): String = listOfNotNull(
        node.text,
        node.contentDescription,
        node.role,
    ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun flatten(root: ScreenNode): List<ScreenNode> = buildList {
        fun visit(node: ScreenNode) {
            add(node)
            node.children.forEach(::visit)
        }
        visit(root)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SEARCH_MARKERS = setOf("搜索", "search")
        val PLAY_MARKERS = setOf("播放", "play", "resume", "继续")
        val PAUSE_MARKERS = setOf("暂停", "pause")
        val PREVIOUS_MARKERS = setOf("上一首", "上一曲", "previous", "prev")
        val NEXT_MARKERS = setOf("下一首", "下一曲", "next")
        val RESULT_ROLE_MARKERS = setOf(
            "result",
            "song",
            "track",
            "music_item",
            "musicitem",
            "list_item",
            "listitem",
        )
    }
}
