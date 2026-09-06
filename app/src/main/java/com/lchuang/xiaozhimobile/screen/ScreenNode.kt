package com.lchuang.xiaozhimobile.screen

data class ScreenNode(
    val id: String,
    val role: String? = null,
    val text: String? = null,
    val children: List<ScreenNode> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Screen node id must not be blank" }
    }
}
