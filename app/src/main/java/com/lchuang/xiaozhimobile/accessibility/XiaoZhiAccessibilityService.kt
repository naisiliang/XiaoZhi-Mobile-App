package com.lchuang.xiaozhimobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.lchuang.xiaozhimobile.screen.ScreenContextStore

class XiaoZhiAccessibilityService : AccessibilityService() {
    private val snapshotBuilder = AccessibilitySnapshotBuilder()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in SNAPSHOT_EVENTS) return
        val root = rootInActiveWindow
        if (root == null) {
            ScreenContextStoreProvider.instance.invalidate()
            return
        }
        try {
            val packageName = event.packageName?.toString()
                ?: root.packageName?.toString()
                ?: run {
                    ScreenContextStoreProvider.instance.invalidate()
                    return
                }
            val windowFingerprint = "$packageName:${root.windowId}"
            val snapshot = snapshotBuilder.build(root, windowFingerprint)
                ?: run {
                    ScreenContextStoreProvider.instance.invalidate()
                    return
                }
            ScreenContextStoreProvider.instance.publish(packageName, windowFingerprint, snapshot)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() {
        ScreenContextStoreProvider.instance.invalidate()
    }

    override fun onDestroy() {
        ScreenContextStoreProvider.instance.invalidate()
        super.onDestroy()
    }

    private companion object {
        val SNAPSHOT_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
        )
    }
}

internal object ScreenContextStoreProvider {
    val instance = ScreenContextStore()
}
