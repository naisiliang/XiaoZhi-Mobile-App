package com.lchuang.xiaozhimobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveScreenDetectorTest {
    private val detector = SensitiveScreenDetector()

    @Test
    fun `payment and transfer labels are sensitive`() {
        val payment = detector.detect(
            packageName = "com.eg.android.AlipayGphone",
            root = ScreenNode(id = "root", text = "确认支付"),
        )
        val transfer = detector.detect(
            packageName = "com.example.wallet",
            root = ScreenNode(id = "root", contentDescription = "转账"),
        )

        assertTrue(payment.isSensitive)
        assertEquals(SensitiveScreenCategory.PAYMENT, payment.category)
        assertTrue(transfer.isSensitive)
        assertEquals(SensitiveScreenCategory.TRANSFER, transfer.category)
    }

    @Test
    fun `credential verification and security labels are sensitive`() {
        val cases = listOf(
            ScreenNode(id = "password", text = "请输入登录密码") to SensitiveScreenCategory.CREDENTIAL,
            ScreenNode(id = "otp", text = "输入短信验证码") to SensitiveScreenCategory.VERIFICATION,
            ScreenNode(id = "security", text = "账号安全设置") to SensitiveScreenCategory.SECURITY_SETTINGS,
            ScreenNode(id = "delete", text = "删除账户") to SensitiveScreenCategory.ACCOUNT_DELETION,
        )

        cases.forEach { (root, expectedCategory) ->
            val result = detector.detect("com.android.settings", root)
            assertTrue(result.isSensitive)
            assertEquals(expectedCategory, result.category)
        }
    }

    @Test
    fun `password field signals are sensitive without retaining credential text`() {
        val result = detector.detect(
            packageName = "com.example.login",
            root = ScreenNode(id = "root", text = "登录"),
            signals = SensitiveScreenSignals(passwordFieldPresent = true),
        )

        assertTrue(result.isSensitive)
        assertEquals(SensitiveScreenCategory.CREDENTIAL, result.category)
    }

    @Test
    fun `android password input variations are sensitive`() {
        val result = detector.detect(
            packageName = "com.example.login",
            root = ScreenNode(id = "root"),
            signals = SensitiveScreenSignals(inputTypeFlags = 0x00000080),
        )

        assertTrue(result.isSensitive)
        assertEquals(SensitiveScreenCategory.CREDENTIAL, result.category)
    }

    @Test
    fun `nested high risk labels and banking packages are sensitive`() {
        val nested = detector.detect(
            packageName = "com.example.shopping",
            root = ScreenNode(
                id = "root",
                children = listOf(ScreenNode(id = "child", text = "银行卡信息")),
            ),
        )
        val bankingPackage = detector.detect(
            packageName = "com.example.mobilebanking",
            root = ScreenNode(id = "root"),
        )

        assertTrue(nested.isSensitive)
        assertEquals(SensitiveScreenCategory.PAYMENT, nested.category)
        assertTrue(bankingPackage.isSensitive)
        assertEquals(SensitiveScreenCategory.PAYMENT, bankingPackage.category)
    }

    @Test
    fun `ordinary map list and chat screens remain non-sensitive`() {
        val cases = listOf(
            "com.autonavi.minimap" to ScreenNode(id = "map", text = "导航到第一家餐厅"),
            "com.example.shopping" to ScreenNode(id = "list", text = "第二个商品"),
            "com.tencent.mm" to ScreenNode(id = "chat", text = "告诉张三我已经到了"),
        )

        cases.forEach { (packageName, root) ->
            val result = detector.detect(packageName, root)
            assertFalse(result.isSensitive)
            assertEquals(null, result.category)
        }
    }

    @Test
    fun `navigation wording is not mistaken for money transfer`() {
        val result = detector.detect(
            packageName = "com.autonavi.minimap",
            root = ScreenNode(id = "root", text = "转到下一个路口"),
        )

        assertFalse(result.isSensitive)
    }

    @Test
    fun `missing context is conservatively sensitive`() {
        val result = detector.detect(packageName = null, root = null)

        assertTrue(result.isSensitive)
        assertEquals(SensitiveScreenCategory.UNKNOWN_HIGH_RISK, result.category)
    }
}
