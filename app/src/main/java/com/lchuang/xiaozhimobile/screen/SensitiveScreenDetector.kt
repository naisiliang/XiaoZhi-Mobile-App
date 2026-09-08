package com.lchuang.xiaozhimobile.screen

import java.util.Locale

enum class SensitiveScreenCategory {
    PAYMENT,
    TRANSFER,
    CREDENTIAL,
    VERIFICATION,
    SECURITY_SETTINGS,
    ACCOUNT_DELETION,
    UNKNOWN_HIGH_RISK,
}

/**
 * Short-lived signals supplied by a current UI adapter. Credential values are
 * intentionally not part of this object.
 */
data class SensitiveScreenSignals(
    val inputTypeFlags: Int = 0,
    val passwordFieldPresent: Boolean = false,
)

data class SensitiveScreenDecision(
    val isSensitive: Boolean,
    val category: SensitiveScreenCategory? = null,
)

class SensitiveScreenDetector {
    fun detect(
        packageName: String?,
        root: ScreenNode?,
        signals: SensitiveScreenSignals = SensitiveScreenSignals(),
    ): SensitiveScreenDecision {
        if (root == null) return sensitive(SensitiveScreenCategory.UNKNOWN_HIGH_RISK)

        val effectiveSignals = SensitiveScreenSignals(
            inputTypeFlags = signals.inputTypeFlags or root.sensitiveScreenSignals.inputTypeFlags,
            passwordFieldPresent = signals.passwordFieldPresent ||
                root.sensitiveScreenSignals.passwordFieldPresent,
        )
        if (effectiveSignals.passwordFieldPresent || isPasswordInputType(effectiveSignals.inputTypeFlags)) {
            return sensitive(SensitiveScreenCategory.CREDENTIAL)
        }

        val labels = collectSemanticLabels(root)
        findLabelCategory(labels)?.let { return sensitive(it) }

        findPackageCategory(packageName)?.let { return sensitive(it) }

        if (labels.isEmpty()) {
            return sensitive(SensitiveScreenCategory.UNKNOWN_HIGH_RISK)
        }
        return SensitiveScreenDecision(isSensitive = false)
    }

    private fun collectSemanticLabels(root: ScreenNode): List<String> {
        val labels = mutableListOf<String>()

        fun visit(node: ScreenNode) {
            // className is implementation metadata, not a semantic label. In
            // particular, an opaque third-party class must never make a screen
            // look understood merely because it contains a dotted name.
            listOf(node.text, node.contentDescription)
                .filterNotNull()
                .mapTo(labels) { normalize(it) }
            node.role?.let { role ->
                val normalized = normalize(role)
                if (normalized.isNotBlank() &&
                    !isImplementationAccessibilityLabel(normalized, node.className)
                ) {
                    labels += normalized
                }
            }
            node.children.forEach(::visit)
        }

        visit(root)
        return labels.filter(String::isNotBlank).filterNot(::isGenericAccessibilityLabel)
    }

    private fun isGenericAccessibilityLabel(label: String): Boolean =
        label.startsWith("android.") ||
            label.startsWith("androidx.") ||
            label in GENERIC_ACCESSIBILITY_LABELS

    /** Roles copied from AccessibilityNodeInfo class names are metadata, not screen semantics. */
    private fun isImplementationAccessibilityLabel(label: String, className: String?): Boolean =
        label.contains('.') ||
            isGenericAccessibilityLabel(label) ||
            normalizedClassName(className) == label

    private fun normalizedClassName(className: String?): String =
        className?.let(::normalize).orEmpty()

    private fun findLabelCategory(labels: List<String>): SensitiveScreenCategory? {
        for ((category, markers) in LABEL_MARKERS) {
            if (labels.any { label -> markers.any(label::contains) }) return category
        }
        return null
    }

    private fun findPackageCategory(packageName: String?): SensitiveScreenCategory? {
        val normalized = packageName?.let(::normalize) ?: return null
        return PACKAGE_MARKERS.firstOrNull { (marker, _) -> normalized.contains(marker) }?.second
    }

    private fun sensitive(category: SensitiveScreenCategory) =
        SensitiveScreenDecision(isSensitive = true, category = category)

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    internal fun isPasswordInputType(inputTypeFlags: Int): Boolean {
        val variation = inputTypeFlags and INPUT_TYPE_VARIATION_MASK
        return variation in PASSWORD_VARIATIONS
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val INPUT_TYPE_VARIATION_MASK = 0x00000ff0

        // Android InputType password variations: text, visible text, web text,
        // and numeric passwords. Kept as constants so this detector remains a
        // pure semantic policy and does not copy credential values.
        val PASSWORD_VARIATIONS = setOf(0x80, 0x90, 0xe0, 0x10)
        val GENERIC_ACCESSIBILITY_LABELS = setOf(
            "view", "viewgroup", "button", "textview", "edittext", "imageview",
            "framelayout", "linearlayout", "relativelayout", "scrollview", "listview",
            "recyclerview", "composeview", "window", "container", "root", "screen", "layout",
        )

        val LABEL_MARKERS = listOf(
            SensitiveScreenCategory.PAYMENT to setOf(
                "支付", "付款", "payment", "checkout", "pay now", "支付宝", "银行卡",
            ),
            SensitiveScreenCategory.TRANSFER to setOf(
                "转账", "汇款", "transfer", "send money", "money transfer", "红包", "red packet",
            ),
            SensitiveScreenCategory.CREDENTIAL to setOf(
                "密码", "password", "passcode", "pin code", "pin码",
            ),
            SensitiveScreenCategory.VERIFICATION to setOf(
                "验证码", "verification code", "one-time password", "otp", "captcha",
                "身份认证", "短信验证", "安全验证码", "authentication",
            ),
            SensitiveScreenCategory.SECURITY_SETTINGS to setOf(
                "安全设置", "security settings", "privacy and security", "account security",
                "账号安全", "two-factor", "双重认证", "2fa",
            ),
            SensitiveScreenCategory.ACCOUNT_DELETION to setOf(
                "删除账户", "删除账号", "注销账号", "delete account", "deactivate account",
                "删除数据", "删除聊天记录",
            ),
        )

        val PACKAGE_MARKERS = listOf(
            "alipay" to SensitiveScreenCategory.PAYMENT,
            "paypal" to SensitiveScreenCategory.PAYMENT,
            "payment" to SensitiveScreenCategory.PAYMENT,
            "wallet" to SensitiveScreenCategory.PAYMENT,
            "banking" to SensitiveScreenCategory.PAYMENT,
            ".bank" to SensitiveScreenCategory.PAYMENT,
        )
    }
}
