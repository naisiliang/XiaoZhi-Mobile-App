package com.lchuang.xiaozhimobile.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.lchuang.xiaozhimobile.screen.ScreenBounds
import com.lchuang.xiaozhimobile.screen.ScreenNode
import com.lchuang.xiaozhimobile.screen.SensitiveScreenDetector
import com.lchuang.xiaozhimobile.screen.SensitiveScreenSignals

class AccessibilitySnapshotBuilder {
    private val sensitiveScreenDetector = SensitiveScreenDetector()

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
        val inputTypeFlags = node.inputType
        val nodeHasPasswordField = node.isPassword ||
            sensitiveScreenDetector.isPasswordInputType(inputTypeFlags)
        return ScreenNode(
            id = "$windowFingerprint:$path",
            role = className,
            text = if (nodeHasPasswordField) null else nodeName(node.text),
            contentDescription = nodeName(node.contentDescription),
            className = className,
            clickable = node.isClickable,
            visibleBounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            children = children,
            sensitiveScreenSignals = SensitiveScreenSignals(
                inputTypeFlags = inputTypeFlags,
                passwordFieldPresent = nodeHasPasswordField ||
                    children.any { it.sensitiveScreenSignals.passwordFieldPresent },
            ),
        )
    }

    private fun nodeName(value: CharSequence?): String? = value?.toString()?.takeIf(String::isNotBlank)
}
