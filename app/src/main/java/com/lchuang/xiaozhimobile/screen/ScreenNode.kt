package com.lchuang.xiaozhimobile.screen

import java.util.Collections

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

class ScreenNode(
    val id: String,
    val role: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val visibleBounds: ScreenBounds? = null,
    children: List<ScreenNode> = emptyList(),
    val sensitiveScreenSignals: SensitiveScreenSignals = SensitiveScreenSignals(),
) {
    val children: List<ScreenNode> = Collections.unmodifiableList(children.toList())

    init {
        require(id.isNotBlank()) { "Screen node id must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is ScreenNode &&
            id == other.id &&
            role == other.role &&
            text == other.text &&
            contentDescription == other.contentDescription &&
            className == other.className &&
            clickable == other.clickable &&
            visibleBounds == other.visibleBounds &&
            sensitiveScreenSignals == other.sensitiveScreenSignals &&
            children == other.children

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (role?.hashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        result = 31 * result + (className?.hashCode() ?: 0)
        result = 31 * result + clickable.hashCode()
        result = 31 * result + (visibleBounds?.hashCode() ?: 0)
        result = 31 * result + sensitiveScreenSignals.hashCode()
        result = 31 * result + children.hashCode()
        return result
    }

    override fun toString(): String =
        "ScreenNode(id=$id, role=$role, children=${children.size})"
}
