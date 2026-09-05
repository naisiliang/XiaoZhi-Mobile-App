package com.lchuang.xiaozhimobile.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class CentralSafetyPolicyEngineTest {
    private val engine = CentralSafetyPolicyEngine()

    @Test
    fun evaluatesUnknownConfirmationAndOrdinaryTools() {
        val unknown = engine.evaluate(tool("unknown"))
        assertEquals(ToolDecision.BLOCK, unknown.decision)
        assertEquals(ToolBlockReason.UNKNOWN_TOOL, unknown.blockReason)
        assertEquals(ToolDecision.CONFIRM, engine.evaluate(tool("send_text_message")).decision)
        assertEquals(ToolDecision.ALLOW, engine.evaluate(tool("open_app")).decision)
    }

    @Test
    fun blocksSensitiveAndDestructiveTools() {
        listOf(
            "pay",
            "payment",
            "transfer_money",
            "enter_password",
            "read_otp",
            "delete_file",
            "destructive_operation",
            "root_shell",
            "run_shell_command"
        ).forEach { name ->
            val result = engine.evaluate(tool(name))
            assertEquals(ToolDecision.BLOCK, result.decision)
            assertEquals(ToolBlockReason.RESTRICTED_TOOL, result.blockReason)
        }
    }

    private fun tool(name: String) = ToolInvocation(name = name, arguments = emptyMap())
}
