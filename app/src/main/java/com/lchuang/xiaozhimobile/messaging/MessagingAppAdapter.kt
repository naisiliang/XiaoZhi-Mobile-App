package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.adapters.AppAdapter
import com.lchuang.xiaozhimobile.adapters.AppPageKind
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import java.util.Locale

/**
 * Semantic-only adapter shared by ordinary WeChat and QQ text messaging.
 *
 * The adapter can describe a safe target and propose an action, but it never
 * invokes Android UI APIs. The central generic executor remains the only
 * component allowed to execute the proposal.
 */
abstract class MessagingAppAdapter : AppAdapter {
    protected abstract val messagingPackageName: String

    override fun canHandle(packageName: String): Boolean =
        packageName.trim().equals(messagingPackageName, ignoreCase = true)

    override fun classifyPage(context: ScreenContext): AppPageKind {
        if (!canHandle(context.packageName)) return AppPageKind.UNKNOWN
        return MessagingTreeSemantics.classify(context)
    }

    override fun extractSemanticObjects(context: ScreenContext): List<ContextCandidate> {
        if (!canHandle(context.packageName)) return emptyList()
        return MessagingTreeSemantics.extractSemanticObjects(context)
    }

    /** Return all exact-name contact rows in their current semantic order. */
    fun resolveContactCandidates(
        context: ScreenContext,
        requestedName: String,
    ): List<ContextCandidate> {
        if (!canHandle(context.packageName)) return emptyList()
        return MessagingTreeSemantics.resolveContacts(context, requestedName)
    }

    /** Propose opening a chat only when exactly one contact row matches. */
    fun proposeOpenChat(
        context: ScreenContext,
        requestedName: String,
    ): UiActionProposal? {
        val candidate = resolveContactCandidates(context, requestedName).singleOrNull()
            ?: return null
        return UiActionProposal(
            action = UiActionType.CLICK,
            context = context,
            target = candidate,
        )
    }

    fun currentChatCandidate(context: ScreenContext): ContextCandidate? {
        if (!canHandle(context.packageName)) return null
        return MessagingTreeSemantics.currentChat(context)
    }

    fun messageInputCandidate(context: ScreenContext): ContextCandidate? {
        if (!canHandle(context.packageName)) return null
        return MessagingTreeSemantics.messageInput(context)
    }

    fun sendButtonCandidate(context: ScreenContext): ContextCandidate? {
        if (!canHandle(context.packageName)) return null
        return MessagingTreeSemantics.sendButton(context)
    }
}

/** Pure semantic extraction over the immutable accessibility snapshot. */
private object MessagingTreeSemantics {
    private val CONTROL_LABELS = setOf(
        "联系人",
        "通讯录",
        "返回",
        "取消",
        "搜索",
        "设置",
        "发送",
        "send",
        "message",
        "输入消息",
    )

    fun classify(context: ScreenContext): AppPageKind {
        val nodes = flatten(context.root)
        val labels = nodes.flatMap(::labels).map(::normalize)
        val roles = nodes.flatMap(::roles).map(::normalize)
        return when {
            roles.any { it.contains("contact_list") || it.contains("contactlist") || it == "contacts" } ||
                labels.any { it == "联系人" || it == "通讯录" || it == "contacts" } -> AppPageKind.LIST
            nodes.any(::isChatRole) || nodes.any(::isMessageControl) -> AppPageKind.OTHER
            else -> AppPageKind.UNKNOWN
        }
    }

    fun resolveContacts(
        context: ScreenContext,
        requestedName: String,
    ): List<ContextCandidate> {
        val query = normalize(requestedName)
        if (query.isBlank()) return emptyList()

        val pageKind = classify(context)
        val contactNodes = flatten(context.root).filter { node ->
            val label = semanticLabel(node) ?: return@filter false
            node.clickable &&
                !isControlLabel(label) &&
                (isContactRole(node) || pageKind == AppPageKind.LIST) &&
                normalize(label) == query
        }
        return contactNodes.mapIndexed { index, node ->
            candidate(node, context, position = index + 1)
        }
    }

    fun currentChat(context: ScreenContext): ContextCandidate? {
        if (classify(context) == AppPageKind.LIST) return null
        val node = flatten(context.root).firstOrNull { candidate ->
            val label = semanticLabel(candidate)
            !label.isNullOrBlank() && !isControlLabel(label) && isChatRole(candidate)
        } ?: flatten(context.root).firstOrNull { candidate ->
            val label = semanticLabel(candidate)
            !label.isNullOrBlank() && !isControlLabel(label) && isToolbarTitle(candidate)
        }
        return node?.let { candidate(it, context) }
    }

    fun messageInput(context: ScreenContext): ContextCandidate? =
        uniqueControlCandidate(context) { node ->
            val label = semanticLabel(node).orEmpty()
            val role = normalize(node.role.orEmpty())
            val className = normalize(node.className.orEmpty())
            !isSendNode(node) &&
                (role.contains("message_input") || role.contains("input") ||
                    role.contains("editor") || className.contains("edittext") ||
                    label.contains("输入消息") || label == "输入" ||
                    label == "message input" || label == "message")
        }

    fun sendButton(context: ScreenContext): ContextCandidate? =
        uniqueControlCandidate(context) { node ->
            node.clickable && isSendNode(node)
        }

    fun extractSemanticObjects(context: ScreenContext): List<ContextCandidate> {
        val objects = buildList {
            val contactPage = classify(context) == AppPageKind.LIST
            flatten(context.root).filter { node ->
                node.clickable &&
                    !isControlLabel(semanticLabel(node).orEmpty()) &&
                    (isContactRole(node) || contactPage)
            }.forEachIndexed { index, node ->
                add(candidate(node, context, position = index + 1))
            }
            currentChat(context)?.let(::add)
            messageInput(context)?.let(::add)
            sendButton(context)?.let(::add)
        }
        return objects.distinctBy(ContextCandidate::id)
    }

    private fun uniqueControlCandidate(
        context: ScreenContext,
        predicate: (ScreenNode) -> Boolean,
    ): ContextCandidate? {
        val matches = flatten(context.root).filter(predicate)
        return matches.singleOrNull()?.let { candidate(it, context) }
    }

    private fun candidate(
        node: ScreenNode,
        context: ScreenContext,
        position: Int? = null,
    ): ContextCandidate = ContextCandidate(
        id = node.id,
        label = semanticLabel(node).orEmpty(),
        kind = ContextTargetKind.GENERIC,
        position = position,
        generationId = context.generationId,
        packageName = context.packageName,
        windowFingerprint = context.windowFingerprint,
    )

    private fun flatten(root: ScreenNode): List<ScreenNode> = buildList {
        fun visit(node: ScreenNode) {
            add(node)
            node.children.forEach(::visit)
        }
        visit(root)
    }

    private fun labels(node: ScreenNode): List<String> = listOfNotNull(
        node.text,
        node.contentDescription,
    ).filter(String::isNotBlank)

    private fun roles(node: ScreenNode): List<String> = listOfNotNull(
        node.role,
        node.className,
    ).filter(String::isNotBlank)

    private fun semanticLabel(node: ScreenNode): String? =
        labels(node).firstOrNull()?.trim()?.takeIf(String::isNotBlank)

    private fun isContactRole(node: ScreenNode): Boolean =
        roles(node).any { role ->
            val normalized = normalize(role)
            normalized.contains("contact") || normalized.contains("conversation_item") ||
                normalized.contains("contact_item")
        }

    private fun isChatRole(node: ScreenNode): Boolean =
        roles(node).any { role ->
            val normalized = normalize(role)
            normalized == "chat" || normalized.contains("current_chat") ||
                normalized.contains("chat_title") || normalized.contains("conversation_title") ||
                normalized == "conversation"
        }

    private fun isToolbarTitle(node: ScreenNode): Boolean {
        val role = normalize(node.role.orEmpty())
        val className = normalize(node.className.orEmpty())
        return role.contains("toolbar_title") ||
            (role.contains("toolbar") && !isMessageControl(node)) ||
            className.contains("toolbar")
    }

    private fun isMessageControl(node: ScreenNode): Boolean =
        isSendNode(node) || messageInputRole(node)

    private fun messageInputRole(node: ScreenNode): Boolean {
        val role = normalize(node.role.orEmpty())
        val className = normalize(node.className.orEmpty())
        val label = normalize(semanticLabel(node).orEmpty())
        return role.contains("message_input") || role.contains("input") ||
            role.contains("editor") || className.contains("edittext") ||
            label.contains("输入消息") || label == "输入" || label == "message input"
    }

    private fun isSendNode(node: ScreenNode): Boolean {
        val role = normalize(node.role.orEmpty())
        val label = normalize(semanticLabel(node).orEmpty())
        return role == "send_button" || role.contains("send") ||
            label == "发送" || label == "send" || label.contains("发送消息") ||
            label == "send message"
    }

    private fun isControlLabel(label: String): Boolean {
        val normalized = normalize(label)
        return normalized in CONTROL_LABELS || normalized.contains("返回") ||
            normalized.contains("取消") || normalized.contains("搜索") ||
            normalized.contains("设置")
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
