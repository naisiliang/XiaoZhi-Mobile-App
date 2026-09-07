package com.lchuang.xiaozhimobile.extensions.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lchuang.xiaozhimobile.R
import com.lchuang.xiaozhimobile.extensions.agents.AgentRegistry

/** Displays the bounded, declarative built-in agents and their local state. */
class AgentCenterActivity : Activity() {
    private lateinit var root: ScrollView
    private lateinit var status: TextView
    private lateinit var agentCards: LinearLayout
    private val agentRegistry = AgentRegistry(AgentRegistry.builtIns())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_center)
        root = findViewById(R.id.agent_center_root)
        status = findViewById(R.id.agent_center_status)
        agentCards = findViewById(R.id.agent_cards)
        configureInsets()
        render()
    }

    private fun render() {
        agentCards.removeAllViews()
        agentRegistry.all().forEach { agent ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_assistant_card)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                agentCards.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            addText(card, agent.name, 17f, Color.rgb(23, 32, 51), true)
            addText(card, "${agent.id} · v${agent.version}", 13f, Color.rgb(104, 115, 134))
            addText(card, "工具：${agent.allowedTools.joinToString().ifBlank { "无" }}", 13f)
            addText(
                card,
                "预算：最大 ${agent.maxToolCalls} 次工具调用 · 委派深度 ${agent.maxDelegationDepth} · ${agent.maxExecutionTimeMs}ms",
                13f,
            )
            val enabled = agentRegistry.isEnabled(agent.id)
            val health = if (enabled) "健康状态：已启用" else "健康状态：已停用"
            addText(card, health, 12f, if (enabled) Color.rgb(48, 126, 86) else Color.rgb(166, 111, 32))
            val toggle = Switch(this).apply {
                text = "启用"
                isChecked = enabled
            }
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) agentRegistry.enable(agent.id) else agentRegistry.disable(agent.id)
                status.text = if (checked) "已启用 ${agent.name}" else "已停用 ${agent.name}"
                render()
            }
            card.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
