package com.lchuang.xiaozhimobile.screen

import java.util.Collections

class ScreenNode(
    val id: String,
    val role: String? = null,
    val text: String? = null,
    children: List<ScreenNode> = emptyList(),
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
            children == other.children

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (role?.hashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + children.hashCode()
        return result
    }

    override fun toString(): String =
        "ScreenNode(id=$id, role=$role, text=$text, children=$children)"
}
