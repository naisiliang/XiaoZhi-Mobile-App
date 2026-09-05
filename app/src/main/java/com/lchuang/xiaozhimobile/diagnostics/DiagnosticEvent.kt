package com.lchuang.xiaozhimobile.diagnostics

class DiagnosticEvent(
    timestamp: Long,
    sessionId: String,
    module: String,
    action: String,
    resultCode: String,
    durationMs: Long,
    safeMetadata: Map<String, String>,
) {
    val timestamp: Long = timestamp
    val sessionId: String = sessionId
    val module: String = module
    val action: String = action
    val resultCode: String = resultCode
    val durationMs: Long = durationMs
    val safeMetadata: Map<String, String> = sanitize(safeMetadata)

    companion object {
        private val forbiddenKey = Regex(
            "(?i)^(api[_-]?key|password|passwd|passcode|otp|verification[_-]?code|" +
                "security[_-]?code|payment|card(?:[_-]?(number|holder|expiry))?|cvv|cvc|" +
                "screenshot|screen[_-]?shot|raw[_-]?(screenshot|data|image))$",
        )
        private val sensitiveAssignment = Regex(
            "(?i)(^|[^a-z0-9])['\"]?(?:api[_-]?key|password|passcode|" +
                "access[ _-]?token|otp|verification[ _-]?code|security[ _-]?code|payment|" +
                "card(?:[_-]?(number|holder|expiry))?|cvv|cvc|screenshot|screen[ _-]?shot|" +
                "raw[ _-]?screenshot)['\"]?\\s*[:=]\\s*['\"]?\\S",
        )
        private val apiKeyValue = Regex("(?i)\\bsk-[a-z0-9_-]+\\b")
        private val bearerValue = Regex("(?i)\\bbearer\\s+[a-z0-9._~-]+\\b")
        private val rawImageValue = Regex(
            "(?i)\\bdata:image/(png|jpeg|webp);base64,[a-z0-9+/]{16,}={0,2}",
        )
        private val cardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

        private fun sanitize(metadata: Map<String, String>): Map<String, String> =
            metadata.asSequence()
                .filterNot { (key, value) -> isSensitive(key, value) }
                .map { (key, value) -> key to value }
                .toMap()

        private fun isSensitive(key: String, value: String): Boolean =
            forbiddenKey.matches(key.trim()) ||
                sensitiveAssignment.containsMatchIn(value) ||
                apiKeyValue.containsMatchIn(value) ||
                bearerValue.containsMatchIn(value) ||
                rawImageValue.containsMatchIn(value) ||
                cardCandidate.findAll(value).any { isLuhnCard(it.value.filter(Char::isDigit)) }

        private fun isLuhnCard(digits: String): Boolean {
            if (digits.length !in 13..19) return false
            var sum = 0
            var double = false
            for (digit in digits.reversed()) {
                var number = digit - '0'
                if (double) {
                    number *= 2
                    if (number > 9) number -= 9
                }
                sum += number
                double = !double
            }
            return sum % 10 == 0
        }
    }
}
