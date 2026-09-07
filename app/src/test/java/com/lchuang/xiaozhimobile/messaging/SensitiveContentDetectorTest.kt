package com.lchuang.xiaozhimobile.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentDetectorTest {
    private val detector = SensitiveContentDetector()

    @Test
    fun credentialAndVerificationContentIsBlocked() {
        listOf(
            "我的支付密码是123456",
            "登录密码: hunter2",
            "验证码 482901",
            "OTP: 482901",
            "恢复码 AB7Q-9K2M",
        ).forEach { text ->
            assertTrue("must block: $text", detector.detect(text).blocked)
        }
    }

    @Test
    fun ordinaryTransferReportIsNotCredentialContent() {
        assertFalse(detector.detect("告诉张三我已经转了100元").blocked)
        assertFalse(detector.detect("明天早上九点见").blocked)
    }

    @Test
    fun ambiguousStandaloneCodeIsBlockedWithoutEchoingTheSecret() {
        val secret = "482901"
        val result = detector.detect(secret)

        assertTrue(result.blocked)
        assertTrue(result.category != null)
        assertFalse(result.toString().contains(secret))
    }
}
