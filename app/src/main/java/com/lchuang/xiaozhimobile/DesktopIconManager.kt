package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import java.io.File
import kotlin.math.min

class DesktopIconManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val customFile = File(appContext.filesDir, CUSTOM_ICON_FILE)

    fun applyCustomIcon(uri: Uri): Result<String> = runCatching {
        val decoded = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("无法读取所选图片")
        val bitmap = cropSquare(decoded, ICON_SIZE)
        customFile.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "保存图标失败" }
        }
        updateShortcut(bitmap, requestPinIfMissing = true)
        "桌面图标已更新"
    }

    fun restoreDefault(): Result<String> = runCatching {
        val hadCustomIcon = customFile.exists()
        if (hadCustomIcon) customFile.delete()
        if (hadCustomIcon) {
            updateShortcut(defaultBitmap(), requestPinIfMissing = false)
            "已恢复默认桌面图标"
        } else {
            "当前已经是默认 Logo"
        }
    }

    fun currentBitmap(): Bitmap {
        if (customFile.exists()) {
            BitmapFactory.decodeFile(customFile.absolutePath)?.let { return it }
        }
        return defaultBitmap()
    }

    private fun defaultBitmap(): Bitmap {
        return BitmapFactory.decodeResource(appContext.resources, R.mipmap.ic_launcher)
            ?: error("默认图标资源不可用")
    }

    private fun cropSquare(source: Bitmap, targetSize: Int): Bitmap {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, left, top, side, side)
        return if (cropped.width == targetSize && cropped.height == targetSize) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        }
    }

    private fun updateShortcut(bitmap: Bitmap, requestPinIfMissing: Boolean) {
        val manager = appContext.getSystemService(ShortcutManager::class.java)
            ?: error("系统不支持桌面快捷方式")
        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val info = ShortcutInfo.Builder(appContext, SHORTCUT_ID)
            .setShortLabel("小智手机助手")
            .setLongLabel("小智手机助手")
            .setIcon(Icon.createWithBitmap(bitmap))
            .setIntent(launchIntent)
            .build()

        // Keep a dynamic shortcut with the same ID so most launchers update an
        // already-pinned shortcut when the user changes the image again.
        manager.addDynamicShortcuts(listOf(info))
        manager.updateShortcuts(listOf(info))

        val alreadyPinned = manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }
        if (!alreadyPinned && requestPinIfMissing) {
            check(manager.isRequestPinShortcutSupported) { "当前桌面不支持固定自定义快捷图标" }
            check(manager.requestPinShortcut(info, null)) { "桌面拒绝创建快捷图标" }
        }
    }

    companion object {
        const val SHORTCUT_ID = "xiaozhi-custom-desktop"
        private const val CUSTOM_ICON_FILE = "xiaozhi_custom_desktop_icon.png"
        private const val ICON_SIZE = 512
    }
}
