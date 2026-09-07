package com.lchuang.xiaozhimobile.extensions.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lchuang.xiaozhimobile.R
import com.lchuang.xiaozhimobile.extensions.ExtensionPermission
import com.lchuang.xiaozhimobile.extensions.ExtensionRepository
import com.lchuang.xiaozhimobile.extensions.ManagedExtension
import com.lchuang.xiaozhimobile.extensions.PluginManager
import com.lchuang.xiaozhimobile.extensions.PluginManagerCode
import com.lchuang.xiaozhimobile.extensions.PluginManagerResult
import com.lchuang.xiaozhimobile.extensions.mcp.McpRegistry
import com.lchuang.xiaozhimobile.extensions.skills.SkillRegistry
import java.io.IOException
import java.util.concurrent.Executors

/**
 * User-facing extension center. It only imports validated declarative packages;
 * no package code is loaded or rendered as executable content.
 */
class ExtensionCenterActivity : Activity() {
    private lateinit var root: ScrollView
    private lateinit var status: TextView
    private lateinit var extensionCards: LinearLayout
    private lateinit var skillCards: LinearLayout
    private lateinit var mcpCards: LinearLayout
    private lateinit var pluginManager: PluginManager
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var mcpRegistry: McpRegistry
    private val importExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extension_center)
        root = findViewById(R.id.extension_center_root)
        status = findViewById(R.id.extension_center_status)
        extensionCards = findViewById(R.id.extension_cards)
        skillCards = findViewById(R.id.skill_cards)
        mcpCards = findViewById(R.id.mcp_cards)
        pluginManager = PluginManager(ExtensionRepository.inAppPrivateStorage(this))
        skillRegistry = SkillRegistry()
        mcpRegistry = McpRegistry()
        findViewById<Button>(R.id.import_extension).setOnClickListener { openExtensionPicker() }
        configureInsets()
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: run {
            status.text = "导入失败：没有选择文件"
            return
        }
        status.text = "正在验证扩展包…"
        importExecutor.execute {
            val result = try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, "无法读取所选文件")
                } else {
                    input.use { pluginManager.importPackage(it) }
                }
            } catch (_: IOException) {
                PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, "无法读取所选文件")
            } catch (_: SecurityException) {
                PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, "文件访问被系统拒绝")
            } catch (_: RuntimeException) {
                PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, "扩展包读取失败")
            }
            if (!isFinishing && !isDestroyed) {
                runOnUiThread { handleImportResult(result) }
            }
        }
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun openExtensionPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/octet-stream", "application/zip", "application/x-xzpack"),
                )
            },
            REQUEST_IMPORT,
        )
    }

    private fun handleImportResult(result: PluginManagerResult) {
        when (result) {
            is PluginManagerResult.NeedsPermissionConfirmation -> {
                status.text = "已导入 ${result.extension.manifest.name}，等待权限确认"
                render()
                showPermissionReview(result.extension, result.requiredPermissions)
            }
            is PluginManagerResult.ImportedDisabled -> {
                status.text = "已导入 ${result.extension.manifest.name}，默认停用"
                render()
            }
            is PluginManagerResult.ImportedEnabled -> {
                status.text = "已更新 ${result.extension.manifest.name}，保持启用"
                render()
            }
            is PluginManagerResult.Rejected -> {
                status.text = "导入失败：${result.code.name}${result.detail.takeIf { it.isNotBlank() }?.let { "（$it）"}.orEmpty()}"
                render()
            }
            else -> {
                status.text = "扩展状态已更新"
                render()
            }
        }
    }

    private fun showPermissionReview(
        extension: ManagedExtension,
        requiredPermissions: Set<ExtensionPermission>,
    ) {
        val permissionText = requiredPermissions
            .sortedBy { it.wireName }
            .joinToString("\n") { "• ${permissionLabel(it)}" }
            .ifBlank { "• 无额外权限" }
        AlertDialog.Builder(this)
            .setTitle("确认扩展权限")
            .setMessage(
                "${extension.manifest.name}（${extension.manifest.version}）声明：\n$permissionText\n\n确认后才会启用；扩展包仍只能使用应用提供的声明式能力。",
            )
            .setNegativeButton("稍后") { _, _ ->
                status.text = "扩展已导入，等待权限确认"
            }
            .setPositiveButton("确认并启用") { _, _ ->
                val result = pluginManager.enable(extension.id, requiredPermissions)
                status.text = pluginResultMessage(result)
                render()
            }
            .show()
    }

    private fun render() {
        renderExtensions()
        renderSkills()
        renderMcp()
    }

    private fun renderExtensions() {
        extensionCards.removeAllViews()
        val extensions = pluginManager.installed()
        if (extensions.isEmpty()) {
            addEmptyState(extensionCards, "暂无已安装插件；可通过上方按钮导入 .xzpack。")
            return
        }
        extensions.forEach { extension ->
            val card = card(extensionCards)
            addText(card, extension.manifest.name, 17f, Color.rgb(23, 32, 51), true)
            addText(card, "${extension.id} · v${extension.version}", 13f, Color.rgb(104, 115, 134))
            addText(card, "能力：${extension.manifest.capabilities.joinToString().ifBlank { "未声明" }}", 13f)
            addText(
                card,
                "权限：${extension.manifest.permissions.sortedBy { it.wireName }.joinToString { permissionLabel(it) }.ifBlank { "无" }}",
                13f,
            )
            val health = if (extension.enabled) "健康状态：已启用" else "健康状态：已停用 / 待复核"
            addText(card, health, 12f, if (extension.enabled) Color.rgb(48, 126, 86) else Color.rgb(166, 111, 32))
            val actions = horizontalRow(card)
            val toggle = Switch(this).apply {
                text = if (extension.enabled) "启用" else "停用"
                isChecked = extension.enabled
            }
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (extension.manifest.permissions.isEmpty()) {
                        val result = pluginManager.enable(extension.id, emptySet())
                        status.text = pluginResultMessage(result)
                        render()
                    } else {
                        render()
                        showPermissionReview(extension, extension.manifest.permissions)
                    }
                } else {
                    val result = pluginManager.disable(extension.id)
                    status.text = pluginResultMessage(result)
                    render()
                }
            }
            actions.addView(toggle, rowWeightParams())
            val remove = Button(this).apply { text = "移除" }
            remove.setOnClickListener { confirmRemove(extension) }
            actions.addView(remove, rowWrapParams())
        }
    }

    private fun renderSkills() {
        skillCards.removeAllViews()
        val skills = skillRegistry.all()
        if (skills.isEmpty()) {
            addEmptyState(skillCards, "暂无已安装声明式技能；技能不会获得额外 Android 权限。")
            return
        }
        skills.forEach { skill ->
            val card = card(skillCards)
            addText(card, skill.name, 17f, Color.rgb(23, 32, 51), true)
            addText(card, "${skill.id} · v${skill.version}", 13f, Color.rgb(104, 115, 134))
            addText(card, "工具预算：${skill.toolBudget} · 健康状态：${if (skillRegistry.isEnabled(skill.id)) "已启用" else "已停用"}", 13f)
            val toggle = Switch(this).apply {
                text = "启用"
                isChecked = skillRegistry.isEnabled(skill.id)
            }
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) skillRegistry.enable(skill.id) else skillRegistry.disable(skill.id)
                render()
            }
            card.addView(toggle, rowWrapParams())
        }
    }

    private fun renderMcp() {
        mcpCards.removeAllViews()
        val servers = mcpRegistry.diagnostics()
        if (servers.isEmpty()) {
            addEmptyState(mcpCards, "暂无已添加 MCP 服务器；服务器必须由用户显式配置。")
            return
        }
        servers.forEach { diagnostic ->
            val card = card(mcpCards)
            addText(card, diagnostic.name, 17f, Color.rgb(23, 32, 51), true)
            addText(card, "${diagnostic.id} · ${diagnostic.type.wireName}", 13f, Color.rgb(104, 115, 134))
            val health = if (diagnostic.enabled) "健康状态：已启用，等待工具检查" else "健康状态：已停用"
            addText(card, health, 13f)
            val toggle = Switch(this).apply {
                text = "启用"
                isChecked = diagnostic.enabled
            }
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) mcpRegistry.enable(diagnostic.id) else mcpRegistry.disable(diagnostic.id)
                render()
            }
            card.addView(toggle, rowWrapParams())
        }
    }

    private fun confirmRemove(extension: ManagedExtension) {
        AlertDialog.Builder(this)
            .setTitle("移除扩展")
            .setMessage("确定移除 ${extension.manifest.name}？运行时注册和 app-private 存储都会清理。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                val result = pluginManager.remove(extension.id)
                status.text = pluginResultMessage(result)
                render()
            }
            .show()
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(16), bars.top + dp(16), dp(16), bars.bottom + dp(20))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_assistant_card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun addEmptyState(parent: LinearLayout, message: String) {
        val text = addText(parent, message, 14f, Color.rgb(104, 115, 134))
        text.setPadding(dp(4), dp(8), dp(4), dp(12))
    }

    private fun addText(
        parent: ViewGroup,
        value: String,
        size: Float,
        color: Int = Color.rgb(74, 96, 117),
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
    }

    private fun horizontalRow(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun rowWeightParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, -2, 1f)

    private fun rowWrapParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-2, -2)

    private fun pluginResultMessage(result: PluginManagerResult): String = when (result) {
        is PluginManagerResult.Enabled -> "已启用 ${result.extension.manifest.name}"
        is PluginManagerResult.Disabled -> "已停用 ${result.extension.manifest.name}"
        is PluginManagerResult.Removed -> "已移除 ${result.extensionId}"
        is PluginManagerResult.Rejected -> "操作失败：${result.code.name}"
        else -> "扩展状态已更新"
    }

    private fun permissionLabel(permission: ExtensionPermission): String = when (permission) {
        ExtensionPermission.NETWORK -> "网络"
        ExtensionPermission.READ_SCREEN -> "读取屏幕"
        ExtensionPermission.ACCESSIBILITY -> "辅助功能"
        ExtensionPermission.LOCATION -> "位置"
        ExtensionPermission.PHONE_CONTROL -> "电话控制"
        ExtensionPermission.MEDIA_CONTROL -> "媒体控制"
        ExtensionPermission.MESSAGING -> "消息"
        ExtensionPermission.FILES -> "文件"
        ExtensionPermission.VISION -> "视觉"
        ExtensionPermission.IMAGE_GENERATION -> "图像生成"
        ExtensionPermission.MCP -> "MCP"
        ExtensionPermission.CAMERA -> "相机"
        ExtensionPermission.MICROPHONE -> "麦克风"
        ExtensionPermission.NOTIFICATIONS -> "通知"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_IMPORT = 701
    }
}
