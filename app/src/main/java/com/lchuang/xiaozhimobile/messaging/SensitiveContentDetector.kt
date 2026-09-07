package com.lchuang.xiaozhimobile.messaging

import java.util.Locale

/**
 * Side-effect-free content gate for ordinary text messaging.
 *
 * The result intentionally contains only a category. It never retains or
 * echoes the inspected message, so callers cannot accidentally expose a
 * credential in a status line or diagnostic event.
 */
class SensitiveContentDetector {
    enum class Category {
        PASSWORD,
        PAYMENT_PASSWORD,
        OTP,
        RECOVERY_CODE,
        AMBIGUOUS_CODE,
    }

    data class Result(
        val blocked: Boolean,
        val category: Category? = null,
    ) {
        init {
            require(blocked == (category != null)) {
                "Sensitive content result must have a category exactly when blocked"
            }
        }

        val allowed: Boolean
            get() = !blocked
    }

    fun detect(text: String): Result {
        val normalized = text.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return Result(blocked = false)

        return when {
            PAYMENT_PASSWORD_MARKER.containsMatchIn(normalized) -> blocked(Category.PAYMENT_PASSWORD)
            PASSWORD_MARKER.containsMatchIn(normalized) -> blocked(Category.PASSWORD)
            OTP_MARKER.containsMatchIn(normalized) -> blocked(Category.OTP)
            RECOVERY_MARKER.containsMatchIn(normalized) -> blocked(Category.RECOVERY_CODE)
            STANDALONE_CODE.containsMatchIn(normalized) -> blocked(Category.AMBIGUOUS_CODE)
            else -> Result(blocked = false)
        }
    }

    private fun blocked(category: Category) = Result(blocked = true, category = category)

    private companion object {
        val PAYMENT_PASSWORD_MARKER = Regex(
            "(?:支付|付款|银行卡支付|wallet)\\s*(?:密码|口令)|payment\\s*password",
        )
        val PASSWORD_MARKER = Regex(
            "密码|口令|password|passwd|passcode|\\bpin\\b",
        )
        val OTP_MARKER = Regex(
            "验证码|校验码|动态码|一次性密码|一次性验证码|短信码|otp|one[- ]time\\s+(?:password|code)",
        )
        val RECOVERY_MARKER = Regex(
            "恢复码|恢复代码|备用码|备份码|救援码|助记词|私钥|recovery\\s+code|backup\\s+code|seed\\s+phrase",
        )
        val STANDALONE_CODE = Regex("(?<!\\d)\\d{6,8}(?!\\d)")
    }
}
