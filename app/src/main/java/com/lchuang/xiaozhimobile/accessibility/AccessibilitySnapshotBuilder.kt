package com.lchuang.xiaozhimobile.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.lchuang.xiaozhimobile.screen.ScreenBounds
import com.lchuang.xiaozhimobile.screen.ScreenNode

class AccessibilitySnapshotBuilder {
    fun build(root: AccessibilityNodeInfo, windowFingerprint: String): ScreenNode? =
        buildNode(root, windowFingerprint, "0")

    private fun buildNode(
        node: AccessibilityNodeInfo,
        windowFingerprint: String,
        path: String,
    ): ScreenNode? {
        if (!node.isVisibleToUser) return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children = buildList {
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    buildNode(child, windowFingerprint, "$path.$index")?.let(::add)
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }
        val className = nodeName(node.className)
        return ScreenNode(
            id = "$windowFingerprint:$path",
            role = className,
            text = nodeName(node.text),
            contentDescription = nodeName(node.contentDescription),
            className = className,
            clickable = node.isClickable,
            visibleBounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            children = children,
        )
    }

    private fun nodeName(value: CharSequence?): String? = value?.toString()?.takeIf(String::isNotBlank)
}
